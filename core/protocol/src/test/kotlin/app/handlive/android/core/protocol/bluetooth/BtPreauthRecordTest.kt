package app.handlive.android.core.protocol.bluetooth

import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** bt-frame.json kind "preauth_frame": thân pre-auth = kind(1) ‖ payload (0.4.5 "Link records" bảng). */
class BtPreauthRecordTest {
    private val vectors = SharedTestVectors.vectors("bt-frame.json").filter { it.str("kind") == "preauth_frame" }

    @Test
    fun encodeAndDecodeMatchVectors() {
        for (v in vectors) {
            val name = v.str("name")
            val kind = Integer.decode(v.str("preauth_kind"))
            val body = v.hex("body")
            val payload = body.copyOfRange(1, body.size)
            assertArrayEquals(name, body, BtPreauthRecord.encode(kind, payload))
            val decoded = BtPreauthRecord.decode(body)
            assertEquals(name, kind, decoded.kind)
            assertArrayEquals(name, payload, decoded.payload)
        }
        assertEquals(2, vectors.size)
    }

    @Test
    fun emptyBodyIsRejected() {
        assertThrows(BtLinkException::class.java) { BtPreauthRecord.decode(ByteArray(0)) }
    }

    @Test
    fun kindConstantsMatchVectors() {
        assertEquals(0x01, BtPreauthRecord.KIND_CHALLENGE)
        assertEquals(0x7F, BtPreauthRecord.KIND_REJECT)
        assertEquals(0x02, BtPreauthRecord.KIND_NOISE1)
        assertEquals(0x03, BtPreauthRecord.KIND_NOISE2)
    }
}
