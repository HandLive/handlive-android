package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.feature.sms.observe.NewMessageScanner
import app.handlive.android.feature.sms.observe.ReadStateTracker
import app.handlive.android.feature.sms.provider.SmsObjects
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.send.SendRegistry

/** Where the SMS events go for clients without a session (the relay module: CONN-03 wake-up, CONN-04 push). */
fun interface OfflineSmsDelivery {
    /** [message] reached the sessions of [reached]; other clients may need a push or a relay connection. */
    suspend fun newMessage(
        message: SmsNewData,
        reached: Set<String>,
    )
}

/**
 * One round of the SMS observer (SMS-02 steps 3–5, 10 and 12, then SMS-05 steps 3–5): the new rows go out as
 * `sms/new` (a row the system wrote after sending for a client carries that client's `local_id`, SMS-04 API 4), then
 * the unread snapshot is compared and `sms/read_changed` goes out — the new-message part first, for the 500 ms
 * target. Runs on the module's serial worker.
 */
class SmsEvents(
    private val scanner: NewMessageScanner,
    private val readState: ReadStateTracker,
    private val objects: () -> SmsObjects,
    private val registry: SendRegistry,
    private val broadcaster: SmsBroadcaster,
    private val offline: OfflineSmsDelivery,
) {
    /** The observer starts: the first unread snapshot emits nothing (SMS-05 step 4), then one round. */
    suspend fun start() {
        readState.reset(objects().unreadEntries)
        round()
    }

    fun stop() = readState.forget()

    suspend fun round() {
        val rows = scanner.scan()
        val scope = objects()
        val announced = HashMap<Long, Int>()
        val news =
            rows.mapNotNull { row ->
                val message = scope.message(row)
                val thread = scope.thread(row.threadId)
                if (message != null && thread != null) row to SmsNewData(message, thread) else null
            }
        for ((row, new) in news) {
            val message = new.message
            val sentFor =
                if (row.type == SmsType.SENT || row.type == SmsType.FAILED) {
                    registry.match(message.address, message.body, message.messageKey)
                } else {
                    null
                }
            val withLocalId = sentFor?.let { SmsNewData(message.copy(localId = it.localId), new.thread) }
            val reached = broadcaster.newMessage(new, sentFor?.pairId, withLocalId)
            announced[row.threadId] = new.thread.unreadCount
            offline.newMessage(new, reached)
        }
        readState.changes(scope.unreadEntries, announced).forEach { broadcaster.readChanged(it) }
    }
}
