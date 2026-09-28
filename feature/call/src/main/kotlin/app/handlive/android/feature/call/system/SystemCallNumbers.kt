package app.handlive.android.feature.call.system

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.context.CallNumbers
import app.handlive.android.feature.call.context.NameCache

/**
 * [CallNumbers] on Android: E.164 through the platform's own libphonenumber (`PhoneNumberUtils.formatNumberToE164`)
 * for the SIM's country, as SMS-04 API 1 does; names from `ContactsContract.PhoneLookup` behind the 200-number LRU
 * cache of A-CALL, only while [contactsAllowed] (`READ_CONTACTS`, CALL-01 E2). Nothing is logged or stored on disk.
 */
class SystemCallNumbers(
    private val resolver: ContentResolver,
    private val countryIso: (subId: Int?) -> String?,
    private val contactsAllowed: () -> Boolean,
) : CallNumbers {
    private val cache = NameCache(CallConstants.NAME_CACHE_SIZE, ::lookup)

    override fun normalize(
        raw: String,
        subId: Int?,
    ): String {
        if (raw.isBlank() || raw.any { it.isLetter() }) return raw
        return runCatching { PhoneNumberUtils.formatNumberToE164(raw, countryIso(subId).orEmpty()) }.getOrNull() ?: raw
    }

    override fun name(number: String): String? = if (contactsAllowed()) cache.name(number) else null

    /** The contacts changed: names are looked up again. */
    fun forgetNames() = cache.clear()

    /** One `PhoneLookup` query, first row only (CALL-01 Query); a cache of its own for one `log_sync`. */
    fun lookup(number: String): String? {
        if (number.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(0)?.takeIf { it.isNotBlank() }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS was revoked meanwhile (E2): the number is shown instead.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val PROJECTION = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
    }
}
