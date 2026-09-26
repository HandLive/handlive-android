package app.handlive.android.feature.connection.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.feature.connection.PairingAdvert
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

/**
 * Advertises the phone on the LAN while A-SVC runs (CONN-01 API 1): instance `HL-<6 hex>` new at every start, TXT
 * `v`, the hourly hint of every active pair, and `pr`/`pm` during pairing. The hints are recomputed at each full
 * hour and whenever pairs change; a new default network re-registers (CONN-02 step 1).
 */
class DiscoveryAdvertising(
    context: Context,
    private val pairs: PairStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val appContext = context.applicationContext
    private val networkVersion = MutableStateFlow(0)
    private var advertiser: MdnsAdvertiser? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun start(
        scope: CoroutineScope,
        port: Int,
        pairingAdvert: Flow<PairingAdvert>,
    ) {
        val mdns = MdnsAdvertiser(NsdMdnsRegistrar(appContext), scope, clock).also { advertiser = it }
        val instanceName =
            INSTANCE_PREFIX + SecureRandomBytes.next(INSTANCE_BYTES).joinToString("") { "%02x".format(it) }
        combine(pairs.observeActive(), hourTicks(), pairingAdvert, networkVersion) { _, _, advert, network ->
            advert to
                network
        }.onEach { (advert, network) ->
            val hints = DiscoveryHints.forPairs(pairs.activeSecrets().mapNotNull { it.prk }, clock())
            mdns.advertise(
                MdnsRegistration(
                    instanceName,
                    port,
                    MdnsTxtRecord(hints, advert.pairingRequest, advert.pinMode),
                    network,
                ),
            )
        }.launchIn(scope)
        watchNetwork()
    }

    suspend fun stop() {
        networkCallback?.let { callback ->
            runCatching {
                appContext
                    .getSystemService(
                        ConnectivityManager::class.java,
                    ).unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        advertiser?.stop()
        advertiser = null
    }

    /** Emits the hour index now and again just after each hour boundary, when every hint changes. */
    private fun hourTicks(): Flow<Long> =
        flow {
            while (true) {
                emit(DiscoveryHints.hourIndex(clock()))
                delay(DiscoveryHints.millisUntilNextHour(clock()) + HOUR_MARGIN_MILLIS)
            }
        }

    /** Also writes the bench `net` events (`up`, `down`, `changed`) that start a reconnect measurement. */
    private fun watchNetwork() {
        val callback =
            object : ConnectivityManager.NetworkCallback() {
                private var current: Network? = null

                override fun onAvailable(network: Network) {
                    BenchLog.event(BenchEvent.NET, "change" to if (current == null) "up" else "changed")
                    current = network
                    networkVersion.update { it + 1 }
                }

                override fun onLost(network: Network) {
                    if (network == current) {
                        BenchLog.event(BenchEvent.NET, "change" to "down")
                        current = null
                    }
                }

                override fun onLinkPropertiesChanged(
                    network: Network,
                    linkProperties: LinkProperties,
                ) = networkVersion.update { it + 1 }
            }
        appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private companion object {
        const val INSTANCE_PREFIX = "HL-"
        const val INSTANCE_BYTES = 3
        const val HOUR_MARGIN_MILLIS = 1_000L
    }
}
