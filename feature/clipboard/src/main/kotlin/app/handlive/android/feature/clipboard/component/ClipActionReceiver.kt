package app.handlive.android.feature.clipboard.component

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.feature.clipboard.ClipboardFeature

/**
 * The buttons of the `clipboard` notifications: "Send Anyway" (QC3), "Send Again" (CLIP-01 field 13) and "Cancel"
 * on image progress (CLIP-03 field 4). They act on content held in memory, so no activity is started.
 */
class ClipActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val module = ClipboardFeature.get(context).module
        val notifications = NotificationManagerCompat.from(context)
        when (intent.action) {
            ACTION_SEND_ANYWAY -> {
                notifications.cancel(ClipNotifier.ID_SENSITIVE)
                module.sendAnyway()
            }

            ACTION_SEND_AGAIN -> {
                notifications.cancel(ClipNotifier.ID_CONFLICT)
                intent.getStringExtra(EXTRA_CLIP_ID)?.let(module::sendAgain)
            }

            ACTION_CANCEL_TRANSFER -> {
                val pairId = intent.getStringExtra(EXTRA_PAIR_ID)
                val transferId = intent.getStringExtra(EXTRA_TRANSFER_ID)
                if (pairId != null && transferId != null) {
                    module.cancelTransfer(pairId, transferId, intent.getBooleanExtra(EXTRA_SENDING, false))
                }
            }
        }
    }

    companion object {
        private const val ACTION_SEND_ANYWAY = "app.handlive.android.clipboard.SEND_ANYWAY"
        private const val ACTION_SEND_AGAIN = "app.handlive.android.clipboard.SEND_AGAIN"
        private const val ACTION_CANCEL_TRANSFER = "app.handlive.android.clipboard.CANCEL_TRANSFER"
        private const val EXTRA_CLIP_ID = "clip_id"
        private const val EXTRA_PAIR_ID = "pair_id"
        private const val EXTRA_TRANSFER_ID = "transfer_id"
        private const val EXTRA_SENDING = "sending"

        fun sendAnyway(context: Context): PendingIntent = broadcast(context, Intent(ACTION_SEND_ANYWAY), 0)

        fun sendAgain(
            context: Context,
            clipId: String,
        ): PendingIntent = broadcast(context, Intent(ACTION_SEND_AGAIN).putExtra(EXTRA_CLIP_ID, clipId), 0)

        fun cancelTransfer(
            context: Context,
            pairId: String,
            transferId: String,
            sending: Boolean,
        ): PendingIntent =
            broadcast(
                context,
                Intent(ACTION_CANCEL_TRANSFER)
                    .putExtra(EXTRA_PAIR_ID, pairId)
                    .putExtra(EXTRA_TRANSFER_ID, transferId)
                    .putExtra(EXTRA_SENDING, sending),
                transferId.hashCode(),
            )

        private fun broadcast(
            context: Context,
            intent: Intent,
            requestCode: Int,
        ): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                intent.setClass(context, ClipActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
