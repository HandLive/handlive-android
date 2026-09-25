package app.handlive.android.feature.pairing

import android.content.Context
import android.os.Build
import android.provider.Settings
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.pairing.devices.DeviceListItem
import app.handlive.android.feature.pairing.devices.DeviceListModel
import app.handlive.android.feature.pairing.exchange.LocalPairingDevice
import app.handlive.android.feature.pairing.exchange.PairingCoordinator
import app.handlive.android.feature.pairing.revoke.UnpairController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The pairing feature of this process: the coordinator serves `/v1/pair`, the unpair controller handles
 * `pair/revoke` and `session/bye {revoked}`, and the device list joins pairs with sessions. [install] runs once at
 * process start, before the connection service can start.
 */
class PairingFeature private constructor(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val data = HandLiveData.get(appContext)
    private val runtime = ConnectionRuntime.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val coordinator =
        PairingCoordinator(
            local = ::localDevice,
            pairs = data.pairs,
            advertise = runtime::setPairingAdvert,
            scope = scope,
        )

    val unpair = UnpairController(data.pairs, { runtime.sessions.value }, runtime::closeSession)

    val devices: Flow<List<DeviceListItem>> =
        DeviceListModel.observe(
            data.pairs.observeActive(),
            runtime.sessions,
            data.settings.settings.map {
                it.clipboardEnabled
            },
        )

    private suspend fun localDevice(): LocalPairingDevice? {
        val tlsSha256 = runtime.certificateSha256 ?: return null
        val identity = withContext(Dispatchers.IO) { data.identity }
        return LocalPairingDevice(identity, phoneName(), Build.MODEL, Build.VERSION.RELEASE, tlsSha256)
    }

    /** PAIR-01 field 9: the name the user gave the phone (`Settings.Global.DEVICE_NAME`), else the model. */
    private fun phoneName(): String =
        Settings.Global
            .getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_NAME)
            ?: Build.MODEL

    companion object {
        private const val MAX_NAME = 64

        @Volatile
        private var instance: PairingFeature? = null

        fun get(context: Context): PairingFeature =
            instance ?: synchronized(this) {
                instance ?: PairingFeature(context).also { instance = it }
            }

        /** Connects the feature to the connection runtime. */
        fun install(context: Context) {
            val feature = get(context)
            feature.runtime.pairingEndpoint = feature.coordinator
            feature.runtime.router.register(MessageType.PAIR, feature.unpair.handler)
            feature.runtime.sessionEnded
                .onEach(feature.unpair::onSessionEnded)
                .launchIn(feature.scope)
        }
    }
}
