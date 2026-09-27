package app.handlive.android.feature.sms.observe

import app.handlive.android.core.protocol.sms.SmsReadChangedData
import app.handlive.android.core.protocol.sms.SmsUnreadEntry

/**
 * SMS-05 steps 3–4 on the phone: the unread inbox messages per conversation, compared with the previous snapshot.
 * A conversation that appears, disappears or changes its count or `read_up_to_ts` changed; one that got an `sms/new`
 * in the same round with the same unread count is skipped (the client already has that count). The first snapshot
 * emits nothing.
 */
class ReadStateTracker(
    private val clock: () -> Long,
) {
    private var snapshot: Map<Long, SmsUnreadEntry>? = null

    /** Builds the first snapshot when A-SMS starts (no events). */
    fun reset(current: List<SmsUnreadEntry>) {
        snapshot = current.associateBy { it.threadId }
    }

    fun forget() {
        snapshot = null
    }

    /** The `read_changed` events of this round; [announced] = `thread_id` → `unread_count` sent in `sms/new`. */
    fun changes(
        current: List<SmsUnreadEntry>,
        announced: Map<Long, Int>,
    ): List<SmsReadChangedData> {
        val previous = snapshot
        val now = current.associateBy { it.threadId }
        snapshot = now
        if (previous == null) return emptyList()
        return (previous.keys + now.keys)
            .sorted()
            .filter { previous[it] != now[it] }
            .map { threadId ->
                // `unread_count = 0` → `read_up_to_ts` is the time of the event (SMS-05 API 1).
                now[threadId]?.let { SmsReadChangedData(threadId, it.unreadCount, it.readUpToTs) }
                    ?: SmsReadChangedData(threadId, 0, clock())
            }.filterNot { announced[it.threadId] == it.unreadCount }
    }
}
