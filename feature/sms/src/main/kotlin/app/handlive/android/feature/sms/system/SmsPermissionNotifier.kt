package app.handlive.android.feature.sms.system

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.connection.notification.OpenRequest
import app.handlive.android.feature.connection.session.PeerSession
import app.handlive.android.feature.sms.module.PermissionMissingListener
import app.handlive.android.core.design.R as DesignR

/**
 * SET-01 field 17: when a client got `PERMISSION_MISSING` for SMS, "<device> needs SMS permission on this phone — tap
 * to allow" (the same text whichever SMS permission is missing) on the `permission` channel, at most once per 24
 * hours; tapping it opens HandLive on the SMS primer (SET-01 part B). Nothing is posted while notifications are off
 * (E1).
 */
class SmsPermissionNotifier(
    context: Context,
    private val clock: () -> Long,
) : PermissionMissingListener {
    private val context = context.applicationContext
    private var lastPostedAt: Long? = null

    override fun onPermissionMissing(
        session: PeerSession,
        permission: String,
    ) {
        if (!due()) return
        val open =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                putExtra(OpenRequest.EXTRA, OpenRequest.SMS_PERMISSION)
            } ?: return
        val text = context.getString(R.string.notification_permission_sms_read, session.peerName)
        val notification =
            NotificationCompat
                .Builder(context, NotificationChannels.PERMISSION)
                .setSmallIcon(DesignR.drawable.ic_symbol_smartphone)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        REQUEST_CODE,
                        open,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                ).setAutoCancel(true)
                .build()
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) runCatching { manager.notify(TAG, ID, notification) }
    }

    /** Once per feature per 24 h (SET-01 field 17). */
    @Synchronized
    private fun due(): Boolean {
        val now = clock()
        val last = lastPostedAt
        if (last != null && now - last < INTERVAL_MILLIS) return false
        lastPostedAt = now
        return true
    }

    private companion object {
        const val TAG = "permission-sms"
        const val ID = 1
        const val REQUEST_CODE = 17
        const val INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
    }
}
