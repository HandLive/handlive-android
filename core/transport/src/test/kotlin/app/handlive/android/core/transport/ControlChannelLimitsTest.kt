package app.handlive.android.core.transport

import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.Payload
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.session.SessionOp
import app.handlive.android.core.protocol.session.SessionRekeyData
import app.handlive.android.core.transport.server.ControlConnectionState
import app.handlive.android.core.transport.server.ControlServerLimits
import app.handlive.android.core.transport.testing.LoopbackServerFixture
import app.handlive.android.core.transport.testing.connect
import app.handlive.android.core.transport.testing.pinnedWebSocketClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.channels.toList
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Close codes 4410, 4411 and 4429 on a real Netty/TLS 1.3 server (0.8.3, CONN-01 API 3–4, CONN-02). */
class ControlChannelLimitsTest {
    private val fixture =
        LoopbackServerFixture(
            rekeyAfterEnvelopes = REKEY_AFTER,
            limits = ControlServerLimits(idleTimeout = IDLE_TIMEOUT),
        )
    private val client = pinnedWebSocketClient(fixture.tls.certificateSha256())
    private val event = PlaintextCodec.encodePayload(Payload("push", JsonObject(emptyMap())))

    @After
    fun tearDown() {
        client.close()
        fixture.close()
    }

    @Test
    fun seventeenthConnectionWaitingForItsHandshakeIsClosedWith4429() =
        runBlocking {
            val waiting =
                (1..TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS).map { client.webSocketSession(fixture.url) }
            // Let the server accept all sixteen before the next one arrives.
            withTimeout(WAIT_MILLIS) {
                while (fixture.server.pendingHandshakes < TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS) delay(20)
            }
            var reason: CloseReason? = null
            client.webSocket(fixture.url) { reason = closeReason.await() }
            assertEquals(WsCloseCode.RATE_LIMITED, reason?.code)
            waiting.forEach { it.close() }
        }

    @Test
    fun fiveWrongMacsFromOneAddressBlockItWith4429EvenForAValidHello() =
        runBlocking {
            val peer = fixture.addPair()
            repeat(TransportConstants.AUTH_FAILURES_BEFORE_BLOCK) {
                val (badHello) = peer.hello(macKey = SecureRandomBytes.next(PRK_SIZE))
                client.webSocket(fixture.url) {
                    send(Frame.Text(badHello))
                    assertEquals(WsCloseCode.AUTH_FAILED, closeReason.await()?.code)
                }
            }
            val (goodHello) = peer.hello()
            var reason: CloseReason? = null
            var frames = 0
            client.webSocket(fixture.url) {
                runCatching { send(Frame.Text(goodHello)) }
                frames = incoming.toList().size
                reason = closeReason.await()
            }
            assertEquals(WsCloseCode.RATE_LIMITED, reason?.code)
            assertEquals("no session/error or welcome before the close", 0, frames)
        }

    @Test
    fun sessionWithoutAnyFrameForTheIdleTimeoutIsClosedWith4411() =
        runBlocking {
            val channel = fixture.connect(client, fixture.addPair())
            val session = fixture.awaitSession()
            val started = System.nanoTime()
            val reason = withTimeout(WAIT_MILLIS) { channel.socket.closeReason.await() }
            val elapsedMillis = (System.nanoTime() - started) / NANOS_PER_MILLI
            assertEquals(WsCloseCode.IDLE_TIMEOUT, reason?.code)
            assertTrue("closed after ${elapsedMillis}ms", elapsedMillis >= IDLE_TIMEOUT.inWholeMilliseconds - SLACK)
            assertEquals(ControlConnectionState.CLOSED, session.state.value)
        }

    @Test
    fun webSocketPingsFromTheClientKeepTheSessionAlive() =
        runBlocking {
            val channel = fixture.connect(client, fixture.addPair())
            val session = fixture.awaitSession()
            repeat(PING_COUNT) {
                channel.socket.send(Frame.Ping(byteArrayOf(0, 0, 0, 0, 0, 0, 0, it.toByte())))
                delay(IDLE_TIMEOUT.inWholeMilliseconds / 3)
            }
            // Pings covered 2.5 × the idle timeout without any envelope.
            assertFalse(channel.socket.closeReason.isCompleted)
            assertEquals(ControlConnectionState.ESTABLISHED, session.state.value)
            val reason = withTimeout(WAIT_MILLIS) { channel.socket.closeReason.await() }
            assertEquals(WsCloseCode.IDLE_TIMEOUT, reason?.code)
        }

    @Test
    fun errorAckToTheServersRekeyClosesWith4410() =
        runBlocking {
            val channel = fixture.connect(client, fixture.addPair())
            fixture.awaitSession()
            repeat(REKEY_AFTER.toInt() - 1) { channel.sendPlaintext(MessageType.CLIPBOARD.wire, event) }
            val (request, plaintext) = channel.receive()
            assertEquals(SessionOp.REKEY, PlaintextCodec.decodeOp(plaintext, SessionRekeyData.serializer()).op)

            val refusal = Ack.failure(request.id, ErrorCode.BAD_REQUEST, "refused")
            channel.sendPlaintext(MessageType.ACK.wire, PlaintextCodec.encodeAck(refusal))
            val reason = withTimeout(WAIT_MILLIS) { channel.socket.closeReason.await() }
            assertEquals(WsCloseCode.REKEY_FAILED, reason?.code)
        }

    private companion object {
        const val PRK_SIZE = 32
        const val REKEY_AFTER = 3L
        const val PING_COUNT = 8
        const val WAIT_MILLIS = 10_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val SLACK = 150L
        val IDLE_TIMEOUT = 1.seconds
    }
}
