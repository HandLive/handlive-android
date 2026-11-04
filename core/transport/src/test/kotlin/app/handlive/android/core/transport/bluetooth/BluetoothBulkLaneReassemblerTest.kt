package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 0.4.7 bulk rule 5: ghép làn bulk của kênh `0x02`, đệm riêng của làn dài; đóng liên kết khi vượt `BT_MESSAGE_MAX`. */
class BluetoothBulkLaneReassemblerTest {
    @Test
    fun reassemblesTwoBulkFragmentsOfOneMessage() {
        val reassembler = BluetoothBulkLaneReassembler()
        val first = BtTransportFrame(chan = 0x02, more = true, long = false, data = byteArrayOf(1, 2, 3))
        val second = BtTransportFrame(chan = 0x02, more = false, long = false, data = byteArrayOf(4, 5))
        assertNull(reassembler.accept(first))
        val message = reassembler.accept(second)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), message)
    }

    @Test
    fun aSingleFragmentMessageCompletesImmediately() {
        val reassembler = BluetoothBulkLaneReassembler()
        val frame = BtTransportFrame(chan = 0x02, more = false, long = false, data = byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), reassembler.accept(frame))
    }

    @Test
    fun messageOverMaxClosesTheLinkEvenMidStream() {
        val reassembler = BluetoothBulkLaneReassembler(messageMax = 10)
        val first = BtTransportFrame(chan = 0x02, more = true, long = false, data = ByteArray(8))
        assertNull(reassembler.accept(first))
        val second = BtTransportFrame(chan = 0x02, more = false, long = false, data = ByteArray(3))
        assertThrows(BtLinkException::class.java) { reassembler.accept(second) }
    }

    @Test
    fun aSecondMessageStartsWithAFreshBuffer() {
        val reassembler = BluetoothBulkLaneReassembler()
        reassembler.accept(BtTransportFrame(chan = 0x02, more = false, long = false, data = byteArrayOf(1)))
        val frame = BtTransportFrame(chan = 0x02, more = false, long = false, data = byteArrayOf(2))
        assertArrayEquals(byteArrayOf(2), reassembler.accept(frame))
    }
}
