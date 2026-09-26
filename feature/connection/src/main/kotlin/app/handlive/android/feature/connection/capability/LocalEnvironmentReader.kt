package app.handlive.android.feature.connection.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import app.handlive.android.core.protocol.capability.SimInfo

/**
 * Reads the [LocalEnvironment] of this phone: app version, Android version, model, the runtime permissions of
 * SET-01 API 2, telephony and the SIMs.
 */
class LocalEnvironmentReader(
    context: Context,
    private val sims: SimDirectory = SimDirectory(context),
) {
    private val appContext = context.applicationContext

    fun read(accessibilityServiceRunning: Boolean): LocalEnvironment {
        val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        val notificationsMissing =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !granted(Manifest.permission.POST_NOTIFICATIONS)
        return LocalEnvironment(
            appVersion = "${packageInfo.versionName} (${packageInfo.longVersionCode})",
            osVersion = Build.VERSION.RELEASE,
            model = Build.MODEL,
            accessibilityServiceRunning = accessibilityServiceRunning,
            notificationsMissing = notificationsMissing,
            telephony = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            missingPermissions =
                AndroidPermissions.SMS
                    .filterNot { granted(AndroidPermissions.fullName(it)) }
                    .toSet(),
            sims = sims.activeSims().map { SimInfo(it.subId, it.slot, it.label) },
            defaultSmsSubId = sims.defaultSmsSubId(),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

/** Short names of the runtime permissions per feature (SET-01 API 2), as `permissions_missing` lists them. */
object AndroidPermissions {
    const val READ_SMS = "READ_SMS"
    const val SEND_SMS = "SEND_SMS"
    const val READ_CONTACTS = "READ_CONTACTS"
    const val READ_PHONE_STATE = "READ_PHONE_STATE"

    /** What the SMS feature asks for, in one request (SET-01 API 2 example). */
    val SMS = listOf(READ_SMS, SEND_SMS, READ_CONTACTS, READ_PHONE_STATE)

    /** `android.permission.READ_SMS`: the form of `details.permission` in `PERMISSION_MISSING` (SMS-01 E2). */
    fun fullName(short: String): String = "android.permission.$short"
}
