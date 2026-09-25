package app.handlive.android.ui.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.IntentCompat
import androidx.core.net.toUri

/** The system pages SET-01 and SET-02 lead to; each falls back when a device lacks the page. */
object SystemPages {
    private const val XIAOMI_PACKAGE = "com.miui.securitycenter"
    private const val XIAOMI_AUTOSTART = "com.miui.permcenter.autostart.AutoStartManagementActivity"

    /** SET-01 E1: the app's notification settings. */
    fun notificationSettings(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** SET-01 API 4: the battery optimization exemption dialog. */
    fun batteryExemption(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** API 4 logic 2: the list, when the dialog does not exist. */
    fun batterySettings(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** SET-01 E5 and the fallback of every page: HandLive's App info. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** SET-01 field 7: "Pause app activity if unused". */
    fun unusedAppRestrictions(context: Context): Intent =
        IntentCompat.createManageUnusedAppRestrictionsIntent(context, context.packageName)

    /** SET-01 API 5: Xiaomi's autostart screen; OPPO and Samsung use App info. */
    fun manufacturerAutostart(
        context: Context,
        manufacturer: Manufacturer,
    ): Intent =
        if (manufacturer == Manufacturer.XIAOMI) {
            Intent().setComponent(ComponentName(XIAOMI_PACKAGE, XIAOMI_AUTOSTART))
        } else {
            appDetails(context)
        }

    /** Opens [intent]; a missing or protected page falls back to [fallback], then to App info. */
    fun open(
        context: Context,
        intent: Intent,
        fallback: Intent? = null,
    ) {
        listOfNotNull(intent, fallback, appDetails(context)).any { candidate ->
            runCatching { context.startActivity(candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        }
    }
}
