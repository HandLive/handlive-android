package app.handlive.android.core.protocol.bluetooth

/**
 * Lỗi khung liên kết Bluetooth (0.4.5 "Link records"): mọi trường hợp đóng kênh ngay, không trả lời, không nói
 * lý do qua dây (cận `rlen`/`dlen` vượt, `chan` lạ, `more` không có `long`, tin làn dài vượt `BT_MESSAGE_MAX`…).
 */
class BtLinkException(
    message: String,
) : Exception(message)
