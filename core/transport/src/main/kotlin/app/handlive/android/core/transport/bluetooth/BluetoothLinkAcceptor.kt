package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.bluetooth.BtLinkException
import app.handlive.android.core.protocol.bluetooth.BtPreauthRecord
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Một kênh L2CAP vừa được A-SVC accept, từ đầu tới cuối (0.4.5 "Link-security handshake" bước 1-5, "Admission"):
 * [BluetoothLinkAdmission] gác kênh ngay khi mở (`BT_LINK_LIMIT`, `BT_PREAUTH_LIMIT`, khoá chung); `challenge` →
 * `noise1` (thử `K_bt_link` từng cặp) → `noise2`; liên kết chỉ được xác nhận khi bản ghi vận chuyển đầu tiên của C
 * giải mã được (bước 5), tất cả trong `BT_PREAUTH_TIMEOUT`. Mọi lỗi trước khi gửi `noise2` nhận `reject` rồi đóng;
 * lỗi xác nhận khoá (bước 3, 5) tính một `AUTH_FAILED` cho cả listener. Liên kết đã xác nhận đi vào [serve]
 * (`ControlServer.serveBluetoothPeer`, bắt tay E2E bước 6) cùng `pair_id` của psk đã khớp.
 */
class BluetoothLinkAcceptor(
    private val admission: BluetoothLinkAdmission,
    private val serve: suspend (WebSocketSession, String) -> Unit,
    private val preauthTimeout: Duration = BluetoothLinkConstants.PREAUTH_TIMEOUT,
    private val newSession: () -> BluetoothPreauthSession,
) {
    constructor(
        admission: BluetoothLinkAdmission,
        pairKeys: BluetoothPairKeys,
        serve: suspend (WebSocketSession, String) -> Unit,
    ) : this(admission, serve, newSession = { BluetoothPreauthSession(pairKeys) })

    /** Trả về khi kênh đóng; luôn trả slot admission và đóng [io]. */
    suspend fun accept(io: BluetoothLinkIo) {
        if (admission.admitNewChannel() != BluetoothLinkAdmission.AdmissionResult.Admitted) {
            sendReject(io)
            io.close()
            return
        }
        val link = LinkState()
        try {
            coroutineScope {
                val watchdog =
                    launch {
                        delay(preauthTimeout)
                        if (link.expire()) {
                            if (!link.noise2Sent) sendReject(io)
                            io.close()
                        }
                    }
                val confirmed = runPreauth(io, link, this)
                watchdog.cancel()
                if (confirmed != null) {
                    try {
                        serve(confirmed.socket, confirmed.pairId)
                    } finally {
                        confirmed.socket.shutDown()
                    }
                } else {
                    link.socket?.shutDown()
                }
            }
        } finally {
            admission.closeLink(wasPreauth = !link.confirmed)
            io.close()
        }
    }

    private class Confirmed(
        val socket: BluetoothPeerSocket,
        val pairId: String,
    )

    private enum class FirstRecord { OPENED, KEY_FAILED, CLOSED }

    /** Bước 1-5; `null` khi kênh phải đóng (đã gửi `reject` nếu còn trước `noise2`). */
    private suspend fun runPreauth(
        io: BluetoothLinkIo,
        link: LinkState,
        scope: CoroutineScope,
    ): Confirmed? =
        try {
            val session = newSession()
            io.writeRecord(session.challenge())
            val record = BtPreauthRecord.decode(io.readPreauthRecord())
            // `cpace1` chỉ hợp lệ khi cửa sổ PIN mở (A7.2a, chưa có): đóng không trả lời, như mọi kind lạc chỗ.
            if (record.kind != BtPreauthRecord.KIND_NOISE1) return null
            val verified = session.receiveNoise1(record.payload) as? BluetoothPreauthSession.Noise1Outcome.Verified
            if (verified == null) {
                admission.recordAuthFailure()
                sendReject(io)
                return null
            }
            val keys = session.buildNoise2()
            io.writeRecord(keys.noise2)
            link.noise2Sent = true
            awaitFirstRecord(io, link, scope, keys)?.let { Confirmed(it, verified.pairId) }
        } catch (_: BtLinkException) {
            // Kênh đứt, `rlen` vượt cận hoặc bị watchdog đóng: không trả lời thêm.
            null
        }

    private suspend fun awaitFirstRecord(
        io: BluetoothLinkIo,
        link: LinkState,
        scope: CoroutineScope,
        keys: BluetoothPreauthSession.LinkKeys,
    ): BluetoothPeerSocket? {
        val first = CompletableDeferred<FirstRecord>()
        // S nhận bằng c1 (C mã hoá), gửi bằng c2 (bt-link.json, Split()).
        val socket =
            BluetoothPeerSocket(
                io,
                sendCipher = BtTransportCipher(keys.c2),
                receiveCipher = BtTransportCipher(keys.c1),
                parent = scope,
                callbacks =
                    BluetoothPeerSocketCallbacks(
                        onClosed = { first.complete(FirstRecord.CLOSED) },
                        onFirstRecordOpened = {
                            val outcome = if (link.confirm()) FirstRecord.OPENED else FirstRecord.CLOSED
                            if (outcome == FirstRecord.OPENED) admission.confirmLink()
                            first.complete(outcome)
                        },
                        onFirstRecordRejected = { first.complete(FirstRecord.KEY_FAILED) },
                    ),
            )
        link.socket = socket
        return when (first.await()) {
            FirstRecord.OPENED -> {
                socket
            }

            FirstRecord.KEY_FAILED -> {
                admission.recordAuthFailure()
                null
            }

            FirstRecord.CLOSED -> {
                null
            }
        }
    }

    private suspend fun sendReject(io: BluetoothLinkIo) {
        try {
            io.writeRecord(BtPreauthRecord.encode(BtPreauthRecord.KIND_REJECT))
        } catch (_: BtLinkException) {
            // "when it can" (0.4.5): kênh đã đứt thì chỉ còn đóng.
        }
    }

    /** Bước 5 và hết giờ pre-auth loại trừ nhau: cái nào tới trước thắng. */
    private class LinkState {
        @Volatile
        var noise2Sent = false

        @Volatile
        var socket: BluetoothPeerSocket? = null

        @Volatile
        var confirmed = false
            private set

        private var expired = false

        @Synchronized
        fun confirm(): Boolean {
            if (expired) return false
            confirmed = true
            return true
        }

        @Synchronized
        fun expire(): Boolean {
            if (confirmed) return false
            expired = true
            return true
        }
    }
}

private fun BluetoothPeerSocket.shutDown() {
    end()
    cancel()
}
