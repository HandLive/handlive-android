package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.core.transport.server.RawTextMessageSocket
import app.handlive.android.core.transport.server.TextMessageSocket
import io.ktor.websocket.WebSocketSession

/**
 * Chạy PAIR-01 API 2-6 trên một liên kết ghép đôi PIN đã xác nhận bằng `K_bt_pinlink` (0.4.5 "PIN", A7.2-C): như
 * [BtQrPairingLink], các envelope `pair` đi trên kênh điều khiển `0x01` nên [endpoint] (`PairingCoordinator`) chạy y
 * nguyên như ở LAN — chỉ khác socket mang thêm [kBtPin] để [PairingExchange] dùng làm `K_pa` thay Argon2id
 * (0.4.5 "Keys from ISK": `K_bt_pin` thay `K_pin` ở bước 9-11).
 */
object BtPinPairingLink {
    /** Không dùng cho admission (Bluetooth có admission riêng của listener); chỉ để phân biệt với địa chỉ LAN. */
    const val PSEUDO_ADDRESS = "bluetooth"

    /** Thời gian tối đa chờ envelope cuối (`pair/error`, `pair/done`) lên dây trước khi liên kết bị huỷ. */
    const val FLUSH_TIMEOUT_MILLIS = 2_000L

    suspend fun serve(
        socket: WebSocketSession,
        kBtPin: ByteArray,
        endpoint: PairingEndpoint,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        val wrapped = BluetoothPinSecretTextMessageSocket(RawTextMessageSocket(socket, PSEUDO_ADDRESS, clock), kBtPin)
        endpoint.handle(wrapped)
        // BluetoothLinkAcceptor huỷ socket ngay khi hàm này trả về: `pair/error` vừa xếp hàng phải lên dây trước đã.
        (socket as? BluetoothPeerSocket)?.awaitWritesFinished(FLUSH_TIMEOUT_MILLIS)
    }
}

/** Một socket ghép đôi PIN qua Bluetooth mang theo `K_bt_pin` của chính kênh đó (0.4.5 "PIN"): [PairingExchange]
 * dùng [kBtPin] thay vì dẫn `K_pin` qua Argon2id khi đang chạy trên một liên kết như thế này. */
interface BluetoothPinSecretSocket : TextMessageSocket {
    val kBtPin: ByteArray
}

private class BluetoothPinSecretTextMessageSocket(
    private val delegate: TextMessageSocket,
    override val kBtPin: ByteArray,
) : BluetoothPinSecretSocket,
    TextMessageSocket by delegate
