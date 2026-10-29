package app.handlive.android.core.protocol.bluetooth

import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.bool
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** bt-frame.json kind "transport_frame": `chan` ‖ `flags`(`more`/`long`) ‖ `dlen` ‖ `data` ‖ đệm (0.4.5). */
class BtTransportFrameTest {
    private val vectors = SharedTestVectors.vectors("bt-frame.json").filter { it.str("kind") == "transport_frame" }

    @Test
    fun encodeMatchesVectorPlaintext() {
        for (v in vectors) {
            val chan = Integer.decode(v.str("chan"))
            val plaintext = BtTransportFrame.encode(chan, v.bool("more"), v.bool("long"), v.hex("data"))
            assertArrayEquals(v.str("name"), v.hex("plaintext"), plaintext)
            assertEquals(v.str("name"), v.int("padded_size"), plaintext.size)
        }
        assertEquals(4, vectors.size)
    }

    @Test
    fun decodeMatchesVectorFields() {
        for (v in vectors) {
            val frame = BtTransportFrame.decode(v.hex("plaintext"))
            val name = v.str("name")
            assertEquals(name, Integer.decode(v.str("chan")), frame.chan)
            assertEquals(name, v.bool("more"), frame.more)
            assertEquals(name, v.bool("long"), frame.long)
            assertArrayEquals(name, v.hex("data"), frame.data)
        }
    }

    @Test
    fun unknownChanClosesTheLink() {
        val v = SharedTestVectors.invalidVectors("bt-frame.json").single { it.str("reason") == "unknown_chan" }
        assertThrows(BtLinkException::class.java) { BtTransportFrame.decode(v.hex("plaintext")) }
    }

    @Test
    fun moreWithoutLongClosesTheLink() {
        val v = SharedTestVectors.invalidVectors("bt-frame.json").single { it.str("reason") == "more_without_long" }
        assertThrows(BtLinkException::class.java) { BtTransportFrame.decode(v.hex("plaintext")) }
    }

    @Test
    fun bulkFragmentOverMaxClosesTheLink() {
        val v =
            SharedTestVectors.invalidVectors("bt-frame.json").single { it.str("reason") == "bulk_fragment_over_max" }
        val dlen = v.int("dlen")
        assertEquals(v.int("bt_bulk_fragment_max"), BtLinkConstants.BULK_FRAGMENT_MAX)
        assertTrue(dlen > BtLinkConstants.BULK_FRAGMENT_MAX)
        // Vector chỉ tả cận, không cho byte cụ thể — dựng thủ công một plaintext có dlen vượt cận để kiểm decode.
        val padded = ByteArray(4096)
        padded[0] = Integer.decode(v.str("chan")).toByte()
        padded[1] = 0x02 // long = 1
        padded[2] = (dlen shr 8).toByte()
        padded[3] = dlen.toByte()
        assertThrows(BtLinkException::class.java) { BtTransportFrame.decode(padded) }
    }

    @Test
    fun maxDlenMatchesEachLane() {
        val control = BtLinkConstants.CHAN_CONTROL
        val bulk = BtLinkConstants.CHAN_BULK
        assertEquals(BtLinkConstants.FRAGMENT_MAX, BtTransportFrame.maxDlenFor(control, long = false))
        assertEquals(BtLinkConstants.BULK_FRAGMENT_MAX, BtTransportFrame.maxDlenFor(control, long = true))
        assertEquals(BtLinkConstants.BULK_FRAGMENT_MAX, BtTransportFrame.maxDlenFor(bulk, long = false))
    }

    @Test
    fun shortLaneRejectsOversizedData() {
        val oversized = ByteArray(BtLinkConstants.FRAGMENT_MAX + 1)
        assertThrows(IllegalArgumentException::class.java) {
            BtTransportFrame.encode(BtLinkConstants.CHAN_CONTROL, more = false, long = false, data = oversized)
        }
    }

    @Test
    fun paddedSizeFitsTheSmallestTier() {
        assertEquals(256, BtTransportFrame.paddedSizeFor(4))
        assertEquals(1024, BtTransportFrame.paddedSizeFor(1024))
        assertEquals(4096, BtTransportFrame.paddedSizeFor(1025))
        assertFalse(BtTransportFrame.paddedSizeFor(256) > 256)
    }
}
