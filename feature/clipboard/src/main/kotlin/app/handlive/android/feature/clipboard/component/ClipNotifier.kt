package app.handlive.android.feature.clipboard.component

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.core.strings.R
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.ClipNotices
import app.handlive.android.feature.clipboard.TransferProgress
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.connection.notification.NotificationChannels
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap
import app.handlive.android.core.design.R as DesignR

/**
 * [ClipNotices] on Android: toasts for results and errors in place (C19), and the silent `clipboard` channel for
 * "Sensitive Content Blocked", "Clipboard Not Updated on …" (each replaces its previous one and expires with its
 * button after 120 s) and image progress with "Cancel". All texts come from the catalog.
 */
class ClipNotifier(
    context: Context,
) : ClipNotices {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lastPercent = ConcurrentHashMap<String, Int>()

    override fun show(message: ClipMessage) {
        val text =
            when (message) {
                is ClipMessage.SentTo -> context.getString(R.string.clipboard_sent_to, message.deviceName)
                ClipMessage.NotConnected -> context.getString(R.string.clipboard_not_connected_will_send)
                ClipMessage.EmptyOrNotText -> context.getString(R.string.clipboard_empty_or_not_text)
                ClipMessage.TextTooLarge -> context.getString(R.string.error_clip_text_too_large)
                ClipMessage.ImageTooLarge -> context.getString(R.string.error_clip_image_too_large)
                ClipMessage.ImageUnreadable -> context.getString(R.string.error_clip_image_unreadable)
                ClipMessage.ImageSendFailed -> context.getString(R.string.error_clip_image_send_failed)
                ClipMessage.ImageNoSpace -> context.getString(R.string.error_clip_image_no_space)
                is ClipMessage.FeatureDisabled -> context.getString(R.string.error_feature_disabled, message.deviceName)
            }
        main.post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
    }

    override fun sensitiveBlocked() {
        val notification =
            builder()
                .setSmallIcon(DesignR.drawable.ic_symbol_visibility_off)
                .setContentTitle(context.getString(R.string.clipboard_sensitive_blocked_title))
                .setContentText(context.getString(R.string.clipboard_sensitive_blocked_body))
                .setTimeoutAfter(ClipLimits.STALE_AFTER_MILLIS)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.clipboard_send_anyway), ClipActionReceiver.sendAnyway(context))
                .build()
        post(ID_SENSITIVE, null, notification)
    }

    override fun conflict(
        deviceName: String,
        clipId: String,
    ) {
        val notification =
            builder()
                .setSmallIcon(DesignR.drawable.ic_symbol_content_paste)
                .setContentTitle(context.getString(R.string.clipboard_conflict_title, deviceName))
                .setContentText(context.getString(R.string.clipboard_conflict_body))
                .setTimeoutAfter(ClipLimits.STALE_AFTER_MILLIS)
                .setAutoCancel(true)
                .addAction(
                    0,
                    context.getString(R.string.clipboard_send_again),
                    ClipActionReceiver.sendAgain(context, clipId),
                ).build()
        post(ID_CONFLICT, null, notification)
    }

    override fun progress(progress: TransferProgress) {
        val percent = progress.percent
        if (percent == null) {
            lastPercent.remove(progress.transferId)
            NotificationManagerCompat.from(context).cancel(progress.transferId, ID_PROGRESS)
            return
        }
        // Notifications are rate-limited by the system: update every 5 % only.
        val previous = lastPercent[progress.transferId]
        if (previous != null && percent - previous < PROGRESS_STEP && percent < FULL) return
        lastPercent[progress.transferId] = percent
        val text =
            context.getString(
                if (progress.sending) R.string.clipboard_image_sending else R.string.clipboard_image_receiving,
                progress.deviceName,
                NumberFormat.getPercentInstance().format(percent / FULL.toDouble()),
            )
        val cancel = ClipActionReceiver.cancelTransfer(context, progress.pairId, progress.transferId, progress.sending)
        val notification =
            builder()
                .setSmallIcon(DesignR.drawable.ic_symbol_image)
                .setContentText(text)
                .setProgress(FULL, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(0, context.getString(R.string.common_cancel), cancel)
                .build()
        post(ID_PROGRESS, progress.transferId, notification)
    }

    private fun builder() =
        NotificationCompat
            .Builder(context, NotificationChannels.CLIPBOARD)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)

    /** Android 13+ without `POST_NOTIFICATIONS`: notifications are off and nothing is posted (SET-01 E1). */
    private fun post(
        id: Int,
        tag: String?,
        notification: Notification,
    ) {
        val manager = NotificationManagerCompat.from(context)
        if (manager.areNotificationsEnabled()) runCatching { manager.notify(tag, id, notification) }
    }

    companion object {
        const val ID_SENSITIVE = 101
        const val ID_CONFLICT = 102
        const val ID_PROGRESS = 103
        private const val PROGRESS_STEP = 5
        private const val FULL = 100
    }
}
