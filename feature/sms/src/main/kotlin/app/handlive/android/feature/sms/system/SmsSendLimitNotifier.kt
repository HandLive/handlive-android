package app.handlive.android.feature.sms.system

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.sms.module.SendLimitListener
import app.handlive.android.core.design.R as DesignR

/**
 * SMS-04 field 12 (E11): "HandLive stopped sending messages from <name> for now…" on the `permission` channel, one
 * notification per pair (the module already limits it to once per pair per day). Nothing is posted while
 * notifications are off.
 */
class SmsSendLimitNotifier(
    context: Context,
) : SendLimitListener {
    private val context = context.applicationContext

    override fun onSendLimited(session: PeerSession) {
        val text = context.getString(R.string.sms_send_rate_limited_body, session.peerName)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.PERMISSION)
                .setSmallIcon(DesignR.drawable.ic_symbol_smartphone)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setAutoCancel(true)
                .build()
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) {
            runCatching {
                manager.notify(
                    TAG,
                    session.pairId.hashCode(),
                    notification,
                )
            }
        }
    }

    private companion object {
        const val TAG = "sms-send-limit"
    }
}
