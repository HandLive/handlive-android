package app.handlive.android.feature.call

/** Constants of group 6 (0.10) and the windows the leaf specs state for Android. */
object CallConstants {
    /** A per-SIM report counts for `sub_id` within 500 ms around the default callback (CALL-01 API 2 logic 3). */
    const val SIM_WINDOW_MILLIS = 500L

    /** A number-carrying broadcast that arrives before the state callback is held this long (API 3 logic 4). */
    const val BROADCAST_HOLD_MILLIS = 2_000L

    /** An ended context stays in the "recently ended" list this long, for the call log (CALL-01 postconditions). */
    const val RECENTLY_ENDED_MILLIS = 60_000L

    /** One command per `call_id` within 3 s; the "declined by HandLive" flag lives as long (CALL-02 API 1). */
    const val ACTION_LOCK_MILLIS = 3_000L

    /** The `call_incoming` push waits at most this long after `RINGING` for the number (CALL-01 API 4 logic 2). */
    const val PUSH_NUMBER_WAIT_MILLIS = 300L

    /** A call log entry matches an ended context whose `started_at` is this close to its `DATE` (CALL-04 API 2). */
    const val LOG_MATCH_MILLIS = 5_000L

    /** `CALLLOG_SYNC_WINDOW`: the first sync covers 90 days, at most 500 entries. */
    const val SYNC_WINDOW_MILLIS = 90L * 24 * 60 * 60 * 1000
    const val SYNC_WINDOW_ENTRIES = 500

    /** `log_sync.limit`: Android accepts 1–500 (the client sends 200). */
    const val SYNC_LIMIT_MAX = 500

    /** A page closes when its plaintext reaches 180 KiB, like `SMS_PAGE_MAX_BYTES` (CALL-04 API 1 logic 6). */
    const val PAGE_MAX_BYTES = 180 * 1024

    /** `ack` wrapper around a page: `{"re":"<uuid>","ok":true,"data":{…,"cursor":…,"has_more":…,"reset":…}}`. */
    const val PAGE_OVERHEAD_BYTES = 160

    /** Contact names in A-CALL memory (CALL-01 Query), and per sync (CALL-04 API 1 logic 7). */
    const val NAME_CACHE_SIZE = 200

    /** `onChange` calls of the call log within 100 ms are coalesced (CALL-04 API 3 logic 2). */
    const val LOG_OBSERVER_DEBOUNCE_MILLIS = 100L

    /** New call log rows are read in batches of this size (CALL-04 API 3). */
    const val LOG_ROUND_BATCH = 100

    /**
     * `APP_CALL_LINK_WINDOW` (CALL-05): after a ringing app call's notification is removed, an ongoing notification of
     * the same package within this long is its in-call notification; otherwise the call ended.
     */
    const val APP_CALL_LINK_WINDOW_MILLIS = 3_000L

    /**
     * The window is decided by post times, but Android delivers a post 215–232 ms after its `postTime` (spike T3.2):
     * the end of the window is processed this much later, so an in-call notification posted inside the window and
     * still on its way is not cut off.
     */
    const val APP_CALL_LINK_GRACE_MILLIS = 500L

    /** `APP_CALL_TAP_NOTIFICATION_TTL`: the "tap to answer" notification is removed after this long at the latest. */
    const val APP_CALL_TAP_NOTIFICATION_TTL_MILLIS = 60_000L

    /** `app_call` wire limits: `caller` string(128), `app.label` string(64). */
    const val APP_CALL_CALLER_MAX = 128
    const val APP_CALL_LABEL_MAX = 64
}
