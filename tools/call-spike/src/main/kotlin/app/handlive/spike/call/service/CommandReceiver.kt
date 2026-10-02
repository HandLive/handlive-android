package app.handlive.spike.call.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * adb commands for the spike, sent while the probe is in the background (the receiver requires `DUMP`, which only
 * the shell and the system hold):
 *
 * `adb shell am broadcast -n app.handlive.spike.call/.service.CommandReceiver --es cmd <cmd> [--es key <hash>] [--ei n <i>]`
 *
 * `list`, `answer`, `decline`, `hangup` (the `CallStyle` intents of the newest call notification or of `key`),
 * `action` (ordinary action `n` of `key`), `devices`, `route`, `route_mode`, `unroute`.
 */
class CommandReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val command = intent.getStringExtra(EXTRA_CMD) ?: return
        val key = intent.getStringExtra(EXTRA_KEY)
        val router = AudioRouter.get(context)
        when (command) {
            "list" -> list(context)
            "answer" -> sendCallStyle(context, key, CallNotificationsService.EXTRA_ANSWER_INTENT, command)
            "decline" -> sendCallStyle(context, key, CallNotificationsService.EXTRA_DECLINE_INTENT, command)
            "hangup" -> sendCallStyle(context, key, CallNotificationsService.EXTRA_HANG_UP_INTENT, command)
            "action" -> sendAction(context, key, intent.getIntExtra(EXTRA_N, 0))
            "devices" -> router.logDevices()
            "route" -> router.routeToBluetooth(takeMode = false)
            "route_mode" -> router.routeToBluetooth(takeMode = true)
            "unroute" -> router.clear()
            else -> SpikeLog.write(context, listOf("ev" to "unknown_cmd", "cmd" to command))
        }
    }

    private fun list(context: Context) {
        CallRegistry.summary().forEach { (keyHash, call) ->
            SpikeLog.write(
                context,
                listOf(
                    "ev" to "entry",
                    "key" to keyHash,
                    "pkg" to call?.packageName,
                    "phase" to call?.phase?.name?.lowercase(),
                    "actions" to call?.actionTitles,
                ),
            )
        }
    }

    private fun sendCallStyle(
        context: Context,
        key: String?,
        extra: String,
        what: String,
    ) {
        val (keyHash, notification) = CallRegistry.find(key) ?: return missing(context, what, "no_notification")
        val pending =
            notification.notification.extras.pendingIntent(extra) ?: return missing(context, what, "no_intent")
        IntentSender.send(context, pending, what, keyHash)
    }

    private fun sendAction(
        context: Context,
        key: String?,
        index: Int,
    ) {
        val (keyHash, notification) = CallRegistry.find(key) ?: return missing(context, "action", "no_notification")
        val action =
            notification.notification.actions?.getOrNull(index) ?: return missing(context, "action", "no_action")
        IntentSender.send(context, action.actionIntent, "action:$index", keyHash)
    }

    private fun missing(
        context: Context,
        what: String,
        error: String,
    ) {
        SpikeLog.write(context, listOf("ev" to "sent", "what" to what, "ok" to false, "error" to error))
    }

    private fun Bundle.pendingIntent(key: String): PendingIntent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, PendingIntent::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelable(key)
        }

    private companion object {
        const val EXTRA_CMD = "cmd"
        const val EXTRA_KEY = "key"
        const val EXTRA_N = "n"
    }
}
