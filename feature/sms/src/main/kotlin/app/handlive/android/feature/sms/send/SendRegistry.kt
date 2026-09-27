package app.handlive.android.feature.sms.send

import app.handlive.android.core.protocol.sms.SmsSendStatus
import app.handlive.android.core.protocol.sms.SmsStatusData
import app.handlive.android.feature.sms.SmsConstants

/** What is sent: the normalized recipient, the text, the SIM (`null` = the default one) and the number of parts. */
class OutgoingSms(
    val address: String,
    val body: String,
    val subId: Int?,
    val parts: Int,
)

/**
 * One message sent for a client (SMS-04 API 1 logic 5): which pair asked, what was sent, the result of each part,
 * the provider row once matched, and the times. Kept in memory only.
 */
class SendEntry(
    val localId: String,
    val pairId: String,
    val message: OutgoingSms,
    val createdAt: Long,
) {
    val parts: Int get() = message.parts

    /** `null` until `sendMultipartTextMessage` was called. */
    var status: String? = null
        private set
    var errorCode: String? = null
        private set
    var messageKey: String? = null
        internal set

    /** When the final result (every part sent, or the first failure) arrived; the match window starts there. */
    var finalAt: Long? = null
        private set
    private val sent = BooleanArray(message.parts)
    private val delivered = BooleanArray(message.parts)

    val statusData: SmsStatusData? get() = status?.let { SmsStatusData(localId, messageKey, it, errorCode) }

    /** Moves forward to [to] (SMS-04 API 2 logic 1–2); `false` when the move is not allowed. */
    internal fun move(
        to: String,
        now: Long,
        error: String? = null,
    ): Boolean {
        if (!SendStatus.canMove(status, to)) return false
        status = to
        if (to == SmsSendStatus.FAILED) errorCode = error
        if ((to == SmsSendStatus.SENT || to == SmsSendStatus.FAILED) && finalAt == null) finalAt = now
        return true
    }

    /** Part [index] reported `RESULT_OK`; `true` once every part has. */
    internal fun partSent(index: Int): Boolean {
        if (index in sent.indices) sent[index] = true
        return sent.all { it }
    }

    /** Part [index] has a successful delivery report; `true` once every part has one. */
    internal fun partDelivered(index: Int): Boolean {
        if (index in delivered.indices) delivered[index] = true
        return delivered.all { it }
    }
}

/**
 * `SendRegistry` of A-SMS (SMS-04 API 1 logic 4–5): `local_id` → [SendEntry], at most 1 000 entries for 24 h. It
 * de-duplicates retries by `local_id` and matches the rows the system writes into the Sent box with the message they
 * belong to (API 4 logic 1). Lost when the service restarts. Not thread-safe: the SMS module serializes access.
 */
class SendRegistry(
    private val clock: () -> Long,
    private val capacity: Int = SmsConstants.REGISTRY_CAPACITY,
    private val ttlMillis: Long = SmsConstants.REGISTRY_TTL_MILLIS,
) {
    private val entries = LinkedHashMap<String, SendEntry>()

    fun find(localId: String): SendEntry? {
        evict()
        return entries[localId]
    }

    fun add(entry: SendEntry) {
        evict()
        entries[entry.localId] = entry
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    /** Every entry of [pairId], oldest first: re-broadcast when the pair has a new session (API 1 logic 6). */
    fun ofPair(pairId: String): List<SendEntry> {
        evict()
        return entries.values.filter { it.pairId == pairId }
    }

    /**
     * API 4 logic 1: the entry a new Sent-box or failed row belongs to — same recipient, same text, not matched yet,
     * and still waiting for its final result or within 60 s after it; the oldest when several match. The entry keeps
     * [messageKey] (logic 2), so it matches at most one row.
     */
    fun match(
        address: String,
        body: String,
        messageKey: String,
    ): SendEntry? {
        val now = clock()
        return entries.values
            .filter { it.messageKey == null && it.message.address == address && it.message.body == body }
            .filter { entry -> entry.finalAt.let { it == null || now - it <= SmsConstants.SEND_MATCH_WINDOW_MILLIS } }
            .minByOrNull { it.createdAt }
            ?.also { it.messageKey = messageKey }
    }

    private fun evict() {
        val oldest = clock() - ttlMillis
        entries.values.removeAll { it.createdAt < oldest }
    }
}
