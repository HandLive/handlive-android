package app.handlive.android.feature.connection

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Starts [HandLiveService] (SET-01 step 5, API 3). */
object ServiceLauncher {
    /**
     * `false` when Android refuses to start a foreground service now (`ForegroundServiceStartNotAllowedException`
     * from the background, or a missing type permission): the UI retries when it is in the foreground (SET-01 E2).
     */
    fun start(context: Context): Boolean {
        ConnectionRuntime.get(context).markLaunch(accepted = true)
        val started =
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, HandLiveService::class.java))
            }.isSuccess
        if (!started) ConnectionRuntime.get(context).markLaunch(accepted = false)
        return started
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, HandLiveService::class.java))
    }
}
