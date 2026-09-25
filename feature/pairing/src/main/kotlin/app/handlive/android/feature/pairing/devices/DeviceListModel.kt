package app.handlive.android.feature.pairing.devices

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.PairedDevice
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.SerializationException

/** Link of a paired client as the Devices tab shows it (PAIR-02 fields 4–5, 0.11 seen from the phone). */
enum class DeviceLink { WIFI, INTERNET, USB, DISCONNECTED }

/** The clipboard row of the details (PAIR-02 field 8, SET-02 field 24). */
enum class ClipboardAvailability {
    /** Effective between the two devices. */
    ON,

    /** Turned off on the client ("Off on <device>"). */
    OFF_ON_PEER,

    /** Turned off on this phone. */
    OFF_HERE,

    /** Not connected: nothing is effective right now. */
    UNKNOWN,
}

/** One row and its details screen (PAIR-02 fields 1–10). No `pair_id`, keys or addresses are shown. */
data class DeviceListItem(
    val pairId: String,
    val name: String,
    val platform: PeerPlatform,
    val model: String?,
    val link: DeviceLink,
    val lastSeenAt: Long?,
    val appVersion: String?,
    val clipboard: ClipboardAvailability,
    val safetyCode: String,
)

/**
 * PAIR-02 on the phone: the stored pairs joined with the open sessions, so a row changes within a second of a
 * connection change without polling. The peer's app version and features come from its last capability
 * (`features_json`).
 */
object DeviceListModel {
    /** Rows update when a pair, a session, a session's capability or the local clipboard switch changes. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(
        devices: Flow<List<PairedDevice>>,
        sessions: StateFlow<Map<String, PeerSession>>,
        clipboardEnabledHere: Flow<Boolean>,
    ): Flow<List<DeviceListItem>> {
        val liveSessions =
            sessions.flatMapLatest { open ->
                if (open.isEmpty()) {
                    flowOf(open)
                } else {
                    combine(open.values.map { combine(it.effectiveFeatures, it.peerCapability) { _, _ -> } }) { open }
                }
            }
        return combine(devices, liveSessions, clipboardEnabledHere) { stored, open, clipboardHere ->
            stored.map { item(it, open[it.pairId], clipboardHere) }
        }
    }

    fun item(
        device: PairedDevice,
        session: PeerSession?,
        clipboardEnabledHere: Boolean,
    ): DeviceListItem {
        val capability = session?.peerCapability?.value ?: storedCapability(device.featuresJson)
        return DeviceListItem(
            pairId = device.pairId,
            name = device.peerName,
            platform = device.peerPlatform,
            model = device.peerModel,
            link = if (session == null) DeviceLink.DISCONNECTED else session.channel.toLink(),
            lastSeenAt = device.lastSeenAt,
            appVersion = capability?.appVersion,
            clipboard = clipboardAvailability(session, capability, clipboardEnabledHere),
            safetyCode = device.safetyCode,
        )
    }

    private fun clipboardAvailability(
        session: PeerSession?,
        peer: CapabilityData?,
        clipboardEnabledHere: Boolean,
    ): ClipboardAvailability =
        when {
            !clipboardEnabledHere -> ClipboardAvailability.OFF_HERE
            peer != null && peer.features.clipboard?.enabled != true -> ClipboardAvailability.OFF_ON_PEER
            session?.isEffective(Feature.CLIPBOARD) == true -> ClipboardAvailability.ON
            else -> ClipboardAvailability.UNKNOWN
        }

    private fun storedCapability(json: String): CapabilityData? =
        try {
            ProtocolJson.decodeFromString(CapabilityData.serializer(), json)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun PeerSession.Channel.toLink() =
        when (this) {
            PeerSession.Channel.LAN -> DeviceLink.WIFI
            PeerSession.Channel.RELAY -> DeviceLink.INTERNET
            PeerSession.Channel.USB -> DeviceLink.USB
        }
}
