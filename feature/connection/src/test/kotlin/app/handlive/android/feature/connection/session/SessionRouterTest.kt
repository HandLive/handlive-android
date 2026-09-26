package app.handlive.android.feature.connection.session

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.Payload
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.server.InboundEnvelope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Envelope routing rules of 0.5.1: ack correlation, de-duplication, unknown types, `ping/ping`. */
class SessionRouterTest {
    private class Sent(
        val type: MessageType,
        val plaintext: ByteArray,
        val id: String,
    )

    private val sent = mutableListOf<Sent>()
    private var now = 1_727_150_000_000L
    private val ids = UuidV7Generator()
    private val session =
        PeerSession(
            peer =
                PeerSession.PeerInfo(
                    pairId = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
                    peerDeviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
                    peerName = "MacBook",
                    peerPlatform = PeerPlatform.MACOS,
                ),
            channel = PeerSession.Channel.LAN,
            effectiveFeatures = MutableStateFlow(emptySet()),
            peerCapability = MutableStateFlow(null),
            sender = { type, plaintext, id -> sent += Sent(type, plaintext, id) },
            clock = { now },
        )
    private val router = SessionRouter(clock = { now })

    @Test
    fun ackCompletesTheMatchingRequest() =
        runTest {
            val reply = async { session.request(MessageType.CLIPBOARD, op("push")) }
            testScheduler.runCurrent()
            val requestId = sent.single().id
            router.route(session, inbound(MessageType.ACK, PlaintextCodec.encodeAck(Ack.success(requestId))))
            assertTrue(reply.await().ok)
        }

    @Test
    fun requestWithoutAckTimesOut() =
        runTest {
            assertThrows(AckTimeoutException::class.java) {
                kotlinx.coroutines.runBlocking {
                    session.request(
                        MessageType.CLIPBOARD,
                        op("push"),
                        timeout = 0.1.seconds,
                    )
                }
            }
        }

    @Test
    fun theAckWaitStartsAfterTheWorkThatFollowsTheSend() =
        runTest {
            // CLIP-03 API 3 rule 3: chunks go out after the push; the 10 s start after the last one.
            val reply =
                async {
                    session.request(MessageType.CLIPBOARD, op("push"), timeout = 10.seconds) { delay(15.seconds) }
                }
            testScheduler.advanceTimeBy(20.seconds)
            testScheduler.runCurrent()
            assertTrue(reply.isActive)
            router.route(session, inbound(MessageType.ACK, PlaintextCodec.encodeAck(Ack.success(sent.single().id))))
            assertTrue(reply.await().ok)
        }

    @Test
    fun repeatedRequestGetsItsOldAckAndIsNotProcessedAgain() =
        runTest {
            var handled = 0
            router.register(MessageType.CLIPBOARD) { s, envelope ->
                handled++
                s.sendAck(Ack.success(envelope.id))
            }
            val request = inbound(MessageType.CLIPBOARD, op("push"))
            router.route(session, request)
            router.route(session, request)
            assertEquals(1, handled)
            val acks = sent.map { PlaintextCodec.decodeAck(it.plaintext) }
            assertEquals(2, acks.size)
            assertEquals(acks[0], acks[1])
            assertEquals(request.id, acks[0].re)
        }

    @Test
    fun unknownRequestIsRefusedWithUnsupportedTypeAndUnknownEventIsIgnored() =
        runTest {
            val request = inbound(MessageType.SMS, op("send"))
            router.route(session, request)
            val ack = PlaintextCodec.decodeAck(sent.single().plaintext)
            assertEquals(request.id, ack.re)
            assertEquals(ErrorCode.UNSUPPORTED_TYPE.name, ack.error?.code)

            router.route(session, inbound(MessageType.SMS, op("new")))
            assertEquals(1, sent.size)
        }

    @Test
    fun pingIsAnsweredWithItsSequenceAndTheServerTime() =
        runTest {
            val ping = inbound(MessageType.PING, op("ping", buildJsonObject { put("seq", 42) }))
            router.route(session, ping)
            val ack = PlaintextCodec.decodeAck(sent.single().plaintext)
            assertEquals(ping.id, ack.re)
            assertEquals(JsonPrimitive(42), ack.data?.get("seq"))
            assertEquals(JsonPrimitive(now), ack.data?.get("server_ts"))
        }

    @Test
    fun processedIdsExpireAfterFiveMinutes() {
        val cache = ProcessedEnvelopeCache(clock = { now }, capacity = 2)
        cache.remember("a")
        now += ProcessedEnvelopeCache.WINDOW_MILLIS + 1
        assertEquals(null, cache.find("a"))
        cache.remember("b")
        cache.remember("c")
        cache.remember("d")
        assertEquals(null, cache.find("b"))
        assertTrue(cache.find("d") != null)
    }

    private fun op(
        name: String,
        data: JsonObject = JsonObject(emptyMap()),
    ) = PlaintextCodec.encodePayload(Payload(name, data))

    private fun inbound(
        type: MessageType,
        plaintext: ByteArray,
    ) = InboundEnvelope(type.wire, ids.next(), now, plaintext)
}
