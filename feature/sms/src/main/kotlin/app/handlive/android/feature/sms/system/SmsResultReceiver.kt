package app.handlive.android.feature.sms.system

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.telephony.SmsMessage
import app.handlive.android.feature.sms.SmsFeature
import app.handlive.android.feature.sms.send.DeliveryReport

/**
 * Receives the "sent" and "delivered" result of every part (SMS-04 API 3): an internal, non-exported receiver
 * reached only by explicit broadcasts. Each (message, part, kind) has its own intent data, so no two PendingIntents
 * collide; they are mutable because the system fills in the result (the delivery report PDU).
 */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val localId = intent.getStringExtra(EXTRA_LOCAL_ID) ?: return
        val index = intent.getIntExtra(EXTRA_PART_INDEX, -1)
        val module = SmsFeature.get(context).module
        when (intent.action) {
            ACTION_SENT -> module.onSent(localId, index, resultCode)
            ACTION_DELIVERED -> report(intent)?.let { module.onDelivered(localId, index, it) }
        }
    }

    /** The status report PDU of the delivery intent (`pdu`, `format` extras); unreadable → no decision. */
    private fun report(intent: Intent): DeliveryReport? {
        val format = intent.getStringExtra(EXTRA_FORMAT) ?: SmsMessage.FORMAT_3GPP
        val message =
            intent.getByteArrayExtra(EXTRA_PDU)?.let { pdu ->
                runCatching { SmsMessage.createFromPdu(pdu, format) }.getOrNull()
            }
        return message?.let { DeliveryReport.of(it.status, threeGpp2 = format == SmsMessage.FORMAT_3GPP2) }
    }

    companion object {
        const val ACTION_SENT = "app.handlive.android.sms.SENT"
        const val ACTION_DELIVERED = "app.handlive.android.sms.DELIVERED"
        const val EXTRA_LOCAL_ID = "local_id"
        const val EXTRA_PART_INDEX = "part_index"
        const val EXTRA_PART_COUNT = "part_count"
        private const val EXTRA_PDU = "pdu"
        private const val EXTRA_FORMAT = "format"
        private const val SCHEME = "handlive-sms"

        fun sentIntent(
            context: Context,
            localId: String,
            index: Int,
            count: Int,
        ): PendingIntent = pendingIntent(context, ACTION_SENT, localId, index, count)

        fun deliveredIntent(
            context: Context,
            localId: String,
            index: Int,
            count: Int,
        ): PendingIntent = pendingIntent(context, ACTION_DELIVERED, localId, index, count)

        private fun pendingIntent(
            context: Context,
            action: String,
            localId: String,
            index: Int,
            count: Int,
        ): PendingIntent {
            val intent =
                Intent(action)
                    .setClass(context, SmsResultReceiver::class.java)
                    .setData(Uri.parse("$SCHEME://$action/$localId/$index"))
                    .putExtra(EXTRA_LOCAL_ID, localId)
                    .putExtra(EXTRA_PART_INDEX, index)
                    .putExtra(EXTRA_PART_COUNT, count)
            val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            return PendingIntent.getBroadcast(
                context,
                "$action/$localId/$index".hashCode(),
                intent,
                mutable or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
