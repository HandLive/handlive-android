package app.handlive.android.feature.sms.system

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract
import app.handlive.android.feature.sms.provider.ContactNames

/**
 * `thread.display_name` from `ContactsContract.PhoneLookup` (SMS-01 step 5); needs `READ_CONTACTS`. Names are never
 * stored on disk or logged (API 1 logic 9).
 */
class PhoneLookupContactNames(
    private val resolver: ContentResolver,
) : ContactNames {
    override fun lookup(address: String): String? {
        if (address.isBlank()) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(address))
        return try {
            resolver.query(uri, PROJECTION, null, null, null)?.use { cursor ->
                cursor.takeIf { it.moveToFirst() }?.getString(0)?.takeIf { it.isNotBlank() }
            }
        } catch (_: SecurityException) {
            // READ_CONTACTS was revoked meanwhile (SMS-01 E3): the number is shown instead.
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private companion object {
        val PROJECTION = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
    }
}
