package app.handlive.android.core.transport.bluetooth.testing

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtRecordCodec
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import kotlinx.coroutines.channels.Channel

/** Một đôi ống trong bộ nhớ nối hai đầu một liên kết Bluetooth giả — không đụng radio (A7.1a/A7.1b "fake pipe"). */
class InMemoryBluetoothLinkIo private constructor(
    private val readQueue: Channel<ByteArray>,
    private val writeQueue: Channel<ByteArray>,
) : BluetoothLinkIo {
    override suspend fun readPreauthRecord(): ByteArray =
        readRecord().also { BtRecordCodec.requireWithinBound(it.size, BtLinkConstants.PREAUTH_RECORD_MAX) }

    override suspend fun readRecord(): ByteArray =
        readQueue.receiveCatching().getOrNull() ?: throw BtLinkException("pipe closed")

    override suspend fun writeRecord(body: ByteArray) {
        writeQueue.send(body)
    }

    override fun close() {
        readQueue.close()
        writeQueue.close()
    }

    companion object {
        /** Đầu A đọc từ cái B viết và ngược lại. */
        fun pair(): Pair<InMemoryBluetoothLinkIo, InMemoryBluetoothLinkIo> {
            val aToB = Channel<ByteArray>(Channel.UNLIMITED)
            val bToA = Channel<ByteArray>(Channel.UNLIMITED)
            val endA = InMemoryBluetoothLinkIo(readQueue = bToA, writeQueue = aToB)
            val endB = InMemoryBluetoothLinkIo(readQueue = aToB, writeQueue = bToA)
            return endA to endB
        }
    }
}
