package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.transport.bluetooth.testing.InMemoryBluetoothLinkIo
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Envelope điều khiển ngắn gửi giữa lúc bulk đang chạy phải tới trước phần bulk còn lại (0.4.5 Priority). */
class BluetoothPeerSocketPriorityTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val ids = UuidV7Generator()

    @After
    fun tearDown() = scope.cancel()

    private fun envelope(
        type: String,
        bytes: Int,
    ) = EnvelopeCodec.encode(
        Envelope(Envelope.VERSION, type, ids.next(), System.currentTimeMillis(), Base64Codecs.encodeB64(ByteArray(bytes))),
    )

    @Test
    fun shortControlSentMidBulkArrivesBeforeTheBulkEnds() =
        runBlocking {
            val c1 = SecureRandomBytes.next(32)
            val c2 = SecureRandomBytes.next(32)
            val (rawA, ioB) = InMemoryBluetoothLinkIo.pair()
            val ioA = HoldSecondRecordLinkIo(rawA)
            val a = BluetoothPeerSocket(ioA, BtTransportCipher(c1), BtTransportCipher(c2), scope)
            val b = BluetoothPeerSocket(ioB, BtTransportCipher(c2), BtTransportCipher(c1), scope)
            val bulk = envelope("clipboard", 180_000)
            val ping = envelope("ping", 8)

            launch { a.sendBulk(bulk) }
            // Mảnh bulk đầu đã lên dây, mảnh thứ hai đang bị giữ: bulk chắc chắn đang giữa chừng.
            withTimeout(WAIT) { ioA.firstRecordWritten.await() }
            a.outgoing.send(Frame.Text(ping))
            ioA.release.complete(Unit)

            val first = withTimeout(WAIT) { (b.incoming.receive() as Frame.Text).readText() }
            val second = withTimeout(WAIT) { (b.incoming.receive() as Frame.Text).readText() }
            assertEquals("control phải tới trước bulk", ping, first)
            assertEquals("bulk vẫn tới trọn vẹn sau control", bulk, second)
        }

    /** Ống giả giữ bản ghi thứ hai tới khi test mở [release], để control được gửi đúng lúc bulk còn dở. */
    private class HoldSecondRecordLinkIo(
        private val inner: BluetoothLinkIo,
    ) : BluetoothLinkIo by inner {
        val firstRecordWritten = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        private var writes = 0

        override suspend fun writeRecord(body: ByteArray) {
            writes++
            if (writes == 2) release.await()
            inner.writeRecord(body)
            if (writes == 1) firstRecordWritten.complete(Unit)
        }
    }

    private companion object {
        val WAIT = 10.seconds
    }
}
