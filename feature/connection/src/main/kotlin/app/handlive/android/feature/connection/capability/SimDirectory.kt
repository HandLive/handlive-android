package app.handlive.android.feature.connection.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import java.util.Locale

/** An active SIM (`features.sms.sims`, 0.7.2): its subscription, slot, the name the system shows, its country. */
data class SimCard(
    val subId: Int,
    val slot: Int,
    val label: String,
    /** ISO 3166 country of the SIM, uppercase (`VN`), for phone number normalization; `null` if unknown. */
    val countryIso: String?,
)

/**
 * The phone's SIMs (SMS-04 API 6): the active subscriptions need `READ_PHONE_STATE` (without it the list is empty,
 * SET-01 API 2); the default SMS subscription and the SIM country need no permission.
 */
class SimDirectory(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val subscriptions = appContext.getSystemService(SubscriptionManager::class.java)
    private val telephony = appContext.getSystemService(TelephonyManager::class.java)

    fun activeSims(): List<SimCard> {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val infos =
            try {
                subscriptions?.activeSubscriptionInfoList.orEmpty()
            } catch (_: SecurityException) {
                emptyList()
            }
        return infos.map { info ->
            SimCard(
                subId = info.subscriptionId,
                slot = info.simSlotIndex,
                label = (info.displayName ?: info.carrierName)?.toString().orEmpty(),
                countryIso = info.countryIso?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT),
            )
        }
    }

    /** `SmsManager.getDefaultSmsSubscriptionId()`; `null` when the phone asks every time. */
    fun defaultSmsSubId(): Int? =
        SmsManager.getDefaultSmsSubscriptionId().takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }

    /**
     * Country used to normalize numbers to E.164 (5.1.5 `addresses`, SMS-04 API 1 logic 2): the SIM's country, then
     * the network's, then the phone's region setting.
     */
    fun countryIso(subId: Int?): String? {
        val manager = subId?.let { telephony?.createForSubscriptionId(it) } ?: telephony
        return listOfNotNull(manager?.simCountryIso, manager?.networkCountryIso, Locale.getDefault().country)
            .firstOrNull { it.isNotBlank() }
            ?.uppercase(Locale.ROOT)
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
}
