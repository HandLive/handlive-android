package app.handlive.android.feature.connection.notification

import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.core.strings.R

/**
 * Android notification channels (SET-01 fields 5 and 17, CLIP-01 field 8: `IMPORTANCE_LOW`; CALL-05 API 5: the
 * "tap to answer" notification, `IMPORTANCE_HIGH` so that it appears while the phone is in a pocket). Names and
 * descriptions come from the catalog and follow the app language when recreated.
 */
object NotificationChannels {
    const val SERVICE = "hl_service"
    const val CLIPBOARD = "clipboard"
    const val PERMISSION = "permission"

    /** CALL-05 API 5: "Answer <app> Call", posted when the Mac answers an app call without the exemption (`tap`). */
    const val APP_CALL = "hl_app_call"

    /** Creates or renames the channels; safe to call at every start (the user's per-channel choices are kept). */
    fun createAll(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannelsCompat(
            listOf(
                channel(
                    context,
                    SERVICE,
                    R.string.notification_channel_service_name,
                    R.string.notification_channel_service_description,
                ).setShowBadge(false).build(),
                // IMPORTANCE_LOW (CLIP-01 field 8): silent; clipboard errors are reported in place (C19).
                channel(
                    context,
                    CLIPBOARD,
                    R.string.notification_channel_clipboard_name,
                    R.string.notification_channel_clipboard_description,
                ).build(),
                channel(
                    context,
                    PERMISSION,
                    R.string.notification_channel_permission_name,
                    R.string.notification_channel_permission_description,
                ).build(),
                // CALL-05 API 5: named like the setting, no description.
                channel(context, APP_CALL, R.string.settings_call_app_calls, null)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build(),
            ),
        )
    }

    private fun channel(
        context: Context,
        id: String,
        name: Int,
        description: Int?,
    ) = NotificationChannelCompat
        .Builder(id, NotificationManagerCompat.IMPORTANCE_LOW)
        .setName(context.getString(name))
        .setDescription(description?.let(context::getString))
}
