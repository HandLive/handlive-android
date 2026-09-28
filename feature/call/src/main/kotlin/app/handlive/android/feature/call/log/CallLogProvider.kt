package app.handlive.android.feature.call.log

/**
 * One row of `CallLog.Calls` with the columns of CALL-04 (Query). [presentation] is `NUMBER_PRESENTATION` (1 allowed,
 * 2 restricted, 3 unknown, 4 payphone); [accountComponent] and [accountId] name the phone account of the call.
 */
data class CallLogRow(
    val id: Long,
    val number: String?,
    val presentation: Int,
    val cachedName: String?,
    val type: Int,
    val date: Long,
    val durationS: Long,
    val accountComponent: String? = null,
    val accountId: String? = null,
)

/**
 * The call log provider as CALL-04 reads it (`READ_CALL_LOG`); every read may throw (`SecurityException`,
 * `SQLiteException`…) and the caller turns that into `INTERNAL` (E6).
 */
interface CallLogProvider {
    /** The largest `_ID`, `null` for an empty call log (E5, API 3 logic 1). */
    fun maxId(): Long?

    /** The `_ID`s of at most [limit] entries with `DATE >= since`, newest `_ID` first (the first sync). */
    fun newestIdsSince(
        since: Long,
        limit: Int,
    ): List<Long>

    /** At most [limit] rows with `_ID > afterId` (`>=` when [inclusive]), in ascending `_ID` order. */
    fun rows(
        afterId: Long,
        inclusive: Boolean,
        limit: Int,
    ): List<CallLogRow>
}

/** `TelephonyManager.getSubscriptionId(PhoneAccountHandle)` (API 30+) of a call log row; `null` without a mapping. */
fun interface AccountSubIds {
    fun subId(
        component: String?,
        id: String?,
    ): Int?
}
