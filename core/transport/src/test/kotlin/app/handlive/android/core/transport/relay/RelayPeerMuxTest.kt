package app.handlive.android.core.transport.relay

import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityOp
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.relay.RelayOutbound
import app.handlive.android.core.protocol.session.SessionByeData
import app.handlive.android.core.protocol.session.SessionOp
import app.handlive.android.core.transport.WsCloseCode
import app.handlive.android.core.transport.server.ControlConnectionState
import app.handlive.android.core.transport.server.ControlServerLimits
import app.handlive.android.core.transport.server.ControlSession
import app.handlive.android.core.transport.server.SessionTransport
import app.handlive.android.core.transport.session.SessionCipher
import app.handlive.android.core.transport.testing.LoopbackServerFixture
import app.handlive.android.core.transport.testing.TestClientPeer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** `/v1/ctl` sessions through the relay (CONN-03 step 9): the LAN handshake and envelopes inside `{to|from, env}`. */
class RelayPeerMuxTest {
    private val fixture = LoopbackServerFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val wrapped = LinkedBlockingQueue<String>()
    private val mux =
        RelayPeerMux(scope, { wrapped.add(it) }) { link, peer -> fixture.server.serveRelayPeer(link, peer) }

    @After
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aClientHandshakesThroughTheRelayAndGetsARelaySession() =
        runBlocking {
            val peer = fixture.addPair()
            val (session, cipher) = handshake(peer)
            assertEquals(SessionTransport.RELAY, session.transport)
            assertEquals(ControlConnectionState.ESTABLISHED, session.state.value)

            // An envelope of the phone reaches the client wrapped with `to`, and the client's answer comes back.
            session.send(MessageType.PING, """{"op":"ping","data":{"seq":1}}""".toByteArray())
            val out = next(peer.deviceId)
            assertEquals(MessageType.PING.wire, out.type)
            cipher.open(out)
            val ackText =
                PlaintextCodec.encodeOp(
                    "ping",
                    kotlinx.serialization.json.JsonObject
                        .serializer(),
                    kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            mux.onEnvelope(
                peer.deviceId,
                cipher.seal(EnvelopeHeader(MessageType.PING.wire, peer.ids.next(), now()), ackText),
            )
            val received = withTimeoutMillis { session.inbound.receive() }
            assertEquals(MessageType.PING.wire, received.type)
        }

    @Test
    fun aPeerThatLeftTheRelayLosesItsSession() =
        runBlocking {
            val peer = fixture.addPair()
            val (session, _) = handshake(peer)
            mux.onPeerGone(peer.deviceId)
            waitUntil { session.state.value == ControlConnectionState.CLOSED }
            assertEquals(0, mux.peerCount)
        }

    @Test
    fun aNewHelloReplacesTheSessionOfThePair() =
        runBlocking {
            val peer = fixture.addPair()
            val (first, _) = handshake(peer)
            val (second, _) = handshake(peer)
            waitUntil { first.state.value == ControlConnectionState.CLOSED }
            assertEquals(ControlConnectionState.ESTABLISHED, second.state.value)
            assertTrue(fixture.server.sessions.get(peer.pairId) === second)
            // The peer started over: a bye sealed with the keys it dropped would only fail there (CONN-02 E5).
            assertNull(wrapped.poll(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun aRelayedSessionTheServerEndsSaysByeShutdownFirst() =
        runBlocking {
            val peer = fixture.addPair()
            val (session, cipher) = handshake(peer)

            session.close(WsCloseCode.BAD_REQUEST, "BAD_REQUEST")

            assertEquals("shutdown", byeReason(cipher, next(peer.deviceId)))
            waitUntil { session.state.value == ControlConnectionState.CLOSED }
            assertNull(wrapped.poll(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun anIdleRelayedSessionSaysByeShutdownBeforeItEnds() =
        runBlocking {
            LoopbackServerFixture(limits = ControlServerLimits(idleTimeout = IDLE_TIMEOUT)).use { idle ->
                val out = LinkedBlockingQueue<String>()
                val idleMux =
                    RelayPeerMux(scope, { out.add(it) }) { link, peer -> idle.server.serveRelayPeer(link, peer) }
                val peer = idle.addPair()
                val (session, cipher) = handshake(peer, idleMux, out, idle)

                assertEquals("shutdown", byeReason(cipher, next(peer.deviceId, out)))
                waitUntil { session.state.value == ControlConnectionState.CLOSED }
            }
        }

    @Test
    fun aByeFromThePeerEndsTheRelayedSessionWithoutAnAnswer() =
        runBlocking {
            val peer = fixture.addPair()
            val (session, cipher) = handshake(peer)
            val bye = PlaintextCodec.encodeOp(SessionOp.BYE, SessionByeData.serializer(), SessionByeData("replaced"))

            mux.onEnvelope(
                peer.deviceId,
                cipher.seal(EnvelopeHeader(MessageType.SESSION.wire, peer.ids.next(), now()), bye),
            )

            waitUntil { session.state.value == ControlConnectionState.CLOSED }
            assertEquals("replaced", session.byeReason.value)
            assertNull(wrapped.poll(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun switchingTheRelayOffSendsTheCapabilityThenByeShutdown() =
        runBlocking {
            val peer = fixture.addPair()
            val (session, cipher) = handshake(peer)

            fixture.server.closeRelayedSessions()

            val capability = next(peer.deviceId)
            assertEquals(MessageType.CAPABILITY.wire, capability.type)
            assertEquals(CapabilityOp.UPDATE, PlaintextCodec.decodePayload(cipher.open(capability)).op)
            assertEquals("shutdown", byeReason(cipher, next(peer.deviceId)))
            waitUntil { session.state.value == ControlConnectionState.CLOSED }
            // One bye only: closing afterwards adds none.
            assertNull(wrapped.poll(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun envelopesOfAPeerWithoutASessionAreDropped() =
        runBlocking {
            val peer = fixture.addPair()
            val (hello, _) = peer.hello()
            val stray = EnvelopeCodec.decode(hello).copy(type = MessageType.PING.wire)
            mux.onEnvelope(peer.deviceId, stray)
            assertEquals(0, mux.peerCount)
            assertEquals(null, wrapped.poll(SHORT_WAIT_MILLIS, TimeUnit.MILLISECONDS))
        }

    /** The client side of the handshake, every envelope through the mux; returns the phone's session. */
    private suspend fun handshake(
        peer: TestClientPeer,
        through: RelayPeerMux = mux,
        out: LinkedBlockingQueue<String> = wrapped,
        server: LoopbackServerFixture = fixture,
    ): Pair<ControlSession, SessionCipher> {
        val (hello, state) = peer.hello()
        through.onEnvelope(peer.deviceId, EnvelopeCodec.decode(hello))
        val keys = peer.acceptWelcome(EnvelopeCodec.encode(next(peer.deviceId, out)), state)
        val cipher = SessionCipher(keys, PeerRole.CLIENT)
        cipher.open(next(peer.deviceId, out))
        val capability =
            PlaintextCodec.encodeOp(
                CapabilityOp.HELLO,
                CapabilityData.serializer(),
                LoopbackServerFixture.MAC_CAPABILITY,
            )
        through.onEnvelope(
            peer.deviceId,
            cipher.seal(EnvelopeHeader(MessageType.CAPABILITY.wire, peer.ids.next(), now()), capability),
        )
        return server.awaitSession() to cipher
    }

    /** The `reason` of a `session/bye` the phone sent, opened with the client's keys. */
    private fun byeReason(
        cipher: SessionCipher,
        envelope: app.handlive.android.core.protocol.envelope.Envelope,
    ): String {
        assertEquals(MessageType.SESSION.wire, envelope.type)
        val bye = PlaintextCodec.decodeOp(cipher.open(envelope), SessionByeData.serializer())
        assertEquals(SessionOp.BYE, bye.op)
        return bye.data.reason
    }

    /** The next envelope the phone sent through the relay, checked to be wrapped for [to]. */
    private fun next(
        to: String,
        out: LinkedBlockingQueue<String> = wrapped,
    ): app.handlive.android.core.protocol.envelope.Envelope {
        val text = checkNotNull(out.poll(WAIT_SECONDS, TimeUnit.SECONDS)) { "nothing sent through the relay" }
        val outbound = ProtocolJson.decodeFromString(RelayOutbound.serializer(), text)
        assertEquals(to, outbound.to)
        return outbound.env
    }

    private suspend fun <T> withTimeoutMillis(block: suspend () -> T): T =
        kotlinx.coroutines.withTimeout(WAIT_SECONDS * 1_000) { block() }

    private suspend fun waitUntil(condition: () -> Boolean) =
        withTimeoutMillis {
            while (!condition()) kotlinx.coroutines.delay(POLL_MILLIS)
        }

    private fun now() = System.currentTimeMillis()

    private companion object {
        val IDLE_TIMEOUT = 1.seconds
        const val WAIT_SECONDS = 5L
        const val SHORT_WAIT_MILLIS = 300L
        const val POLL_MILLIS = 20L
    }
}
