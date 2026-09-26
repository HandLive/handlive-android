package app.handlive.android.feature.sms.system

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.sms.module.SmsAccess
import kotlinx.coroutines.flow.StateFlow

/** [SmsAccess] from the settings (`feature.sms`), `FEATURE_TELEPHONY` and the live runtime permissions. */
class AndroidSmsAccess(
    context: Context,
    private val settings: StateFlow<HandLiveSettings>,
) : SmsAccess {
    private val appContext = context.applicationContext
    private val telephony = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun enabled(): Boolean = telephony && settings.value.smsEnabled

    override fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, AndroidPermissions.fullName(permission)) ==
            PackageManager.PERMISSION_GRANTED
}
