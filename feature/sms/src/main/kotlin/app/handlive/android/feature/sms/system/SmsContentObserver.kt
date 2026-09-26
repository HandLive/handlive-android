package app.handlive.android.feature.sms.system

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony

/**
 * `ContentObserver` on `content://sms` and its children (SMS-02 API 3): `onChange` carries no data (and its `uri`
 * may be `null`), so it only signals; the module queries by `last_sms_id`. No `RECEIVE_SMS` (C18).
 */
class SmsContentObserver(
    private val resolver: ContentResolver,
    private val onChange: () -> Unit,
) {
    private val observer =
        object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(
                selfChange: Boolean,
                uri: Uri?,
            ) = onChange()
        }
    private var registered = false

    /** Registers once `feature.sms` is on and `READ_SMS` granted; `false` if the provider refused. */
    fun register(): Boolean {
        if (registered) return true
        registered =
            runCatching { resolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer) }.isSuccess
        return registered
    }

    /** The feature was turned off or the permission lost (E6). */
    fun unregister() {
        if (!registered) return
        runCatching { resolver.unregisterContentObserver(observer) }
        registered = false
    }
}
