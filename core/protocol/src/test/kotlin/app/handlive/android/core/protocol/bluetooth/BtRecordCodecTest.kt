package app.handlive.android.core.protocol.bluetooth

import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** bt-frame.json: `rlen` (uint16 BE) ‖ thân — cận kiểm trước khi cấp phát (0.4.5 "Link records", review #165). */
class BtRecordCodecTest {
    @Test
    fun encodeAndDecodeRlenRoundTrip() {
        for (v in SharedTestVectors.vectors("bt-frame.json").filter { it.str("kind") == "preauth_frame" }) {
            val record = v.hex("record")
            val body = v.hex("body")
            assertArrayEquals(v.str("name"), record, BtRecordCodec.encode(body))
            assertEquals(v.str("name"), body.size, BtRecordCodec.decodeRlen(record.copyOfRange(0, 2)))
        }
    }

    @Test
    fun rlenOutOfBoundsClosesBeforeAllocating() {
        val v = SharedTestVectors.invalidVectors("bt-frame.json").single { it.str("reason") == "rlen_out_of_bounds" }
        val rlen = BtRecordCodec.decodeRlen(v.hex("record").copyOfRange(0, 2))
        assertEquals(v.int("rlen"), rlen)
        assertThrows(BtLinkException::class.java) { BtRecordCodec.requireValidTransportRlen(rlen) }
    }

    @Test
    fun preauthRecordOverBoundClosesBeforeAllocating() {
        val v =
            SharedTestVectors
                .invalidVectors("bt-frame.json")
                .single { it.str("reason") == "preauth_record_over_bound" }
        val rlen = BtRecordCodec.decodeRlen(v.hex("record").copyOfRange(0, 2))
        assertEquals(v.int("rlen"), rlen)
        assertEquals(v.int("bt_preauth_record_max"), BtLinkConstants.PREAUTH_RECORD_MAX)
        assertThrows(BtLinkException::class.java) {
            BtRecordCodec.requireWithinBound(rlen, BtLinkConstants.PREAUTH_RECORD_MAX)
        }
    }

    @Test
    fun validTransportRlensNeverThrow() {
        for (rlen in BtLinkConstants.TRANSPORT_RECORD_SIZES) {
            BtRecordCodec.requireValidTransportRlen(rlen)
        }
    }
}
