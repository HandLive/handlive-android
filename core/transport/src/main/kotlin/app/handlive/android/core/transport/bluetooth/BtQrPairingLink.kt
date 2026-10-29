package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.core.transport.server.RawTextMessageSocket
import io.ktor.websocket.WebSocketSession

/**
 * Chạy PAIR-01 API 2–6 trên một liên kết ghép đôi QR đã xác nhận (0.4.5 "Pairing link"): các envelope `pair` đi trên
 * kênh điều khiển `0x01` như trên `/v1/pair`, nên [endpoint] (`PairingCoordinator`) chạy y nguyên như ở LAN.
 */
object BtQrPairingLink {
    /** Không dùng cho admission (Bluetooth có admission riêng của listener); chỉ để phân biệt với địa chỉ LAN. */
    const val PSEUDO_ADDRESS = "bluetooth"

    /** Thời gian tối đa chờ envelope cuối (`pair/error`, `pair/done`) lên dây trước khi liên kết bị huỷ. */
    const val FLUSH_TIMEOUT_MILLIS = 2_000L

    suspend fun serve(
        socket: WebSocketSession,
        endpoint: PairingEndpoint,
        clock: () -> Long = System::currentTimeMillis,
    ) {
        endpoint.handle(RawTextMessageSocket(socket, PSEUDO_ADDRESS, clock))
        // BluetoothLinkAcceptor huỷ socket ngay khi hàm này trả về: `pair/error` vừa xếp hàng phải lên dây trước đã.
        (socket as? BluetoothPeerSocket)?.awaitWritesFinished(FLUSH_TIMEOUT_MILLIS)
    }
}
