package app.handlive.android.feature.connection.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.ConnectedPeer
import app.handlive.android.core.design.R as DesignR

/**
 * The ongoing notification of A-SVC (SET-01 field 5, CONN-01 field 6): no title of its own (Android shows the app
 * name), "Waiting for a connection", "Connected to <client>" or "Connected to N devices", and the "Send Clipboard"
 * button (CLIP-01 field 4).
 */
object ServiceNotification {
    const val ID = 1

    fun build(
        context: Context,
        peers: List<ConnectedPeer>,
        openApp: PendingIntent?,
        sendClipboard: PendingIntent?,
    ): Notification {
        val text =
            when (peers.size) {
                0 -> {
                    context.getString(R.string.notification_service_waiting)
                }

                1 -> {
                    context.getString(R.string.notification_service_connected_to, peers.single().peerName)
                }

                else -> {
                    context.resources.getQuantityString(
                        R.plurals.notification_service_connected_count,
                        peers.size,
                        peers.size,
                    )
                }
            }
        return NotificationCompat
            .Builder(context, NotificationChannels.SERVICE)
            .setSmallIcon(DesignR.drawable.ic_symbol_sensors)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp)
            .apply {
                sendClipboard?.let {
                    addAction(DesignR.drawable.ic_symbol_content_copy, context.getString(R.string.clipboard_send), it)
                }
            }.build()
    }
}
