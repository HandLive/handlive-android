package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallLogEntryData
import app.handlive.android.core.protocol.call.CallLogSyncResponse
import app.handlive.android.feature.call.CallConstants

/**
 * One `call_event/log_sync` page (CALL-04 API 1 logic 2–6). Without a cursor, or with one that cannot be read or lies
 * beyond the largest `_ID` (E5, `reset = true`), the first sync starts at the smallest `_ID` of the 500 newest entries
 * of the last 90 days; with a cursor, the entries after its `_ID`. Each page reads `limit + 1` rows to know
 * `has_more`, closes at 180 KiB of plaintext, and its cursor is the `_ID` of its last row — a row skipped for an
 * unknown `TYPE` included; an empty page keeps the cursor. [entries] is made once per page, so names are cached per
 * sync and never outlive it.
 */
class CallLogSyncEngine(
    private val provider: CallLogProvider,
    private val entries: () -> CallLogEntries,
    private val clock: () -> Long,
) {
    fun page(
        cursor: String?,
        limit: Int,
    ): CallLogSyncResponse {
        val max = provider.maxId() ?: 0
        val position = cursor?.let(CallLogCursor::decode)?.takeIf { it.id <= max }
        return if (position != null) {
            page(provider.rows(position.id, inclusive = false, limit = limit + 1), limit, position.id, reset = false)
        } else {
            firstPage(limit, max, reset = cursor != null)
        }
    }

    private fun firstPage(
        limit: Int,
        max: Long,
        reset: Boolean,
    ): CallLogSyncResponse {
        val since = clock() - CallConstants.SYNC_WINDOW_MILLIS
        val start = provider.newestIdsSince(since, CallConstants.SYNC_WINDOW_ENTRIES).minOrNull()
        // Nothing in the window: the next sync only needs what comes after the newest entry, if any.
        return if (start == null) {
            CallLogSyncResponse(emptyList(), CallLogCursor.at(max).encode(), hasMore = false, reset = reset)
        } else {
            page(provider.rows(start, inclusive = true, limit = limit + 1), limit, max, reset)
        }
    }

    private fun page(
        rows: List<CallLogRow>,
        limit: Int,
        emptyCursor: Long,
        reset: Boolean,
    ): CallLogSyncResponse {
        val mapper = entries()
        val out = mutableListOf<CallLogEntryData>()
        var bytes = CallConstants.PAGE_OVERHEAD_BYTES
        var last = emptyCursor
        var taken = 0
        for (row in rows.take(limit)) {
            val entry = mapper.entry(row)
            val size = entry?.let(::sizeOf) ?: 0
            if (out.isNotEmpty() && bytes + size > CallConstants.PAGE_MAX_BYTES) break
            entry?.let { out += it }
            bytes += size
            last = row.id
            taken++
        }
        return CallLogSyncResponse(out, CallLogCursor.at(last).encode(), hasMore = rows.size > taken, reset = reset)
    }

    private fun sizeOf(entry: CallLogEntryData): Int =
        ProtocolJson.encodeToString(CallLogEntryData.serializer(), entry).toByteArray(Charsets.UTF_8).size + 1
}
