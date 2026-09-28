package app.handlive.android.feature.call.log

import app.handlive.android.feature.call.CallConstants

/**
 * The new call log rows while the observer runs (CALL-04 API 3 logic 1–3): `last_calllog_id` lives in memory and
 * starts at the largest `_ID` at registration — the history goes through `log_sync` — and is lowered when the
 * newest entries were deleted. Never relies on the `onChange` uri: each round reads `_ID > last_calllog_id`.
 */
class CallLogWatcher(
    private val provider: CallLogProvider,
) {
    private var lastId: Long? = null

    /** The observer was registered (calls on with `READ_CALL_LOG`); old entries are not replayed. */
    fun start() {
        lastId = provider.maxId() ?: 0
    }

    fun stop() {
        lastId = null
    }

    /** The rows added since the last round, in ascending `_ID` order; none while stopped. */
    fun newRows(): List<CallLogRow> {
        val last = lastId ?: return emptyList()
        val max = provider.maxId() ?: 0
        val rows = if (max < last) emptyList() else rowsAfter(last)
        lastId = rows.lastOrNull()?.id ?: minOf(last, max)
        return rows
    }

    private fun rowsAfter(id: Long): List<CallLogRow> {
        val rows = mutableListOf<CallLogRow>()
        var after = id
        do {
            val batch = provider.rows(after, inclusive = false, limit = CallConstants.LOG_ROUND_BATCH)
            rows += batch
            after = batch.lastOrNull()?.id ?: after
        } while (batch.size == CallConstants.LOG_ROUND_BATCH)
        return rows
    }
}
