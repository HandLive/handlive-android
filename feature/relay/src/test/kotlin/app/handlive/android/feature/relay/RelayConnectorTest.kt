package app.handlive.android.feature.relay

import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.UnencryptedEnvelopes
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayIdentity
import app.handlive.android.core.transport.relay.RelayLinkEvent
import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.core.transport.server.TextMessageSocket
import app.handlive.android.feature.relay.testing.FakeOwner
import app.handlive.android.feature.relay.testing.FakeRelayHttp
import app.handlive.android.feature.relay.testing.FakeRelayLinks
import app.handlive.android.feature.relay.testing.PHONE_DEVICE_ID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** When the phone is on `/v1/relay` and what it does with what arrives there (CONN-03, PAIR-01 step 6, PAIR-03). */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayConnectorTest {
    private val links = FakeRelayLinks()
    private val owner = FakeOwner()

    @Test
    fun aDemandConnectsWithTheDeviceTokenAndFiveIdleMinutesEndIt() =
        runTest {
            val connector = connector()
            connector.demand()
            runCurrent()
            val link = links.opened.single()
            assertEquals("jwt-1", link.bearer)
            assertEquals(RelayLinkState.CONNECTED, connector.state.value)
            assertEquals(1, owner.connected)

            advanceTimeBy(4 * MINUTE)
            assertNull(link.closedWith)
            advanceTimeBy(MINUTE + IDLE_CHECK)
            assertEquals(1000 to "idle", link.closedWith)
            advanceTimeBy(10 * MINUTE)
            assertEquals(RelayLinkState.OFF, connector.state.value)
            assertEquals(1, links.opened.size)
        }

    @Test
    fun aDroppedLinkIsReopenedAfterTheFirstBackoffStep() =
        runTest {
            val connector = connector()
            connector.demand()
            runCurrent()
            links.opened.single().end()
            runCurrent()
            assertEquals(RelayLinkState.BACKOFF, connector.state.value)

            // 0.5 s ± 20 %; the token is still fresh, so it is reused.
            advanceTimeBy(601)
            assertEquals(2, links.opened.size)
            assertEquals("jwt-1", links.opened.last().bearer)
            assertEquals(RelayLinkState.CONNECTED, connector.state.value)
        }

    @Test
    fun failedUpgradesBackOffUpToThirtySeconds() {
        val waits = (0..8).map { RelayBackoff.delayMillis(it, Random(1)) }
        val bases = listOf(500L, 1_000, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000)
        waits.zip(bases).forEach { (wait, base) ->
            assertTrue("$wait for $base", wait in (base * 0.8).toLong()..(base * 1.2).toLong())
        }
    }

    @Test
    fun anExpiredTokenIsRenewedOnceAndAnUnknownDeviceRegisteredOnce() =
        runTest {
            val http = FakeRelayHttp { currentTime }
            val connector = connector(http)
            links.refuseNext(RelayLinkEvent.Closed(null, 401, RelayErrorCode.TOKEN_EXPIRED))
            connector.demand()
            runCurrent()
            assertEquals(listOf("jwt-1", "jwt-2"), links.opened.map { it.bearer })
            assertEquals(RelayLinkState.CONNECTED, connector.state.value)

            links.opened.last().end()
            links.refuseNext(RelayLinkEvent.Closed(null, 404, RelayErrorCode.DEVICE_NOT_FOUND))
            advanceTimeBy(601)
            // Registered once per process at the first token, then again for the unknown device (E2).
            assertEquals(2, http.calls("POST", "/v1/devices").size)
            assertEquals(listOf("jwt-1", "jwt-2", "jwt-2", "jwt-3"), links.opened.map { it.bearer })
            assertEquals(RelayLinkState.CONNECTED, connector.state.value)
        }

    @Test
    fun aRevokedDeviceOrAnUnpinnedCertificateStopsForGood() =
        runTest {
            val connector = connector()
            links.refuseNext(RelayLinkEvent.Closed(null, 410, RelayErrorCode.DEVICE_REVOKED))
            connector.demand()
            advanceTimeBy(MINUTE)
            assertEquals(1, owner.deviceRevoked)
            assertEquals(1, links.opened.size)
            assertEquals(RelayLinkState.OFF, connector.state.value)

            links.refuseNext(RelayLinkEvent.Closed(null, pinMismatch = true))
            connector.demand()
            advanceTimeBy(MINUTE)
            assertEquals(2, links.opened.size)
            assertEquals(RelayLinkState.OFF, connector.state.value)
        }

    @Test
    fun aRelayedSessionKeepsThePhoneConnectedUntilThePeerLeaves() =
        runTest {
            val connector = connector()
            connector.demand()
            runCurrent()
            val link = links.opened.single()
            link.receive("""{"from":"$PEER","env":${EnvelopeCodec.encode(hello())}}""")
            runCurrent()
            assertEquals(listOf(PEER), owner.served)

            advanceTimeBy(10 * MINUTE)
            assertNull(link.closedWith)

            link.receive("""{"op":"presence","pair_id":"$PAIR","peer_device_id":"$PEER","online":false}""")
            advanceTimeBy(5 * MINUTE + IDLE_CHECK)
            assertEquals(1000 to "idle", link.closedWith)
        }

    @Test
    fun controlOpsReachTheOwner() =
        runTest {
            val connector = connector()
            connector.demand()
            runCurrent()
            val link = links.opened.single()
            link.receive("""{"op":"pair_revoked","pair_id":"$PAIR","by":"$PEER"}""")
            link.receive("""{"op":"error","code":"NOT_PAIRED","message":"not paired","to":"$PEER"}""")
            link.receive("""{"op":"future_op","x":1}""")
            link.receive("not json")
            runCurrent()

            assertEquals(listOf(PAIR to PEER), owner.revokedPairs)
            assertEquals(1, owner.notPaired)
            assertEquals(RelayLinkState.CONNECTED, connector.state.value)
        }

    @Test
    fun aRendezvousIsJoinedOnEveryConnectionAndCarriesThePairingExchange() =
        runTest {
            val connector = connector()
            val endpoint = RecordingEndpoint()
            connector.joinRendezvous(RV, endpoint)
            runCurrent()
            val first = links.opened.single()
            assertEquals(listOf("""{"op":"rv_join","rv_id":"$RV"}"""), first.sent)

            first.receive("""{"op":"rv_msg","rv_id":"$RV","env":${EnvelopeCodec.encode(hello(MessageType.PAIR))}}""")
            runCurrent()
            assertEquals(1, endpoint.sockets.size)

            // A new connection joins again; leaving ends the exchange and lets the phone go idle.
            first.end()
            advanceTimeBy(601)
            assertEquals(listOf("""{"op":"rv_join","rv_id":"$RV"}"""), links.opened.last().sent)
            connector.leaveRendezvous(RV)
            advanceTimeBy(5 * MINUTE + IDLE_CHECK)
            assertEquals(1000 to "idle", links.opened.last().closedWith)
        }

    @Test
    fun theRelaySwitchedOffClosesTheLinkAtOnce() =
        runTest {
            val connector = connector()
            connector.demand()
            runCurrent()
            owner.allowed = false
            connector.stop()
            runCurrent()
            assertEquals(1000 to "off", links.opened.single().closedWith)
            assertEquals(RelayLinkState.OFF, connector.state.value)

            // No demand connects while the relay is not allowed.
            connector.demand()
            advanceTimeBy(MINUTE)
            assertEquals(1, links.opened.size)
            assertFalse(
                links.opened
                    .single()
                    .sent
                    .isNotEmpty(),
            )
        }

    private fun TestScope.connector(http: FakeRelayHttp = FakeRelayHttp { currentTime }): RelayConnector {
        val auth =
            RelayAuth(http, RelayIdentity(PHONE_DEVICE_ID, ByteArray(32), "0.0.1 (1)") { ByteArray(64) }) {
                currentTime
            }
        return RelayConnector(backgroundScope, { currentTime }, auth, links, owner, Random(7))
    }

    private fun hello(type: MessageType = MessageType.SESSION) =
        UnencryptedEnvelopes.build(
            EnvelopeHeader(type.wire, "0192f3e4-7a10-7b20-8c30-9d40ae50bf60", 1_727_150_060_500),
            "hello",
            JsonObject.serializer(),
            JsonObject(emptyMap()),
        )

    /** Records the rendezvous sockets the pairing exchange would read. */
    private class RecordingEndpoint : PairingEndpoint {
        val sockets = mutableListOf<TextMessageSocket>()

        override suspend fun handle(socket: TextMessageSocket) {
            sockets += socket
        }
    }

    private companion object {
        const val MINUTE = 60_000L
        const val IDLE_CHECK = 30_000L
        const val PEER = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037"
        const val PAIR = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f2a3b4c5d"
        const val RV = "q2x7cyNh1u0pA9oVDdk1Rw"
    }
}
