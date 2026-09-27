package app.handlive.android.core.protocol.sms

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `op` names of `type = sms` (0.7.1). */
object SmsOp {
    const val SYNC = "sync"
    const val HISTORY = "history"
    const val NEW = "new"
    const val SEND = "send"
    const val STATUS = "status"
    const val READ_CHANGED = "read_changed"
}

/** Values of `message.box` (5.1.5): the provider's `type` column 1, 2, 4, 5, 6; drafts (3) never leave the phone. */
object SmsBox {
    const val INBOX = "inbox"
    const val SENT = "sent"
    const val OUTBOX = "outbox"
    const val FAILED = "failed"
    const val QUEUED = "queued"
}

/** Values of `sms/status.status` (SMS-04 API 2); they only move forward. */
object SmsSendStatus {
    const val SENDING = "sending"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
}

/**
 * The `thread` object of group 5 (5.1.5). [displayName] is required but may be `null` (no `READ_CONTACTS`, or the
 * number is not a contact), so it has no default and is always written.
 */
@Serializable
data class SmsThreadData(
    @SerialName("thread_id") val threadId: Long,
    val addresses: List<String>,
    @SerialName("display_name") val displayName: String?,
    val snippet: String,
    @SerialName("last_ts") val lastTs: Long,
    @SerialName("unread_count") val unreadCount: Int,
)

/**
 * The `message` object of group 5 (5.1.5). [tsSent] and [subId] are written even when `null`, like the examples of
 * the spec; [localId] only goes to the client that created the message (SMS-04 API 4).
 */
@Serializable
data class SmsMessageData(
    @SerialName("message_key") val messageKey: String,
    @SerialName("thread_id") val threadId: Long,
    val address: String,
    val body: String,
    val box: String,
    val ts: Long,
    @SerialName("ts_sent") val tsSent: Long?,
    val read: Boolean,
    @SerialName("sub_id") val subId: Int?,
    @SerialName("local_id") val localId: String? = null,
)

/** `sms/sync` request (SMS-01 API 1): absent [cursor] = first sync; [pageToken] only while looping. */
@Serializable
data class SmsSyncRequest(
    val cursor: String? = null,
    @SerialName("page_token") val pageToken: String? = null,
    @SerialName("thread_limit") val threadLimit: Int,
    @SerialName("per_thread_limit") val perThreadLimit: Int,
)

/** One conversation with unread inbox messages: `unread` of the last sync page and `sms/read_changed` (SMS-05). */
@Serializable
data class SmsUnreadEntry(
    @SerialName("thread_id") val threadId: Long,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("read_up_to_ts") val readUpToTs: Long,
)

/** `ack.data` of `sms/sync`: [pageToken] only with [hasMore]; [unread] only on the last page. */
@Serializable
data class SmsSyncResponse(
    val threads: List<SmsThreadData>,
    val messages: List<SmsMessageData>,
    val cursor: String,
    @SerialName("page_token") val pageToken: String? = null,
    @SerialName("has_more") val hasMore: Boolean,
    val unread: List<SmsUnreadEntry>? = null,
)

/** `sms/history` request (SMS-03 API 1). */
@Serializable
data class SmsHistoryRequest(
    @SerialName("thread_id") val threadId: Long,
    @SerialName("before_ts") val beforeTs: Long,
    val limit: Int,
)

/** `ack.data` of `sms/history`: newest first, no `local_id`. */
@Serializable
data class SmsHistoryResponse(
    val messages: List<SmsMessageData>,
    @SerialName("has_more") val hasMore: Boolean,
)

/** `sms/new` (SMS-02 API 1, SMS-04 API 4). */
@Serializable
data class SmsNewData(
    val message: SmsMessageData,
    val thread: SmsThreadData,
)

/** `sms/send` request (SMS-04 API 1): exactly one address in v1; absent [subId] = the phone's default SMS SIM. */
@Serializable
data class SmsSendRequest(
    @SerialName("local_id") val localId: String,
    @SerialName("thread_id") val threadId: Long? = null,
    val addresses: List<String>,
    val body: String,
    @SerialName("sub_id") val subId: Int? = null,
)

/** `ack.data` of `sms/send`: [accepted] is always `true` when `ok = true`. */
@Serializable
data class SmsSendAckData(
    val accepted: Boolean,
    val parts: Int,
)

/** `sms/status` (SMS-04 API 2); [errorCode] only with `failed`. */
@Serializable
data class SmsStatusData(
    @SerialName("local_id") val localId: String,
    @SerialName("message_key") val messageKey: String? = null,
    val status: String,
    @SerialName("error_code") val errorCode: String? = null,
)

/** `sms/read_changed` (SMS-05 API 1). */
@Serializable
data class SmsReadChangedData(
    @SerialName("thread_id") val threadId: Long,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("read_up_to_ts") val readUpToTs: Long,
)
