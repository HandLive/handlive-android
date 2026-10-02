package app.handlive.android.feature.call.appcall

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.SystemClock
import android.telecom.InCallService
import android.telecom.TelecomManager

/**
 * The packages whose notifications are cellular calls — followed by CALL-01…04 through the public call state APIs —
 * and never calls of another app (CALL-05): the default and the system dialer, every app with an `InCallService`,
 * Telecom and the phone process. The notification listener drops their notifications before anything is queued.
 */
fun interface DialerPackages {
    fun current(): Set<String>
}

/**
 * [DialerPackages] from `TelecomManager` and `PackageManager`, read only when a notification that may be a call comes,
 * and kept [CACHE_MILLIS] since the user can change the default dialer at any time. A system service that refuses
 * leaves out what it would have named; the fixed packages always stay.
 */
class AndroidDialerPackages(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : DialerPackages {
    private val appContext = context.applicationContext

    @Volatile
    private var cached: Cached? = null

    private class Cached(
        val packages: Set<String>,
        val readAt: Long,
    )

    override fun current(): Set<String> {
        val now = clock()
        val last = cached
        if (last != null && now - last.readAt < CACHE_MILLIS) return last.packages
        return read().also { cached = Cached(it, now) }
    }

    private fun read(): Set<String> =
        buildSet {
            addAll(TELEPHONY)
            val telecom = appContext.getSystemService(TelecomManager::class.java)
            runCatching { telecom?.defaultDialerPackage }.getOrNull()?.let(::add)
            runCatching { telecom?.systemDialerPackage }.getOrNull()?.let(::add)
            runCatching { inCallServices() }.getOrNull().orEmpty().mapNotNullTo(this) { it.serviceInfo?.packageName }
        }

    /** The apps that declare an `InCallService` (the manifest's `<queries>` makes them visible). */
    private fun inCallServices(): List<ResolveInfo> {
        val intent = Intent(InCallService.SERVICE_INTERFACE)
        val packageManager = appContext.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentServices(intent, 0)
        }
    }

    companion object {
        /** How long a read of the dialers holds. */
        const val CACHE_MILLIS = 10_000L

        /** Telecom and the phone process post the cellular call notifications on some phones. */
        val TELEPHONY = setOf("com.android.server.telecom", "com.android.phone")
    }
}
