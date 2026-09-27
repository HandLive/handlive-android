package app.handlive.android.ui.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.ui.settings.SmsAccessState

/** Reads [SmsAccessState] from Android; read again on every resume and after every permission request. */
object SmsAccessReader {
    /**
     * [requested] = `perm.requested`: a permission asked before, still missing and without a rationale is denied
     * for good (SET-01 step 10) — never asked and denied for good both have no rationale.
     */
    fun read(
        context: Context,
        requested: Set<String>,
    ): SmsAccessState {
        val activity = context.findActivity()
        val missing = AndroidPermissions.SMS.filterNot { granted(context, it) }.toSet()
        val deniedForGood =
            missing
                .filter { it in requested }
                .filterNot { permission ->
                    activity != null &&
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            activity,
                            AndroidPermissions.fullName(permission),
                        )
                }.toSet()
        return SmsAccessState(
            telephony = context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
            missing = missing,
            deniedForGood = deniedForGood,
        )
    }

    private fun granted(
        context: Context,
        permission: String,
    ) = ContextCompat.checkSelfPermission(context, AndroidPermissions.fullName(permission)) ==
        PackageManager.PERMISSION_GRANTED
}

/** The activity behind a Compose context, for the permission rationale. */
tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
