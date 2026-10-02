package app.handlive.spike.call.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.handlive.spike.call.logic.CallNotificationParser
import app.handlive.spike.call.logic.NotificationFacts

/**
 * Spike question 1: does a notification listener see calling apps' call notifications, with which intents and
 * actions, and how fast? Logs only the notification's shape (`posted`, `watched`, `removed` lines); titles, text and
 * caller names are never read into the log.
 */
class CallNotificationsService : NotificationListenerService() {
    override fun onListenerConnected() {
        SpikeLog.write(this, listOf("ev" to "listener_connected"))
        AudioRouter.get(this).watch()
        activeNotifications?.forEach { handlePosted(it, initial = true) }
    }

    override fun onListenerDisconnected() {
        SpikeLog.write(this, listOf("ev" to "listener_disconnected"))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        handlePosted(sbn, initial = false)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int,
    ) {
        val keyHash = CallNotificationParser.keyHash(sbn.key)
        if (!CallRegistry.remove(keyHash)) return
        SpikeLog.write(
            this,
            listOf(
                "ev" to "removed",
                "pkg" to sbn.packageName,
                "key" to keyHash,
                "reason" to reason,
                "since_cmd" to CallRegistry.sinceCommand(),
            ),
        )
    }

    private fun handlePosted(
        sbn: StatusBarNotification,
        initial: Boolean,
    ) {
        val facts = facts(sbn)
        val call = CallNotificationParser.parse(facts)
        val watched = CallNotificationParser.isWatchedApp(sbn.packageName)
        if (call == null && !watched) return
        val keyHash = CallNotificationParser.keyHash(sbn.key)
        CallRegistry.put(keyHash, sbn, call)
        val common =
            listOf(
                "pkg" to sbn.packageName,
                "key" to keyHash,
                "channel" to facts.channelId,
                "category" to facts.category,
                "ongoing" to facts.ongoing,
                "actions" to facts.actionTitles,
                "latency_ms" to (System.currentTimeMillis() - sbn.postTime),
                "initial" to initial,
                "since_cmd" to CallRegistry.sinceCommand(),
            )
        val fields =
            if (call != null) {
                listOf(
                    "ev" to "posted",
                    "phase" to call.phase.name.lowercase(),
                    "style" to call.callStyle,
                    "answer" to call.canAnswer,
                    "decline" to call.canDecline,
                    "hangup" to call.canHangUp,
                    "caller" to facts.hasCallPerson,
                ) + common
            } else {
                listOf("ev" to "watched") + common
            }
        SpikeLog.write(this, fields)
    }

    private fun facts(sbn: StatusBarNotification): NotificationFacts {
        val notification = sbn.notification
        val extras = notification.extras
        return NotificationFacts(
            packageName = sbn.packageName,
            key = sbn.key,
            category = notification.category,
            channelId = notification.channelId,
            callType = if (extras.containsKey(EXTRA_CALL_TYPE)) extras.getInt(EXTRA_CALL_TYPE) else null,
            hasCallPerson = extras.containsKey(EXTRA_CALL_PERSON),
            hasAnswerIntent = extras.containsKey(EXTRA_ANSWER_INTENT),
            hasDeclineIntent = extras.containsKey(EXTRA_DECLINE_INTENT),
            hasHangUpIntent = extras.containsKey(EXTRA_HANG_UP_INTENT),
            actionTitles = notification.actions?.map { it.title?.toString().orEmpty() }.orEmpty(),
            ongoing = (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0,
        )
    }

    companion object {
        // `Notification.CallStyle` extras (API 31 constants, spelled out so minSdk 29 needs no version check).
        const val EXTRA_CALL_TYPE = "android.callType"
        const val EXTRA_CALL_PERSON = "android.callPerson"
        const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
    }
}
