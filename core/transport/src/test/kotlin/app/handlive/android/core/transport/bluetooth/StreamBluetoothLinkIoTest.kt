package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkConstants
import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtRecordCodec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * `rlen` ‖ thân trên luồng byte của socket L2CAP (0.4.5 "Link records"): `rlen` sai cận bị từ chối TRƯỚC khi
 * đọc/cấp phát thân (review #165) — luồng thử chỉ chứa 2 byte tiền tố, nên đọc thêm sẽ hết dữ liệu chứ không ra
 * [BtLinkException] về cận.
 */
class StreamBluetoothLinkIoTest {
    private fun ioReading(bytes: ByteArray) =
        StreamBluetoothLinkIo(ByteArrayInputStream(bytes), ByteArrayOutputStream()) {}

    @Test
    fun aPreauthRecordWithinTheBoundIsRead() =
        runBlocking {
            val body = ByteArray(33) { it.toByte() }
            assertArrayEquals(body, ioReading(BtRecordCodec.encode(body)).readPreauthRecord())
        }

    @Test
    fun aPreauthRlenOverTheBoundFailsBeforeReadingTheBody() {
        val prefixOnly = byteArrayOf(0, (BtLinkConstants.PREAUTH_RECORD_MAX + 1).toByte())
        val e = assertThrows(BtLinkException::class.java) { runBlocking { ioReading(prefixOnly).readPreauthRecord() } }
        assertTrue(e.message.orEmpty().contains("exceeds bound"))
    }

    @Test
    fun aTransportRecordMustHaveOneOfTheThreeSizes() {
        assertArrayEquals(ByteArray(272), runBlocking { ioReading(BtRecordCodec.encode(ByteArray(272))).readRecord() })
        val prefixOnly = byteArrayOf(0x01, 0x2C) // 300
        val e = assertThrows(BtLinkException::class.java) { runBlocking { ioReading(prefixOnly).readRecord() } }
        assertTrue(e.message.orEmpty().contains("not a valid transport record size"))
    }

    @Test
    fun aStreamEndingInsideARecordIsALinkFailure() {
        val truncated = BtRecordCodec.encode(ByteArray(33)).copyOf(10)
        assertThrows(BtLinkException::class.java) { runBlocking { ioReading(truncated).readPreauthRecord() } }
        assertThrows(BtLinkException::class.java) { runBlocking { ioReading(ByteArray(0)).readRecord() } }
    }

    @Test
    fun writingPrefixesTheBodyWithItsRlen() =
        runBlocking {
            val out = ByteArrayOutputStream()
            val io = StreamBluetoothLinkIo(ByteArrayInputStream(ByteArray(0)), out) {}
            io.writeRecord(byteArrayOf(0x7F))
            assertArrayEquals(byteArrayOf(0, 1, 0x7F), out.toByteArray())
        }

    @Test
    fun closeRunsTheSocketClose() {
        var closed = false
        StreamBluetoothLinkIo(ByteArrayInputStream(ByteArray(0)), ByteArrayOutputStream()) { closed = true }.close()
        assertTrue(closed)
    }
}
