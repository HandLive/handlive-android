package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtTransportFrame
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** 0.4.5: ghép làn dài, một tin một lúc mỗi hướng; đóng liên kết khi tổng byte vượt `BT_MESSAGE_MAX`. */
class BluetoothLongLaneReassemblerTest {
    @Test
    fun reassemblesTheTwoLongLaneFragmentsOfBtFrameJson() {
        val fragment1 = SharedTestVectors.vectors("bt-frame.json").first { it.str("name").contains("mảnh 1/2") }
        val fragment2 = SharedTestVectors.vectors("bt-frame.json").first { it.str("name").contains("mảnh 2/2") }
        val reassembler = BluetoothLongLaneReassembler()
        assertNull(reassembler.accept(BtTransportFrame.decode(fragment1.hex("plaintext"))))
        val message = reassembler.accept(BtTransportFrame.decode(fragment2.hex("plaintext")))
        assertArrayEquals(fragment1.hex("data") + fragment2.hex("data"), message)
    }

    @Test
    fun messageOverMaxClosesTheLinkEvenMidStream() {
        val v = SharedTestVectors.invalidVectors("bt-frame.json").single { it.str("reason") == "message_over_max" }
        val reassembler = BluetoothLongLaneReassembler()
        val priorData = ByteArray(v.int("prior_bytes_received"))
        val priorFrame = BtTransportFrame(chan = 0x01, more = true, long = true, data = priorData)
        assertNull(reassembler.accept(priorFrame))
        val lastData = ByteArray(v.int("last_fragment_dlen"))
        val lastFrame = BtTransportFrame(chan = 0x01, more = false, long = true, data = lastData)
        assertThrows(BtLinkException::class.java) { reassembler.accept(lastFrame) }
    }
}
