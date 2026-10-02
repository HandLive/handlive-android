package app.handlive.android.feature.connection.capability

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
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
                AndroidPermissions.RUNTIME
                    .filterNot { granted(AndroidPermissions.fullName(it)) }
                    .toSet(),
            sims = sims.activeSims().map { SimInfo(it.subId, it.slot, it.label) },
            defaultSmsSubId = sims.defaultSmsSubId(),
            notificationListenerMissing = !NotificationAccess.granted(appContext),
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}

/**
 * Notification access (`BIND_NOTIFICATION_LISTENER_SERVICE`, granted by hand in Settings): the user allowed HandLive's
 * notification listener of CALL-05 (`NotificationManager.isNotificationListenerAccessGranted`, CALL-05 API 3). It is a
 * special access, not a runtime permission, so [AndroidPermissions.NOTIFICATION_LISTENER] stands outside
 * [AndroidPermissions.RUNTIME].
 */
object NotificationAccess {
    /** The listener service (feature/call), named here so that this module does not depend on it. */
    const val LISTENER_CLASS = "app.handlive.android.feature.call.appcall.AppCallListenerService"

    fun granted(context: Context): Boolean =
        context
            .getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context.packageName, LISTENER_CLASS)) == true
}

/** Short names of the runtime permissions per feature (SET-01 API 2), as `permissions_missing` lists them. */
object AndroidPermissions {
    const val READ_SMS = "READ_SMS"
    const val SEND_SMS = "SEND_SMS"
    const val READ_CONTACTS = "READ_CONTACTS"
    const val READ_PHONE_STATE = "READ_PHONE_STATE"
    const val READ_CALL_LOG = "READ_CALL_LOG"
    const val ANSWER_PHONE_CALLS = "ANSWER_PHONE_CALLS"

    /** The special access "Notification access" of CALL-05, in `permissions_missing` like a permission. */
    const val NOTIFICATION_LISTENER = "NOTIFICATION_LISTENER"

    /** What the SMS feature asks for, in one request (SET-01 API 2 example). */
    val SMS = listOf(READ_SMS, SEND_SMS, READ_CONTACTS, READ_PHONE_STATE)

    /** What the calls feature asks for, in one request (SET-01 part B; public Telecom APIs only, C12). */
    val CALLS = listOf(READ_PHONE_STATE, READ_CALL_LOG, ANSWER_PHONE_CALLS, READ_CONTACTS)

    /** Every runtime permission of the features above, in the order of the SET-01 API 2 table. */
    val RUNTIME = listOf(READ_SMS, SEND_SMS, READ_CONTACTS, READ_PHONE_STATE, READ_CALL_LOG, ANSWER_PHONE_CALLS)

    /** `android.permission.READ_SMS`: the form of `details.permission` in `PERMISSION_MISSING` (SMS-01 E2). */
    fun fullName(short: String): String = "android.permission.$short"
}
