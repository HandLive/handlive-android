package app.handlive.android.feature.sms.system

import android.telephony.PhoneNumberUtils
import app.handlive.android.feature.sms.SmsConstants
import app.handlive.android.feature.sms.provider.AddressNormalizer

/**
 * [AddressNormalizer] on Android's own copy of libphonenumber: `PhoneNumberUtils.formatNumberToE164` parses the
 * number in the SIM's region and formats it only when libphonenumber considers it valid. No extra dependency.
 */
class PhoneNumberNormalizer : AddressNormalizer {
    override fun e164OrSelf(
        address: String,
        countryIso: String?,
    ): String = e164(address, countryIso) ?: address

    override fun recipient(
        address: String,
        countryIso: String?,
    ): String? =
        when {
            SmsConstants.SHORT_CODE.matches(address) -> address
            else -> e164(address, countryIso)
        }

    private fun e164(
        address: String,
        countryIso: String?,
    ): String? {
        if (address.isBlank() || address.any { it.isLetter() } || SmsConstants.SHORT_CODE.matches(address)) return null
        return runCatching { PhoneNumberUtils.formatNumberToE164(address, countryIso.orEmpty()) }.getOrNull()
    }
}
