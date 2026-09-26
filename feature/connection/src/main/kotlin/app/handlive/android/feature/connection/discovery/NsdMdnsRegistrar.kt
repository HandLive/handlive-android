package app.handlive.android.feature.connection.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** [MdnsRegistrar] on `NsdManager.registerService` (CONN-01 API 1). The system may rename the instance on a clash. */
class NsdMdnsRegistrar(
    context: Context,
) : MdnsRegistrar {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    override suspend fun register(registration: MdnsRegistration): Boolean {
        val result = CompletableDeferred<Boolean>()
        val info =
            NsdServiceInfo().apply {
                serviceName = registration.serviceName
                serviceType = SERVICE_TYPE
                port = registration.port
                registration.txt.attributes().forEach { (key, value) -> setAttribute(key, value) }
            }
        val callback =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    result.complete(true)
                }

                override fun onRegistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) {
                    result.complete(false)
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(
                    serviceInfo: NsdServiceInfo,
                    errorCode: Int,
                ) = Unit
            }
        val registered =
            runCatching {
                nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, callback)
                withTimeoutOrNull(REGISTER_TIMEOUT_MILLIS) { result.await() } ?: false
            }.getOrDefault(false)
        if (registered) listener = callback else runCatching { nsd.unregisterService(callback) }
        return registered
    }

    override suspend fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_handlive._tcp"
        private const val REGISTER_TIMEOUT_MILLIS = 10_000L
    }
}
