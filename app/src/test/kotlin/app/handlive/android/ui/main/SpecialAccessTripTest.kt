package app.handlive.android.ui.main

import android.app.AppOpsManager
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
        val blocked = off.copy(restriction = RestrictedSettings.BLOCKED)
        assertEquals(reshowA11y, routeOnReturn(SystemPage.ACCESSIBILITY, blocked))
        assertEquals(reshowNotifications, routeOnReturn(SystemPage.NOTIFICATION_ACCESS, blocked))
    }

    @Test
    fun anUntriedBlockSendsTheUserBackToThePageWhereAndroidShowsItsDialog() {
        val untried = off.copy(restriction = RestrictedSettings.UNTRIED)
        assertEquals(Route.RestrictedSetting(), routeOnReturn(SystemPage.ACCESSIBILITY, untried))
        assertEquals(
            Route.RestrictedSetting(notificationAccess = true),
            routeOnReturn(SystemPage.NOTIFICATION_ACCESS, untried),
        )
    }

    @Test
    fun nothingWhenNotRestrictedOrTheAccessIsOn() {
        val none = off.copy(restriction = RestrictedSettings.NONE)
        assertNull("row 7: stay, the user may try again", routeOnReturn(SystemPage.ACCESSIBILITY, none))
        assertNull("row b", routeOnReturn(SystemPage.NOTIFICATION_ACCESS, none))
        assertNull("row 9", routeOnReturn(SystemPage.ACCESSIBILITY, off.copy(accessibilityOn = true)))
        assertNull("row g", routeOnReturn(SystemPage.NOTIFICATION_ACCESS, off.copy(notificationAccess = true)))
    }

    @Test
    fun nothingAfterAppInfoOrWithoutATrip() {
        assertNull("row 8, row f", routeOnReturn(SystemPage.APP_INFO, off))
        assertNull(routeOnReturn(null, off.copy(restriction = RestrictedSettings.BLOCKED)))
    }

    @Test
    fun nothingWhileTheFeatureIsOff() {
        fun blockedWith(settings: HandLiveSettings) =
            off.copy(restriction = RestrictedSettings.BLOCKED, settings = settings)
        val autoSendOff = blockedWith(wanted.copy(clipAutoSend = false))
        val clipboardOff = blockedWith(wanted.copy(clipboardEnabled = false))
        val appCallsOff = blockedWith(wanted.copy(callAppCalls = false))
        val callsOff = blockedWith(wanted.copy(callEnabled = false))
        assertNull("row 1", routeOnReturn(SystemPage.ACCESSIBILITY, autoSendOff))
        assertNull(routeOnReturn(SystemPage.ACCESSIBILITY, clipboardOff))
        assertNull("row a", routeOnReturn(SystemPage.NOTIFICATION_ACCESS, appCallsOff))
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
    fun theNextTapAfterAppInfoFollowsTheOpAsItIsNow() {
        val trip = SpecialAccessTrip()
        trip.leaveFor(SystemPage.APP_INFO)
        assertEquals(SystemPage.APP_INFO, trip.takeReturn())
        val likely = RestrictedSettings.LIKELY
        assertEquals(
            "still unknown: Continue once more",
            Route.RestrictedSetting(),
            restrictedSettingBefore(likely, false),
        )
        assertNull("allowed: straight to the page", restrictedSettingBefore(RestrictedSettings.NONE, false))
    }

    /** A first `ERRORED` try goes to the page, where Android shows its dialog; then App info can allow it. */
    @Test
    fun aFirstErroredTryContinuesAndAnIgnoredOneOpensSettings() {
        val errored = RestrictedSettings.of(35, AppOpsManager.MODE_ERRORED, null)
        assertEquals(RestrictedSettings.UNTRIED, errored)
        assertEquals(Route.RestrictedSetting(), restrictedSettingBefore(errored, notificationAccess = false))
        assertEquals(
            Route.RestrictedSetting(notificationAccess = true),
            restrictedSettingBefore(errored, notificationAccess = true),
        )
        val ignored = RestrictedSettings.of(35, AppOpsManager.MODE_IGNORED, null)
        assertEquals(
            Route.RestrictedSetting(notificationAccess = true, openAppInfo = true),
            restrictedSettingBefore(ignored, notificationAccess = true),
        )
    }

    @Test
    fun theDisclosureOnlyUntilTheConsentExists() {
        assertEquals(AutoSendStep.DISCLOSURE, autoSendStep(SettingsUiState()))
        val consented = SettingsUiState(wanted)
        assertEquals("row 3: no Route.Consent again", AutoSendStep.SERVICE, autoSendStep(consented))
        assertEquals(
            "turned off by the user, consent kept",
            AutoSendStep.SERVICE,
            autoSendStep(consented.copy(settings = wanted.copy(clipAutoSend = false))),
        )
        assertEquals(AutoSendStep.SAVE, autoSendStep(consented.copy(accessibilityServiceOn = true)))
    }
}
