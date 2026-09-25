package app.handlive.android.feature.pairing.devices

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.pairing.PairedDevice
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityFeatures
import app.handlive.android.core.protocol.capability.ClipboardFeature
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/** PAIR-02 rows: link from the open session, version and features from the last capability. */
class DeviceListModelTest {
    private val device =
        PairedDevice(
            pairId = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
            peerDeviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
            peerName = "MacBook của Lan",
            peerPlatform = PeerPlatform.MACOS,
            peerModel = "Mac15,3",
            featuresJson =
                """{"protocol":1,"app_version":"1.0.0 (100)","platform":"macos","os_version":"15.1",""" +
                    """"model":"Mac15,3","features":{"clipboard":{"enabled":true}}}""",
            createdAt = 1,
            lastSeenAt = 2,
            safetyCode = "7f3a9c21",
        )

    @Test
    fun disconnectedRowShowsTheLastKnownVersionAndSecurityCode() {
        val item = DeviceListModel.item(device, session = null, clipboardEnabledHere = true)
        assertEquals(DeviceLink.DISCONNECTED, item.link)
        assertEquals("1.0.0 (100)", item.appVersion)
        assertEquals(ClipboardAvailability.UNKNOWN, item.clipboard)
        assertEquals("7f3a9c21", item.safetyCode)
    }

    @Test
    fun connectedRowUsesTheSessionsChannelAndEffectiveFeatures() {
        val item = DeviceListModel.item(device, session(setOf(Feature.CLIPBOARD), clipboardOnPeer = true), true)
        assertEquals(DeviceLink.WIFI, item.link)
        assertEquals(ClipboardAvailability.ON, item.clipboard)
    }

    @Test
    fun clipboardOffOnEitherSideIsExplained() {
        assertEquals(
            ClipboardAvailability.OFF_ON_PEER,
            DeviceListModel.item(device, session(emptySet(), clipboardOnPeer = false), true).clipboard,
        )
        assertEquals(
            ClipboardAvailability.OFF_HERE,
            DeviceListModel.item(device, session(emptySet(), clipboardOnPeer = true), false).clipboard,
        )
    }

    private fun session(
        effective: Set<Feature>,
        clipboardOnPeer: Boolean,
    ) = PeerSession(
        peer = PeerSession.PeerInfo(device.pairId, device.peerDeviceId, device.peerName, device.peerPlatform),
        channel = PeerSession.Channel.LAN,
        effectiveFeatures = MutableStateFlow(effective),
        peerCapability =
            MutableStateFlow(
                CapabilityData(
                    protocol = 1,
                    appVersion = "1.0.1 (101)",
                    platform = "macos",
                    osVersion = "15.1",
                    model = "Mac15,3",
                    features = CapabilityFeatures(clipboard = ClipboardFeature(enabled = clipboardOnPeer)),
                ),
            ),
        sender = { _, _, _ -> },
        clock = { 0 },
    )
}
