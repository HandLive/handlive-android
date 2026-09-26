package app.handlive.android.feature.connection.capability

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.SimInfo
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
        val capability = LocalCapabilityBuilder.build(HandLiveSettings(clipA11yConsentAt = CONSENTED_AT), environment)
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
    fun featuresThePhoneDoesNotImplementYetAreAbsent() {
        val features = LocalCapabilityBuilder.build(HandLiveSettings(), environment).features
        assertNull(features.call)
        assertNull(features.callAudio)
        assertNull(features.camera)
    }

    @Test
    fun smsFollowsTheSettingTelephonyPermissionsAndSims() {
        val sims = listOf(SimInfo(1, 0, "SIM 1"), SimInfo(2, 1, "Viettel"))
        val phone = environment.copy(telephony = true, sims = sims, defaultSmsSubId = 1)
        val sms = LocalCapabilityBuilder.build(HandLiveSettings(), phone).features.sms!!
        assertTrue(sms.enabled)
        assertEquals(true, sms.canSend)
        assertEquals(sims, sms.sims)
        assertEquals(1, sms.defaultSubId)

        val noSend = phone.copy(missingPermissions = setOf("SEND_SMS"), defaultSmsSubId = null)
        val limited = LocalCapabilityBuilder.build(HandLiveSettings(), noSend).features.sms!!
        assertEquals(false, limited.canSend)
        assertNull(limited.defaultSubId)

        val off = LocalCapabilityBuilder.build(HandLiveSettings(smsEnabled = false), phone).features.sms!!
        assertFalse(off.enabled)
        val tablet = LocalCapabilityBuilder.build(HandLiveSettings(), environment).features.sms!!
        assertFalse("no FEATURE_TELEPHONY (SET-01 step 8)", tablet.enabled)
    }

    @Test
    fun missingSmsPermissionsCountOnlyWhileSmsIsOn() {
        val missing = setOf("READ_PHONE_STATE", "READ_CONTACTS", "READ_SMS", "SEND_SMS")
        val phone = environment.copy(telephony = true, notificationsMissing = true, missingPermissions = missing)
        assertEquals(
            listOf("POST_NOTIFICATIONS", "READ_SMS", "SEND_SMS", "READ_CONTACTS", "READ_PHONE_STATE"),
            LocalCapabilityBuilder.build(HandLiveSettings(), phone).permissionsMissing,
        )
        assertEquals(
            listOf("POST_NOTIFICATIONS"),
            LocalCapabilityBuilder.build(HandLiveSettings(smsEnabled = false), phone).permissionsMissing,
        )
        assertEquals(
            listOf("READ_CONTACTS"),
            LocalCapabilityBuilder
                .build(
                    HandLiveSettings(),
                    phone.copy(notificationsMissing = false, missingPermissions = setOf("READ_CONTACTS")),
                ).permissionsMissing,
        )
    }

    @Test
    fun autoSendNeedsTheSettingTheConsentAndTheRunningAccessibilityService() {
        val stopped = environment.copy(accessibilityServiceRunning = false)
        assertEquals(
            false,
            LocalCapabilityBuilder
                .build(HandLiveSettings(clipA11yConsentAt = CONSENTED_AT), stopped)
                .features.clipboard
                ?.autoSend,
        )
        assertEquals(
            false,
            LocalCapabilityBuilder
                .build(HandLiveSettings(), environment)
                .features.clipboard
                ?.autoSend,
        )
        val off = HandLiveSettings(clipAutoSend = false, clipA11yConsentAt = CONSENTED_AT)
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

    private companion object {
        const val CONSENTED_AT = 1_727_150_000_000L
    }
}
