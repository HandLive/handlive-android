package app.handlive.android.feature.call.appcall

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.core.strings.R
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.core.design.R as DesignR

/**
 * [TapToAnswerNotifier] on the channel `hl_app_call` (CALL-05 API 5): "Answer <app> Call", the caller as its text, one
 * per call, private on the lock screen with a public version of the title only. Its content intent is the app's own
 * answer intent, so the user's tap starts the app's answer screen with no exemption needed. It is not a call
 * notification — no `CallStyle`, no `call` category — and the listener skips HandLive's own notifications anyway.
 * Nothing is posted when notifications are off for HandLive or its channel is blocked.
 */
class AndroidTapToAnswerNotifier(
    context: Context,
) : TapToAnswerNotifier {
    private val context = context.applicationContext

    override fun post(
        callId: String,
        label: String,
        caller: String?,
        answer: AppIntent,
    ): Boolean {
        val pending = (answer as? PendingAppIntent)?.pending
        val manager = NotificationManagerCompat.from(context)
        return if (pending != null && manager.areNotificationsEnabled() && channelOpen(manager)) {
            val title = context.getString(R.string.call_app_tap_to_answer_title, label)
            val notification =
                builder(title)
                    .setContentText(caller)
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOnlyAlertOnce(true)
                    .setTimeoutAfter(CallConstants.APP_CALL_TAP_NOTIFICATION_TTL_MILLIS)
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(builder(title).build())
                    .build()
            try {
                manager.notify(tag(callId), ID, notification)
                true
            } catch (_: SecurityException) {
                // POST_NOTIFICATIONS revoked between the check and the post.
                false
            }
        } else {
            false
        }
    }

    /** The channel `hl_app_call` is not blocked by the user. */
    private fun channelOpen(manager: NotificationManagerCompat): Boolean =
        manager.getNotificationChannel(NotificationChannels.APP_CALL)?.importance !=
            NotificationManagerCompat.IMPORTANCE_NONE

    override fun cancel(callId: String) = NotificationManagerCompat.from(context).cancel(tag(callId), ID)

    private fun builder(title: String) =
        NotificationCompat
            .Builder(context, NotificationChannels.APP_CALL)
            .setSmallIcon(DesignR.drawable.ic_symbol_smartphone)
            .setContentTitle(title)

    private fun tag(callId: String) = "app_call:$callId"

    private companion object {
        const val ID = 1
    }
}
