package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.bluetooth.testing.InMemoryBluetoothLinkIo
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * `BluetoothPeerSocket` theo mẫu `RelayPeerSocket` (A7.1b), qua một đôi [InMemoryBluetoothLinkIo] — không đụng
 * radio, như #191 "fake pipe". Phủ lại #191: mã hoá/giải mã (`Split()` c1/c2), cắt mảnh/ghép lại làn dài.
 */
class BluetoothPeerSocketTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = UuidV7Generator()

    private fun link(): Pair<BluetoothPeerSocket, BluetoothPeerSocket> {
        val c1 = SecureRandomBytes.next(KEY_SIZE)
        val c2 = SecureRandomBytes.next(KEY_SIZE)
        val (ioA, ioB) = InMemoryBluetoothLinkIo.pair()
        // A đóng vai C (mã bằng c1, giải bằng c2), B đóng vai S (mã bằng c2, giải bằng c1) — đúng chiều Split().
        val a = BluetoothPeerSocket(ioA, BtTransportCipher(c1), BtTransportCipher(c2), scope)
        val b = BluetoothPeerSocket(ioB, BtTransportCipher(c2), BtTransportCipher(c1), scope)
        return a to b
    }

    @After
    fun tearDown() = scope.cancel()

    private fun envelope(
        type: String,
        payloadBytes: Int,
    ) = EnvelopeCodec.encode(
        Envelope(
            v = Envelope.VERSION,
            type = type,
            id = ids.next(),
            ts = System.currentTimeMillis(),
            payload = Base64Codecs.encodeB64(ByteArray(payloadBytes)),
        ),
    )

    @Test
    fun aShortEnvelopeRoundTripsBothWays() =
        runBlocking {
            val (a, b) = link()
            val sent = envelope("ping", payloadBytes = 8)
            a.outgoing.send(Frame.Text(sent))
            val received = withTimeout(WAIT) { b.incoming.receive() as Frame.Text }
            assertEquals(sent, received.readText())

            val reply = envelope("ack", payloadBytes = 8)
            b.outgoing.send(Frame.Text(reply))
            val backAtA = withTimeout(WAIT) { a.incoming.receive() as Frame.Text }
            assertEquals(reply, backAtA.readText())
        }

    @Test
    fun anEnvelopeOverFragmentMaxReassemblesWholeOnTheOtherSide() =
        runBlocking {
            val (a, b) = link()
            val sent = envelope("clipboard", payloadBytes = BtLinkConstants.FRAGMENT_MAX + 5_000)
            assertTrue(sent.toByteArray(Charsets.UTF_8).size > BtLinkConstants.FRAGMENT_MAX)
            a.outgoing.send(Frame.Text(sent))
            val received = withTimeout(WAIT) { b.incoming.receive() as Frame.Text }
            assertEquals(sent, received.readText())
        }

    @Test
    fun sessionTypeEnvelopesStillArriveWhole() =
        runBlocking {
            val (a, b) = link()
            val sent = envelope("session", payloadBytes = 16)
            a.outgoing.send(Frame.Text(sent))
            val received = withTimeout(WAIT) { b.incoming.receive() as Frame.Text }
            assertEquals(sent, received.readText())
        }

    @Test
    fun closingOneEndClosesTheOtherSidesIncoming() =
        runBlocking {
            val (a, b) = link()
            a.end()
            withTimeout(WAIT) {
                var closed = false
                try {
                    b.incoming.receive()
                } catch (_: Exception) {
                    closed = true
                }
                assertTrue(closed || b.incoming.isClosedForReceive)
            }
        }

    @Test
    fun theFirstRecordThatOpensIsReportedOnce() =
        runBlocking {
            val c1 = SecureRandomBytes.next(KEY_SIZE)
            val c2 = SecureRandomBytes.next(KEY_SIZE)
            val (ioA, ioB) = InMemoryBluetoothLinkIo.pair()
            var opened = 0
            val a = BluetoothPeerSocket(ioA, BtTransportCipher(c1), BtTransportCipher(c2), scope)
            val b =
                BluetoothPeerSocket(
                    ioB,
                    BtTransportCipher(c2),
                    BtTransportCipher(c1),
                    scope,
                    callbacks = BluetoothPeerSocketCallbacks(onFirstRecordOpened = { opened++ }),
                )
            repeat(2) {
                a.outgoing.send(Frame.Text(envelope("ping", payloadBytes = 8)))
                withTimeout(WAIT) { b.incoming.receive() }
            }
            assertEquals(1, opened)
        }

    @Test
    fun aFirstRecordUnderTheWrongKeyIsReportedAsAKeyConfirmationFailure() =
        runBlocking {
            val (ioA, ioB) = InMemoryBluetoothLinkIo.pair()
            val rejected = kotlinx.coroutines.CompletableDeferred<Unit>()
            val a = BluetoothPeerSocket(ioA, randomCipher(), randomCipher(), scope)
            BluetoothPeerSocket(
                ioB,
                randomCipher(),
                randomCipher(),
                scope,
                callbacks = BluetoothPeerSocketCallbacks(onFirstRecordRejected = { rejected.complete(Unit) }),
            )
            a.outgoing.send(Frame.Text(envelope("ping", payloadBytes = 8)))
            withTimeout(WAIT) { rejected.await() }
        }

    private fun randomCipher() = BtTransportCipher(SecureRandomBytes.next(KEY_SIZE))

    private companion object {
        const val KEY_SIZE = 32
        val WAIT = 5.seconds
    }
}
