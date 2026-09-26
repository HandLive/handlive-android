package app.handlive.android.ui.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import java.util.Locale

/** Manufacturers with autostart instructions (SET-01 API 5 configuration table). */
enum class Manufacturer { XIAOMI, OPPO, SAMSUNG }

/** State of "Pause app activity if unused" (SET-01 field 7). */
enum class UnusedAppPause { ENABLED, DISABLED, NOT_AVAILABLE }

/** What SET-01 and Settings › Permissions & Background read from Android; read again in every `onResume`. */
data class PhoneEnvironment(
    /** Android 13+: `POST_NOTIFICATIONS` is a runtime permission (SET-01 field 3). */
    val notificationPermissionRuntime: Boolean,
    val notificationsAllowed: Boolean,
    /** SET-01 field 6. */
    val batteryExempt: Boolean,
    val manufacturer: Manufacturer?,
    val unusedAppPause: UnusedAppPause,
    /** Android 13+ and not installed from Google Play: the "Restricted setting" help comes first (API 6). */
    val restrictedSettingsLikely: Boolean,
)

object PhoneEnvironmentReader {
    private const val PLAY_STORE = "com.android.vending"

    fun read(
        context: Context,
        unusedAppPause: UnusedAppPause = UnusedAppPause.NOT_AVAILABLE,
    ): PhoneEnvironment {
        val runtime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val power = context.getSystemService(PowerManager::class.java)
        return PhoneEnvironment(
            notificationPermissionRuntime = runtime,
            notificationsAllowed =
                NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                    (!runtime || granted(context, Manifest.permission.POST_NOTIFICATIONS)),
            batteryExempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true,
            manufacturer = manufacturerOf(Build.MANUFACTURER),
            unusedAppPause = unusedAppPause,
            restrictedSettingsLikely = runtime && installer(context) != PLAY_STORE,
        )
    }

    fun manufacturerOf(name: String?): Manufacturer? =
        when (name?.lowercase(Locale.ROOT)) {
            "xiaomi", "redmi", "poco" -> Manufacturer.XIAOMI
            "oppo", "realme", "oneplus" -> Manufacturer.OPPO
            "samsung" -> Manufacturer.SAMSUNG
            else -> null
        }

    /** API 4 logic 3, read asynchronously: `API_30*`/`API_31` → enabled, `DISABLED`, else not available. */
    fun readUnusedAppPause(
        context: Context,
        onResult: (UnusedAppPause) -> Unit,
    ) {
        val future = PackageManagerCompat.getUnusedAppRestrictionsStatus(context)
        future.addListener(
            {
                val status = runCatching { future.get() }.getOrNull()
                onResult(
                    when (status) {
                        UnusedAppRestrictionsConstants.API_30,
                        UnusedAppRestrictionsConstants.API_30_BACKPORT,
                        UnusedAppRestrictionsConstants.API_31,
                        -> UnusedAppPause.ENABLED

                        UnusedAppRestrictionsConstants.DISABLED -> UnusedAppPause.DISABLED

                        else -> UnusedAppPause.NOT_AVAILABLE
                    },
                )
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    private fun granted(
        context: Context,
        permission: String,
    ) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun installer(context: Context): String? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                context.packageManager.getInstallerPackageName(context.packageName)
            }
        }.getOrNull()
}
