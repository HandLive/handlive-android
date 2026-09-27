package app.handlive.android.feature.relay

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.CancellationException

/**
 * Runs [block]; a network, relay or database failure is dropped, because every relay task runs again at the next
 * occasion (service start, network change, relay switched on, new link).
 */
internal suspend fun attempt(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (
        @Suppress("TooGenericExceptionCaught") _: Exception,
    ) {
        // Tried again at the next occasion.
    }
}

/** Calls [onAvailable] whenever a default network comes up (CONN-02 backoff rule, CONN-04 E2 retries). */
internal fun Context.watchDefaultNetwork(onAvailable: () -> Unit) {
    val connectivity = getSystemService(ConnectivityManager::class.java) ?: return
    runCatching {
        connectivity.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable()
            },
        )
    }
}

/** `app_version` of `POST /v1/devices` (CONN-03 API 1): "1.2.0 (120)". */
internal fun Context.appVersion(): String {
    val info = packageManager.getPackageInfo(packageName, 0)
    return "${info.versionName} (${info.longVersionCode})"
}
