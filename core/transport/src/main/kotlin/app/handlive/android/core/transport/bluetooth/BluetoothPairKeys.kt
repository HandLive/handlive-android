package app.handlive.android.core.transport.bluetooth

/**
 * `K_bt_link` của mọi cặp chưa thu hồi (0.4.5 bước 3): quảng bá BLE không mang `pair_id`, nên S thử lần lượt
 * theo thứ tự trả về đây. Bản thật dẫn mỗi khoá bằng `BtLinkKeyDerivation.kBtLink(pair.prk)` khi cần, như `K_push`.
 */
fun interface BluetoothPairKeys {
    fun unrevokedLinkKeys(): List<Pair<String, ByteArray>>
}
