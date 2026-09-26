package app.handlive.android.feature.connection.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Reads the [LocalEnvironment] of this phone: app version, Android version, model, notification permission. */
class LocalEnvironmentReader(
    context: Context,
) {
    private val appContext = context.applicationContext

    fun read(accessibilityServiceRunning: Boolean): LocalEnvironment {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val notificationsMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        return LocalEnvironment(
            appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
            osVersion = Build.VERSION.RELEASE,
            model = Build.MODEL,
            accessibilityServiceRunning = accessibilityServiceRunning,
            notificationsMissing = notificationsMissing,
        )
    }
}
