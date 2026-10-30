package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.crypto.primitives.HkdfSha256
import app.handlive.android.core.crypto.primitives.HmacSha256

/**
 * Khoá của một QR đang mở (0.4.5 "Keys", "Pairing mode"): `K_bt_pdisc`/`K_bt_plink` dẫn từ `pairing_secret`,
 * sống trong trí nhớ suốt đời QR đó — khác `K_bt_disc`/`K_bt_link` của một cặp đã ghép (`BtLinkKeyDerivation` của
 * `core:crypto`, dẫn từ `PRK`). `pr_bt` là hint quảng bá của cửa sổ, đổi theo mỗi QR và không suy ra được
 * `pairing_secret` hay `pk` (AC9). Vector: `shared/test-vectors/bt-pairing.json`.
 */
object BtQrPairingKeys {
    const val PDISC_INFO = "handlive/v1/bt-pair-disc"
    const val PLINK_INFO = "handlive/v1/bt-pair-link"
    const val PR_BT_SIZE = 4
    private val PR_BT_LABEL = "HLBTPAIR1".toByteArray(Charsets.US_ASCII)

    fun kBtPdisc(pairingSecret: ByteArray): ByteArray = HkdfSha256.derive(ikm = pairingSecret, info = PDISC_INFO)

    fun kBtPlink(pairingSecret: ByteArray): ByteArray = HkdfSha256.derive(ikm = pairingSecret, info = PLINK_INFO)

    /** `pr_bt` = 4 byte đầu của HMAC-SHA256(`K_bt_pdisc`, `"HLBTPAIR1"`). */
    fun prBt(kBtPdisc: ByteArray): ByteArray = HmacSha256.mac(kBtPdisc, PR_BT_LABEL).copyOf(PR_BT_SIZE)
}

/**
 * `K_bt_plink` của cửa sổ ghép đôi QR đang mở, hoặc `null` khi không có cửa sổ nào (0.4.5 "Pairing mode"). Cài
 * đặt thật đọc cửa sổ hiện tại của `PairingCoordinator` (feature/pairing, A7.2-B); đây chỉ là điểm nối để
 * `BluetoothPreauthSession` không phụ thuộc feature/pairing.
 */
fun interface BluetoothPairingKeySource {
    fun currentPlinkKey(): ByteArray?
}

/** Cửa sổ ghép đôi QR như listener Bluetooth thấy: khoá thử `noise1` và hint `pr_bt` đứng đầu quảng bá. */
interface BluetoothQrPairingWindow : BluetoothPairingKeySource {
    /** `pr_bt` của cửa sổ QR đang mở, hoặc `null` khi không có cửa sổ QR nào. */
    fun currentPrBt(): ByteArray?
}
