package app.handlive.android.feature.sms

/** Constants of group 5 (0.10) and the limits the leaf specs state for Android. */
object SmsConstants {
    /** `SMS_SYNC_THREADS` and `SMS_SYNC_PER_THREAD`: the first sync; Android accepts 1–500 and 1–200. */
    const val SYNC_THREADS_MAX = 500
    const val SYNC_PER_THREAD_MAX = 200

    /** `SMS_PAGE_MAX`: messages per `sms/sync` ack. */
    const val PAGE_MAX = 500

    /** `SMS_PAGE_MAX_BYTES`: plaintext of one ack, so the envelope stays within 256 KiB after encryption. */
    const val PAGE_MAX_BYTES = 180 * 1024

    /** `sms/history.limit`: Android accepts 1–200 (`SMS_HISTORY_PAGE` = 50 is the client's default). */
    const val HISTORY_LIMIT_MAX = 200

    /** `SMS_BODY_MAX`: characters (code points) of one message sent from a client. */
    const val BODY_MAX = 1_600

    /** `thread.snippet` is the newest body cut to 160 characters (5.1.5). */
    const val SNIPPET_MAX = 160

    /** `SMS_OBSERVER_DEBOUNCE`: `onChange` calls within this window are coalesced. */
    const val OBSERVER_DEBOUNCE_MILLIS = 100L

    /** Rows waiting to be sent (`pending_out`) are dropped after 10 minutes (SMS-02 API 3 logic 5). */
    const val PENDING_OUT_MILLIS = 10 * 60 * 1000L

    /** `SMS_SEND_MATCH_WINDOW`: a Sent-box row matches a `SendRegistry` entry this long after its final result. */
    const val SEND_MATCH_WINDOW_MILLIS = 60_000L

    /** `SendRegistry` keeps at most 1 000 entries for 24 h (SMS-04 API 1 logic 5). */
    const val REGISTRY_CAPACITY = 1_000
    const val REGISTRY_TTL_MILLIS = 24 * 60 * 60 * 1000L

    /** Contact names are cached per address, 500 entries, for one sync (SMS-01 API 1 logic 9). */
    const val CONTACT_CACHE_SIZE = 500

    /** A string of 3–8 digits is a short code and is sent as it is (SMS-04 API 1 logic 2). */
    val SHORT_CODE = Regex("^[0-9]{3,8}$")
}
