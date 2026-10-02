package app.handlive.spike.call.service

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * Spike question 2: sends a calling app's own PendingIntent (answer, decline, hang-up or an ordinary action) while
 * the probe is in the background. Activity starts need the sender to opt in to background activity starts
 * (API 34+), so every send carries that option; whether Android honours it is what the log records.
 */
object IntentSender {
    fun send(
        context: Context,
        pending: PendingIntent,
        what: String,
        keyHash: String,
    ) {
        CallRegistry.markCommand(what)
        val options = backgroundStartOptions()
        val result = runCatching { pending.send(context, 0, null, null, null, null, options) }
        SpikeLog.write(
            context,
            listOf(
                "ev" to "sent",
                "what" to what,
                "key" to keyHash,
                "ok" to result.isSuccess,
                "error" to result.exceptionOrNull()?.javaClass?.simpleName,
                "bal_option" to (options != null),
                "creator" to pending.creatorPackage,
            ),
        )
    }

    private fun backgroundStartOptions(): Bundle? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        val mode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else {
                @Suppress("DEPRECATION")
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            }
        return ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(mode).toBundle()
    }
}
