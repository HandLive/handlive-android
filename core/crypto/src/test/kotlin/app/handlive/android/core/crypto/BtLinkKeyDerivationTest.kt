package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.BtLinkKeyDerivation
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** bt-link.json kind "key": `K_bt_disc`/`K_bt_link` = HKDF-SHA256(`PRK`, info, L = 32) (0.4.5). */
class BtLinkKeyDerivationTest {
    private val keyVectors =
        SharedTestVectors.file("bt-link.json").objects("vectors").filter { it.str("kind") == "key" }

    @Test
    fun discoveryAndLinkKeysMatchVectors() {
        for (v in keyVectors) {
            val name = v.str("name")
            assertEquals(name, BtLinkKeyDerivation.DISCOVERY_INFO, v.str("k_bt_disc_info"))
            assertEquals(name, BtLinkKeyDerivation.LINK_INFO, v.str("k_bt_link_info"))
            val prk = v.hex("prk")
            assertArrayEquals(name, v.hex("k_bt_disc"), BtLinkKeyDerivation.kBtDisc(prk))
            assertArrayEquals(name, v.hex("k_bt_link"), BtLinkKeyDerivation.kBtLink(prk))
        }
        assertEquals(2, keyVectors.size)
    }
}
