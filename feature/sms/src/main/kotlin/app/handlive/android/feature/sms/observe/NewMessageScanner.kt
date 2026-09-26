package app.handlive.android.feature.sms.observe

import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.provider.SmsProvider
import app.handlive.android.feature.sms.provider.SmsRow
import app.handlive.android.feature.sms.provider.SmsType

/**
 * Steps 3, 4 and 12 of SMS-02 (API 3 logic 1–5): which provider rows are new since `last_sms_id`, which of them go
 * out now (`inbox`, `sent`, `failed`), which wait in `pending_out` until they leave the outbox or queue (dropped after
 * 10 minutes), and the mark written back. Drafts are ignored (E7). One instance per observer, used by one worker.
 */
class NewMessageScanner(
    private val provider: SmsProvider,
    private val state: ObserverState,
    private val clock: () -> Long,
) {
    /** `pending_out`: `_id` → when the row was first seen waiting. */
    private val pendingOut = LinkedHashMap<Long, Long>()

    /** Rows to broadcast, in ascending `_id` order; empty on the first run (history goes through SMS-01). */
    suspend fun scan(): List<SmsRow> {
        val max = provider.maxSmsId() ?: 0
        val last = state.lastSmsId()
        if (last == null) {
            // Logic 1: the first run starts from the current newest message and broadcasts nothing.
            state.write(max)
            return emptyList()
        }
        // Logic 4: the newest message was deleted, its `_id` may come back.
        val from = minOf(last, max)
        val fresh = provider.rowsAfter(from)
        val ready = mutableListOf<SmsRow>()
        for (row in fresh) {
            when (row.type) {
                SmsType.INBOX, SmsType.SENT, SmsType.FAILED -> ready += row
                SmsType.OUTBOX, SmsType.QUEUED -> pendingOut.putIfAbsent(row.id, clock())
                else -> Unit
            }
        }
        ready += leftPendingOut(fresh.map { it.id }.toSet())
        // Step 12: the mark covers every row read, skipped ones included, without waiting for the clients.
        val mark = fresh.maxOfOrNull { it.id } ?: from
        if (mark != last) state.write(mark)
        return ready.sortedBy { it.id }
    }

    /** Logic 5: rows of `pending_out` that became `sent` or `failed` go out; gone or older than 10 minutes, dropped. */
    private fun leftPendingOut(justRead: Set<Long>): List<SmsRow> {
        val now = clock()
        pendingOut.entries.removeAll { now - it.value > SmsConstants.PENDING_OUT_MILLIS }
        val waiting = pendingOut.keys.filterNot { it in justRead }
        if (waiting.isEmpty()) return emptyList()
        val current = provider.rowsById(waiting).associateBy { it.id }
        val done = mutableListOf<SmsRow>()
        for (id in waiting) {
            val row = current[id]
            when (row?.type) {
                null -> {
                    pendingOut.remove(id)
                }

                SmsType.SENT, SmsType.FAILED -> {
                    pendingOut.remove(id)
                    done += row
                }

                SmsType.OUTBOX, SmsType.QUEUED -> {
                    Unit
                }

                else -> {
                    pendingOut.remove(id)
                }
            }
        }
        return done
    }
}
