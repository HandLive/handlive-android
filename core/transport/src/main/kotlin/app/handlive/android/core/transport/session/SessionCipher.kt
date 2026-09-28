package app.handlive.android.core.transport.session

import app.handlive.android.core.crypto.derivation.PeerRole
import app.handlive.android.core.crypto.derivation.SessionKeys
import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.transport.TransportConstants
import java.util.UUID
import kotlin.time.Duration

/**
 * Trạng thái khóa của một phiên `/v1/ctl` theo vai [role]: khóa gửi/nhận theo chiều (0.6.3 bước 3–4),
 * thế hệ khóa `epoch`, bộ đếm envelope mỗi chiều và tuổi khóa cho ngưỡng `REKEY_AFTER`. Sau rekey, khóa nhận cũ
 * còn dùng được trong `OLD_KEY_GRACE` cho envelope đang bay. Không an toàn đa luồng: bên gọi tự tuần tự hóa.
 */
class SessionCipher(
    initialKeys: SessionKeys,
    val role: PeerRole,
    private val clock: () -> Long = System::currentTimeMillis,
    private val rekeyAfterEnvelopes: Long = TransportConstants.REKEY_AFTER_ENVELOPES,
    private val rekeyAfterAge: Duration = TransportConstants.REKEY_AFTER_AGE,
    private val oldKeyGrace: Duration = TransportConstants.OLD_KEY_GRACE,
) {
    var keys: SessionKeys = initialKeys
        private set

    /** Thế hệ khóa hiện tại: 0 sau bắt tay, tăng 1 mỗi lần rekey. */
    var epoch: Int = 0
        private set

    var sentCount: Long = 0
        private set

    var receivedCount: Long = 0
        private set

    private var keysSince: Long = clock()
    private var previousReceiveKey: ByteArray? = null
    private var previousKeyExpiresAt: Long = 0

    /** Mã hóa bằng khóa gửi hiện tại. */
    fun seal(
        header: EnvelopeHeader,
        plaintext: ByteArray,
    ): Envelope = EnvelopeCipher.seal(keys.sendKey(role), header, plaintext).also { sentCount++ }

    /**
     * A decrypted envelope. [replayed]: its `id` was already accepted in this epoch or the previous one; [earlierAck]
     * is the `ack` plaintext sent for it, if kept. [overflow]: the direction reached `MAX_TRACKED_IDS` (close 4410).
     */
    class Opened(
        val plaintext: ByteArray,
        val replayed: Boolean,
        val earlierAck: ByteArray? = null,
        val overflow: Boolean = false,
    )

    /** Capped at twice [rekeyAfterEnvelopes] (`MAX_TRACKED_IDS` = 20,000 with the spec values). */
    private val window =
        ReplayWindow(
            (rekeyAfterEnvelopes.coerceIn(1L, Int.MAX_VALUE / TRACKED_IDS_PER_REKEY) * TRACKED_IDS_PER_REKEY).toInt(),
        )

    /** Number of `id`s kept for replay checks (current and previous epoch). */
    val trackedIds: Int get() = window.size

    /** Giải mã bằng khóa nhận hiện tại, rồi khóa cũ nếu còn hạn; không được → `DECRYPT_FAILED`. */
    fun open(envelope: Envelope): ByteArray = decrypt(envelope).first

    /**
     * Decrypts like [open], then checks the `id` against every `id` accepted in the key epoch (0.5.1 rule 2,
     * `DEDUP_WINDOW`): the current epoch's set, emptied at rekey, and the previous epoch's while its key is still
     * accepted. The `id` is recorded only after the envelope decrypted, so a forged one never takes it, and in the
     * epoch whose key opened it, even when repeated (a copy sealed with the new key stays a replay later).
     */
    fun accept(envelope: Envelope): Opened {
        val (plaintext, withPrevious) = decrypt(envelope)
        val seen = window.record(UUID.fromString(envelope.id), withPrevious)
        return Opened(plaintext, seen.replayed, seen.earlierAck, seen.overflow)
    }

    /** The `ack` this side sends for request [re], kept to answer a duplicate for the whole epoch. */
    fun recordAck(
        re: String,
        ack: ByteArray,
    ) {
        runCatching { UUID.fromString(re) }.getOrNull()?.let { window.recordAck(it, ack) }
    }

    private fun decrypt(envelope: Envelope): Pair<ByteArray, Boolean> {
        dropExpiredPrevious()
        val result =
            try {
                EnvelopeCipher.open(keys.receiveKey(role), envelope) to false
            } catch (e: ProtocolException) {
                val previous = previousReceiveKey ?: throw e
                EnvelopeCipher.open(previous, envelope) to true
            }
        receivedCount++
        return result
    }

    /** The previous epoch ends for good once its key is no longer accepted: its key and its `id`s go. */
    private fun dropExpiredPrevious() {
        if (previousReceiveKey != null && clock() >= previousKeyExpiresAt) {
            previousReceiveKey = null
            window.dropPrevious()
        }
    }

    /** Đạt ngưỡng 24 h hoặc 10 000 envelope ở một chiều (0.6.3 bước 6). */
    fun rekeyDue(): Boolean =
        sentCount >= rekeyAfterEnvelopes ||
            receivedCount >= rekeyAfterEnvelopes ||
            clock() - keysSince >= rekeyAfterAge.inWholeMilliseconds

    /**
     * Chuyển sang khóa của [newEpoch]; giữ khóa nhận cũ thêm `OLD_KEY_GRACE`, đếm lại từ 0. The replay set starts
     * empty; the old one is kept with the old key.
     */
    fun install(
        newKeys: SessionKeys,
        newEpoch: Int,
    ) {
        previousReceiveKey = keys.receiveKey(role)
        previousKeyExpiresAt = clock() + oldKeyGrace.inWholeMilliseconds
        window.rotate()
        keys = newKeys
        epoch = newEpoch
        sentCount = 0
        receivedCount = 0
        keysSince = clock()
    }

    private companion object {
        const val TRACKED_IDS_PER_REKEY = 2L
    }
}
