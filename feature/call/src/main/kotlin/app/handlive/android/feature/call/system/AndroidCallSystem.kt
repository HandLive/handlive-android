package app.handlive.android.feature.call.system

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimLabels
import app.handlive.android.feature.call.log.AccountSubIds
import app.handlive.android.feature.call.module.CallAccess
import app.handlive.android.feature.call.module.CallTelecom
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.feature.connection.capability.SimDirectory
import kotlinx.coroutines.flow.StateFlow

/** [CallAccess] from the settings (`feature.call`), `FEATURE_TELEPHONY` and the live runtime permissions. */
class AndroidCallAccess(
    context: Context,
    private val settings: StateFlow<HandLiveSettings>,
) : CallAccess {
    private val appContext = context.applicationContext
    private val telephony = appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)

    override fun enabled(): Boolean = telephony && settings.value.callEnabled

    override fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, AndroidPermissions.fullName(permission)) ==
            PackageManager.PERMISSION_GRANTED
}

/**
 * [CallTelecom] on `TelecomManager` (CALL-02 API 2, 3): deprecated since API 29, still working (C12). The caller has
 * checked `ANSWER_PHONE_CALLS`; a `SecurityException` (revoked meanwhile) reaches it as `PERMISSION_MISSING`.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class AndroidTelecom(
    context: Context,
) : CallTelecom {
    private val telecom = context.applicationContext.getSystemService(TelecomManager::class.java)

    override fun acceptRingingCall() {
        checkNotNull(telecom) { "no Telecom" }.acceptRingingCall()
    }

    override fun endCall(): Boolean = checkNotNull(telecom) { "no Telecom" }.endCall()

    /** `isInCall()` covers ringing, dialing, active and held calls; `READ_PHONE_STATE` is already granted. */
    override fun phoneState(): PhoneState? =
        runCatching { telecom?.isInCall }.getOrNull()?.let { inCall -> if (inCall) null else PhoneState.IDLE }
}

/** `sim_label` (CALL-01 API 1): `SubscriptionInfo.getDisplayName()` of the call's SIM, only with more than one SIM. */
class DirectorySimLabels(
    private val sims: SimDirectory,
) : SimLabels {
    override fun label(subId: Int): String? {
        val active = sims.activeSims()
        return if (active.size >
            1
        ) {
            active.firstOrNull { it.subId == subId }?.label?.takeIf { it.isNotBlank() }
        } else {
            null
        }
    }
}

/**
 * `sub_id` of a call log row (CALL-04 `entry`): `TelephonyManager.getSubscriptionId(PhoneAccountHandle)` from
 * `PHONE_ACCOUNT_COMPONENT_NAME` and `PHONE_ACCOUNT_ID` (API 30+); API 29, or no mapping, gives `null`.
 */
class PhoneAccountSubIds(
    context: Context,
) : AccountSubIds {
    private val telephony = context.applicationContext.getSystemService(TelephonyManager::class.java)

    override fun subId(
        component: String?,
        id: String?,
    ): Int? {
        val name = component?.let(ComponentName::unflattenFromString)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || name == null || id == null) return null
        // Needs READ_PHONE_STATE, like every part of the call group; revoked meanwhile → no sub_id.
        val subId =
            try {
                telephony?.getSubscriptionId(PhoneAccountHandle(name, id))
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        return subId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
    }
}
