package app.handlive.android.feature.call.appcall

import android.content.Context
import android.os.SystemClock
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.feature.connection.capability.NotificationAccess

/**
 * [AppCallAccess] from the settings and Android: the settings are loaded, `feature.call` and `call.app_calls` are on
 * and HandLive has Notification access. Telephony is not needed (CALL-05). Before the settings are loaded nothing is
 * enabled. Granted Notification access is asked of Android again only after [ACCESS_CACHE_MILLIS], not for every
 * notification — a revoke also disconnects the listener, which ends the calls at once (E10); a missing one is asked
 * every time, so a grant counts at once.
 */
class AndroidAppCallAccess(
    context: Context,
    /** The settings once loaded; `null` before. */
    private val settings: () -> HandLiveSettings?,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val notificationAccess: (Context) -> Boolean = NotificationAccess::granted,
) : AppCallAccess {
    private val appContext = context.applicationContext

    /** When Notification access was last seen granted. */
    @Volatile
    private var grantedAt: Long? = null

    override fun enabled(): Boolean {
        val current = settings() ?: return false
        return current.callEnabled && current.callAppCalls && accessGranted()
    }

    private fun accessGranted(): Boolean {
        val now = clock()
        val last = grantedAt
        if (last != null && now - last < ACCESS_CACHE_MILLIS) return true
        return notificationAccess(appContext).also { grantedAt = if (it) now else null }
    }

    companion object {
        const val ACCESS_CACHE_MILLIS = 1_000L
    }
}

/**
 * The label of an app from `PackageManager`. From API 30 package visibility hides the other apps. Measured on the
 * API 35 emulator, Android makes an app visible to the notification listener once it posts a notification; the
 * manifest's launcher-intent `<queries>` keeps the calling apps visible on a version or OEM build that does not
 * (API 30–34 not measured). The package name when the label still cannot be read (an app still hidden, or one just
 * removed).
 */
class PackageAppLabels(
    context: Context,
) : AppLabels {
    private val packageManager = context.applicationContext.packageManager

    override fun label(packageName: String): String =
        try {
            packageManager.getApplicationInfo(packageName, 0).loadLabel(packageManager).toString()
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            packageName
        }
}
