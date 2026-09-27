package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallLogEntryData
import app.handlive.android.core.protocol.call.CallLogType

/**
 * Call log rows as the shared `entry` object of CALL-04: `TYPE` 1 → incoming, 2 → outgoing, 3 → missed,
 * 4 → voicemail, 5 → rejected, 6 → blocked, 7 (answered elsewhere) → incoming, any other value skipped; the number
 * only when `NUMBER_PRESENTATION` is allowed, normalized like CALL-01; the name from `PhoneLookup` ([name] is `null`
 * without `READ_CONTACTS`), else `CACHED_NAME` (E3); `sub_id` from the row's phone account.
 */
class CallLogEntries(
    private val normalize: (raw: String, subId: Int?) -> String,
    private val name: ((String) -> String?)?,
    private val subIds: AccountSubIds,
) {
    /** `null` for a `TYPE` outside the table: the entry is skipped, the cursor still moves past it. */
    fun entry(row: CallLogRow): CallLogEntryData? {
        val type = typeOf(row.type) ?: return null
        val subId = subIds.subId(row.accountComponent, row.accountId)
        val raw = row.number?.trim()?.takeIf { it.isNotEmpty() && row.presentation == PRESENTATION_ALLOWED }
        val number = raw?.let { normalize(it, subId) }
        val displayName = number?.let { name?.invoke(it) } ?: row.cachedName?.takeIf { it.isNotBlank() }
        return CallLogEntryData(
            entryId = row.id,
            number = number,
            displayName = displayName,
            type = type,
            ts = row.date,
            durationS = row.durationS.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            subId = subId,
        )
    }

    companion object {
        /** `CallLog.Calls.PRESENTATION_ALLOWED`. */
        const val PRESENTATION_ALLOWED = 1

        /** `CallLog.Calls.REJECTED_TYPE`, `BLOCKED_TYPE`, `ANSWERED_EXTERNALLY_TYPE` (CALL-01 API 1 logic 5). */
        const val TYPE_REJECTED = 5
        const val TYPE_BLOCKED = 6
        const val TYPE_ANSWERED_EXTERNALLY = 7

        private val TYPES =
            mapOf(
                1 to CallLogType.INCOMING,
                2 to CallLogType.OUTGOING,
                3 to CallLogType.MISSED,
                4 to CallLogType.VOICEMAIL,
                TYPE_REJECTED to CallLogType.REJECTED,
                TYPE_BLOCKED to CallLogType.BLOCKED,
                TYPE_ANSWERED_EXTERNALLY to CallLogType.INCOMING,
            )

        fun typeOf(type: Int): String? = TYPES[type]

        /** The direction a context needs to match an entry of [type] (CALL-04 API 2 logic 2); `null` = none. */
        fun directionOf(type: Int): String? =
            when (TYPES[type]) {
                CallLogType.OUTGOING -> CallDirection.OUTGOING
                CallLogType.VOICEMAIL, null -> null
                else -> CallDirection.INCOMING
            }
    }
}
