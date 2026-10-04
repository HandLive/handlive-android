package app.handlive.android.ui.main

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.ui.settings.SettingsUiState
import app.handlive.android.ui.system.RestrictedSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SET-01 E7, E8, E11: field 14 again on the way back from Accessibility or Notification access without the access,
 * once per return, never after App info, never while the feature is off; and CLIP-01 A1: the disclosure only until
 * the consent exists.
 */
class SpecialAccessTripTest {
    private val wanted = HandLiveSettings(clipA11yConsentAt = 1L)
    private val off =
        SpecialAccessState(
            restriction = RestrictedSettings.LIKELY,
            settings = wanted,
            accessibilityOn = false,
            notificationAccess = false,
        )
    private val reshowA11y = Route.RestrictedSetting(openAppInfo = true)
    private val reshowNotifications = Route.RestrictedSetting(notificationAccess = true, openAppInfo = true)

    @Test
    fun backWithoutTheAccessShowsField14WithOpenSettingsWhileAndroidMayRestrictIt() {
        assertEquals(reshowA11y, routeOnReturn(SystemPage.ACCESSIBILITY, off))
        assertEquals(reshowNotifications, routeOnReturn(SystemPage.NOTIFICATION_ACCESS, off))
    }

    @Test
    fun nothingWhenNotRestrictedOrTheAccessIsOn() {
        val none = off.copy(restriction = RestrictedSettings.NONE)
        assertNull(
            "API 6 logic 6: R = none, stay, the user may try again",
            routeOnReturn(SystemPage.ACCESSIBILITY, none),
        )
        assertNull(
            "API 9 logic 4: R = none, no guidance on return",
            routeOnReturn(SystemPage.NOTIFICATION_ACCESS, none),
        )
        assertNull(
            "E8: the service is on, nothing to show",
            routeOnReturn(SystemPage.ACCESSIBILITY, off.copy(accessibilityOn = true)),
        )
        assertNull(
            "E11: Notification access granted, nothing to show",
            routeOnReturn(SystemPage.NOTIFICATION_ACCESS, off.copy(notificationAccess = true)),
        )
    }

    @Test
    fun nothingAfterAppInfoOrWithoutATrip() {
        assertNull("API 6 logic 6: a return from App info opens nothing", routeOnReturn(SystemPage.APP_INFO, off))
        assertNull(routeOnReturn(null, off))
    }

    @Test
    fun nothingWhileTheFeatureIsOff() {
        val autoSendOff = off.copy(settings = wanted.copy(clipAutoSend = false))
        val clipboardOff = off.copy(settings = wanted.copy(clipboardEnabled = false))
        val appCallsOff = off.copy(settings = wanted.copy(callAppCalls = false))
        val callsOff = off.copy(settings = wanted.copy(callEnabled = false))
        assertNull("API 6 logic 7: auto-send off never warns", routeOnReturn(SystemPage.ACCESSIBILITY, autoSendOff))
        assertNull(routeOnReturn(SystemPage.ACCESSIBILITY, clipboardOff))
        assertNull(
            "API 6 logic 7: calls from other apps off never warn",
            routeOnReturn(SystemPage.NOTIFICATION_ACCESS, appCallsOff),
        )
        assertNull(routeOnReturn(SystemPage.NOTIFICATION_ACCESS, callsOff))
    }

    @Test
    fun aReturnIsJudgedOnce() {
        val trip = SpecialAccessTrip()
        trip.leaveFor(SystemPage.ACCESSIBILITY)
        assertEquals(SystemPage.ACCESSIBILITY, trip.takeReturn())
        assertNull("a second resume finds nothing", trip.takeReturn())
    }

    @Test
    fun theNextTapAfterAppInfoGoesThroughField14AgainWithContinue() {
        val trip = SpecialAccessTrip()
        trip.leaveFor(SystemPage.APP_INFO)
        assertEquals(SystemPage.APP_INFO, trip.takeReturn())
        assertEquals(Route.RestrictedSetting(), restrictedSettingBefore(RestrictedSettings.LIKELY, false))
    }

    @Test
    fun theDisclosureOnlyUntilTheConsentExists() {
        assertEquals(AutoSendStep.DISCLOSURE, autoSendStep(SettingsUiState()))
        val consented = SettingsUiState(wanted)
        assertEquals("CLIP-01 A1: consent given, no disclosure again", AutoSendStep.SERVICE, autoSendStep(consented))
        assertEquals(
            "turned off by the user, consent kept",
            AutoSendStep.SERVICE,
            autoSendStep(consented.copy(settings = wanted.copy(clipAutoSend = false))),
        )
        assertEquals(AutoSendStep.SAVE, autoSendStep(consented.copy(accessibilityServiceOn = true)))
    }
}
