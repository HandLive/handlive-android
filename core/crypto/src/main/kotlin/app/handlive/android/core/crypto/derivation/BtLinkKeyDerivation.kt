package app.handlive.android.core.crypto.derivation

import app.handlive.android.core.crypto.primitives.HkdfSha256

/**
 * `K_bt_disc`/`K_bt_link` (0.4.5): HKDF-SHA256(`PRK`, salt rỗng, info, L = 32) — sống suốt đời cặp, tính khi cần
 * như `K_push`. Khác `K_bt_pdisc`/`K_bt_plink` (dẫn từ `pairing_secret`, chỉ sống theo một QR).
 */
object BtLinkKeyDerivation {
    const val DISCOVERY_INFO = "handlive/v1/bt-discovery"
    const val LINK_INFO = "handlive/v1/bt-link"

    fun kBtDisc(prk: ByteArray): ByteArray = HkdfSha256.derive(ikm = prk, info = DISCOVERY_INFO)

    fun kBtLink(prk: ByteArray): ByteArray = HkdfSha256.derive(ikm = prk, info = LINK_INFO)
}
