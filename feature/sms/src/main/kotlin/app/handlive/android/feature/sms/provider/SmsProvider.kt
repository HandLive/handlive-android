package app.handlive.android.feature.sms.provider

/**
 * One row of `content://sms` with the columns every query of group 5 reads: `_id`, `thread_id`, `address`, `body`,
 * `type`, `date`, `date_sent`, `read`, `sub_id`. Never logged.
 */
data class SmsRow(
    val id: Long,
    val threadId: Long,
    val address: String?,
    val body: String?,
    val type: Int,
    val date: Long,
    val dateSent: Long,
    val read: Boolean,
    val subId: Int,
)

/** A conversation of `content://mms-sms/conversations?simple=true`: `_id`, `date`, the `recipient_ids`. */
data class ConversationRow(
    val threadId: Long,
    val date: Long,
    val recipientIds: List<Long>,
)

/** An unread inbox message (`type = 1 AND read = 0`): its conversation and date (SMS-01 step 5, SMS-05 step 3). */
data class UnreadRow(
    val threadId: Long,
    val date: Long,
)

/** The `type` column of `content://sms` (5.1.5 `box`). */
object SmsType {
    const val INBOX = 1
    const val SENT = 2
    const val DRAFT = 3
    const val OUTBOX = 4
    const val FAILED = 5
    const val QUEUED = 6

    /** Every type that leaves the phone: drafts never do (`type IN (1, 2, 4, 5, 6)`). */
    val SYNCED = setOf(INBOX, SENT, OUTBOX, FAILED, QUEUED)
}

/**
 * Read access to the Telephony provider (0.9.2) with the queries of 05-sms: the SMS rows ([SmsStore]) and the
 * conversations ([ThreadStore]). Every call opens, reads and closes its own cursor on the caller's thread, so no
 * cursor stays open between pages (SMS-01 special requirements); the number of rows is limited by stopping to read,
 * not by `LIMIT`. Failures surface as exceptions (→ `INTERNAL`, SMS-01 E6).
 */
interface SmsProvider :
    SmsStore,
    ThreadStore

/** `content://sms` (`Telephony.Sms.CONTENT_URI`). */
interface SmsStore {
    /** SMS-01 step 5, SMS-02 step 3: the largest `_id`, `null` when there is no SMS at all. */
    fun maxSmsId(): Long?

    /**
     * SMS-01 step 5 (first sync): SMS of [threadId] with `_id ≤ snap`, newest first (`date DESC, _id DESC`), skipping
     * the first [skip] rows, at most [limit].
     */
    fun threadMessages(
        threadId: Long,
        snap: Long,
        skip: Int,
        limit: Int,
    ): List<SmsRow>

    /**
     * SMS-01 step 5 (catch-up): `(_id > cursorId OR date > cursorDate) AND _id > afterId AND _id ≤ snap`, ascending
     * `_id`, at most [limit].
     */
    fun messagesSince(
        cursorId: Long,
        cursorDate: Long,
        afterId: Long,
        snap: Long,
        limit: Int,
    ): List<SmsRow>

    /**
     * `t` of the new cursor: the largest `date` of the SMS with `_id ≤ snap`, restricted to
     * `_id > cursorId OR date > cursorDate` for a catch-up ([since] = cursor id and date).
     */
    fun maxDate(
        snap: Long,
        since: Pair<Long, Long>?,
    ): Long?

    /** The newest SMS of a conversation: `snippet` and `last_ts` of the `thread` object. */
    fun newestMessage(threadId: Long): SmsRow?

    /** SMS-01 steps 5–6, SMS-05 step 3: every unread inbox message, two columns only. */
    fun unreadInbox(): List<UnreadRow>

    /**
     * SMS-03 step 10: SMS of [threadId] with `date < beforeTs`, newest first (`date DESC, _id DESC`). [read] consumes
     * the rows lazily and decides when to stop; the cursor closes when it returns.
     */
    fun <T> olderMessages(
        threadId: Long,
        beforeTs: Long,
        read: (Sequence<SmsRow>) -> T,
    ): T

    /** SMS-02 step 3: every row with `_id > afterId`, ascending. */
    fun rowsAfter(afterId: Long): List<SmsRow>

    /** SMS-02 step 3: the rows waiting to be sent (`pending_out`), by `_id`. */
    fun rowsById(ids: Collection<Long>): List<SmsRow>
}

/** `content://mms-sms/conversations?simple=true` and `content://mms-sms/canonical-addresses`. */
interface ThreadStore {
    /** SMS-01 step 5 (first sync): conversations newest first, at most [limit]. */
    fun conversationsNewestFirst(limit: Int): List<ConversationRow>

    /** The `recipient_ids` of the given conversations; unknown ids are absent. */
    fun conversations(threadIds: Collection<Long>): Map<Long, ConversationRow>

    /** SMS-03 API 1 logic 1: the conversation still exists. */
    fun conversationExists(threadId: Long): Boolean

    /** Canonical addresses (`_id` → address) of the given ids; unknown ids are absent. */
    fun canonicalAddresses(ids: Collection<Long>): Map<Long, String>
}

/** Contact names for `thread.display_name` (`ContactsContract.PhoneLookup`); needs `READ_CONTACTS`. */
fun interface ContactNames {
    /** The contact name of [address], `null` when it is not a contact or the permission is missing (SMS-01 E3). */
    fun lookup(address: String): String?
}
