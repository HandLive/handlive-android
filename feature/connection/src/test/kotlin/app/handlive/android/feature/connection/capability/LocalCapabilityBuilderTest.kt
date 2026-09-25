package app.handlive.android.feature.connection.capability

import app.handlive.android.core.data.settings.HandLiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SET-02 API 1: settings and Android state map onto `capability` (0.7.2). */
class LocalCapabilityBuilderTest {
    private val environment =
        LocalEnvironment(
            appVersion = "1.0.0 (100)",
            osVersion = "15",
            model = "Pixel 8",
            accessibilityServiceRunning = true,
            notificationsMissing = false,
        )

    @Test
    fun defaultsAdvertiseTheClipboardWithImagesAndTheRelay() {
        val capability = LocalCapabilityBuilder.build(HandLiveSettings(), environment)
        assertEquals(1, capability.protocol)
        assertEquals("android", capability.platform)
        assertEquals("1.0.0 (100)", capability.appVersion)
        val clipboard = capability.features.clipboard!!
        assertTrue(clipboard.enabled)
        assertEquals(true, clipboard.autoSend)
        assertEquals(1_048_576L, clipboard.maxTextBytes)
        assertEquals(10_485_760L, clipboard.maxImageBytes)
        assertEquals(listOf("text/plain", "image/png", "image/jpeg"), clipboard.mimes)
        assertEquals(true, capability.features.relay?.enabled)
        assertEquals(emptyList<String>(), capability.permissionsMissing)
    }

    @Test
    fun featuresPhaseOneDoesNotImplementAreAbsent() {
        val features = LocalCapabilityBuilder.build(HandLiveSettings(), environment).features
        assertNull(features.sms)
        assertNull(features.call)
        assertNull(features.callAudio)
        assertNull(features.camera)
    }

    @Test
    fun autoSendNeedsTheSettingAndTheRunningAccessibilityService() {
        val stopped = environment.copy(accessibilityServiceRunning = false)
        assertEquals(
            false,
            LocalCapabilityBuilder
                .build(HandLiveSettings(), stopped)
                .features.clipboard
                ?.autoSend,
        )
        val off = HandLiveSettings(clipAutoSend = false)
        assertEquals(
            false,
            LocalCapabilityBuilder
                .build(off, environment)
                .features.clipboard
                ?.autoSend,
        )
    }

    @Test
    fun switchesMapToEnabledMimesRelayAndMissingPermissions() {
        val settings = HandLiveSettings(clipboardEnabled = false, clipSendImages = false, relayEnabled = false)
        val capability = LocalCapabilityBuilder.build(settings, environment.copy(notificationsMissing = true))
        assertFalse(capability.features.clipboard!!.enabled)
        assertEquals(listOf("text/plain"), capability.features.clipboard!!.mimes)
        assertEquals(false, capability.features.relay?.enabled)
        assertEquals(listOf("POST_NOTIFICATIONS"), capability.permissionsMissing)
    }
}
