package app.handlive.android.ui.main

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.ui.settings.FeatureAccess
import app.handlive.android.ui.settings.PhoneFeature
import app.handlive.android.ui.settings.SettingsUiState
import app.handlive.android.ui.system.RestrictedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SET-01 steps 12 and N1: the "Restricted setting" guidance (field 14) comes before Accessibility and before
 * Notification access alike while Android may restrict them (API 6 logic 2), and switching calls from other apps on
 * shows the Notification access primer only while Calls are on too.
 */
class SpecialAccessRoutesTest {
    @Test
    fun theRestrictedSettingComesFirstForBothSpecialAccessesWhenAndroidMayBlockThem() {
        listOf(false, true).forEach { notificationAccess ->
            assertNull(restrictedSettingBefore(RestrictedSettings.NONE, notificationAccess))
            assertEquals(
                "Continue opens the system page",
                Route.RestrictedSetting(notificationAccess),
                restrictedSettingBefore(RestrictedSettings.LIKELY, notificationAccess),
            )
        }
    }

    @Test
    fun theNotificationAccessPrimerNeedsCallsOnToo() {
        val missing = SettingsUiState(notificationAccess = false)
        assertEquals(Route.NotificationAccess, primerOnSwitch(PhoneFeature.APP_CALLS, missing))

        val callsOff = missing.copy(settings = HandLiveSettings(callEnabled = false))
        assertNull("calls from other apps stay off with Calls off", primerOnSwitch(PhoneFeature.APP_CALLS, callsOff))

        assertNull("the access is there", primerOnSwitch(PhoneFeature.APP_CALLS, SettingsUiState()))
    }

    @Test
    fun theOtherPrimersFollowTheirPermissionsOnly() {
        val ui =
            SettingsUiState(
                settings = HandLiveSettings(callEnabled = false),
                sms = FeatureAccess(missing = setOf("READ_SMS")),
                calls = FeatureAccess(missing = setOf("READ_PHONE_STATE")),
            )
        assertEquals(Route.SmsPermission, primerOnSwitch(PhoneFeature.SMS, ui))
        assertEquals(Route.CallPermission, primerOnSwitch(PhoneFeature.CALLS, ui))
        assertNull(primerOnSwitch(PhoneFeature.CALLS, ui.copy(calls = FeatureAccess())))
    }
}
