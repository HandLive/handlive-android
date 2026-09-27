package app.handlive.android.feature.call.system

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * A `ContentObserver` on [uri] and its children that only signals: `onChange` carries no data (its `uri` may be
 * `null`), so the module always queries again (CALL-04 API 3 logic 3). Used for the call log and for the contacts
 * (the name cache is dropped when they change).
 */
class UriObserver(
    private val resolver: ContentResolver,
    private val uri: Uri,
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

    /** `false` when the provider refused (the permission was revoked meanwhile). */
    @Synchronized
    fun register(): Boolean {
        if (!registered) {
            registered = runCatching { resolver.registerContentObserver(uri, true, observer) }.isSuccess
        }
        return registered
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        runCatching { resolver.unregisterContentObserver(observer) }
        registered = false
    }
}
