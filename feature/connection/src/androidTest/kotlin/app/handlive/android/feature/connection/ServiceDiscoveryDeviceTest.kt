package app.handlive.android.feature.connection

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.handlive.android.feature.connection.discovery.NsdMdnsRegistrar
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a real phone on Wi-Fi (card A1.1): the runtime starts the TLS server on 47800–47809 and `_handlive._tcp`
 * becomes discoverable with TXT `v=1` within 2 s — the same browse a Mac runs (CONN-01 API 2).
 *
 * `./gradlew :feature:connection:connectedDebugAndroidTest` with a device on Wi-Fi.
 */
@RunWith(AndroidJUnit4::class)
class ServiceDiscoveryDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runtime = ConnectionRuntime.get(context)

    @After
    fun tearDown() = runBlocking { runtime.stop() }

    @Test
    fun runtimeIsDiscoverableWithVersionTxtWithinTwoSeconds() =
        runBlocking {
            runtime.start()
            assertEquals(ServiceState.RUNNING, runtime.state.value)
            val found = CompletableDeferred<NsdServiceInfo>()
            val nsd = context.getSystemService(NsdManager::class.java)
            val listener = discoveryListener(nsd, found)
            nsd.discoverServices(NsdMdnsRegistrar.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            try {
                val service = withTimeout(DISCOVERY_BUDGET_MILLIS) { found.await() }
                assertTrue(service.serviceName.startsWith("HL-"))
                assertTrue(service.port in 47_800..47_809)
                assertEquals("1", service.attributes["v"]?.toString(Charsets.UTF_8))
            } finally {
                nsd.stopServiceDiscovery(listener)
            }
        }

    /** `resolveService` (deprecated in API 34) is the one resolver every supported version has. */
    private fun discoveryListener(
        nsd: NsdManager,
        found: CompletableDeferred<NsdServiceInfo>,
    ) = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            if (!serviceInfo.serviceName.startsWith("HL-")) return
            nsd.resolveService(
                serviceInfo,
                object : NsdManager.ResolveListener {
                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                        found.complete(resolved)
                    }

                    override fun onResolveFailed(
                        info: NsdServiceInfo,
                        errorCode: Int,
                    ) = Unit
                },
            )
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit

        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

        override fun onStartDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit

        override fun onStopDiscoveryFailed(
            serviceType: String,
            errorCode: Int,
        ) = Unit
    }

    private companion object {
        const val DISCOVERY_BUDGET_MILLIS = 2_000L
    }
}
