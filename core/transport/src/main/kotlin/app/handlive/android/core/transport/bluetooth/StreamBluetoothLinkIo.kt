package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtRecordCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [BluetoothLinkIo] trên luồng byte của một `BluetoothSocket` L2CAP CoC (A-SVC). Đọc/viết chặn chạy trên luồng IO;
 * huỷ coroutine đang chờ thì đóng socket ([onClose]) — cách duy nhất làm `read()` của socket nhả ra.
 */
class StreamBluetoothLinkIo(
    private val input: InputStream,
    private val output: OutputStream,
    private val onClose: () -> Unit,
) : BluetoothLinkIo {
    private val writeLock = Any()

    override suspend fun readPreauthRecord(): ByteArray =
        cancellableBlocking(::close) {
            val rlen = readRlen()
            BtRecordCodec.requireWithinBound(rlen, BtLinkConstants.PREAUTH_RECORD_MAX)
            readExactly(rlen)
        }

    override suspend fun readRecord(): ByteArray =
        cancellableBlocking(::close) {
            val rlen = readRlen()
            BtRecordCodec.requireValidTransportRlen(rlen)
            readExactly(rlen)
        }

    /** Một lần `write` cho cả `rlen` và thân, dưới khoá: hai coroutine viết cùng lúc không xen byte vào nhau. */
    override suspend fun writeRecord(body: ByteArray) {
        val record = BtRecordCodec.encode(body)
        cancellableBlocking(::close) {
            synchronized(writeLock) {
                output.write(record)
                output.flush()
            }
        }
    }

    override fun close() {
        runCatching(onClose)
    }

    private fun readRlen(): Int = BtRecordCodec.decodeRlen(readExactly(BtRecordCodec.LENGTH_PREFIX_SIZE))

    private fun readExactly(size: Int): ByteArray {
        val out = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(out, read, size - read)
            if (n < 0) throw BtLinkException("stream ended inside a record")
            read += n
        }
        return out
    }
}

/**
 * Chạy [block] chặn trên luồng IO; huỷ coroutine thì gọi [onCancel] ngay (đóng socket để nhả `read`/`accept`) thay vì
 * chờ [block] tự trả. [IOException] của socket thành [BtLinkException]: kênh đứt, đóng như mọi lỗi liên kết.
 */
suspend fun <T> cancellableBlocking(
    onCancel: () -> Unit,
    block: () -> T,
): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { onCancel() }
        Dispatchers.IO.asExecutor().execute {
            val result =
                runCatching(block).recoverCatching { e ->
                    throw if (e is IOException) BtLinkException("link i/o failed: ${e.javaClass.simpleName}") else e
                }
            if (continuation.isActive) {
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        }
    }
