package app.handlive.android.ui.system

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Test

/** SET-01 API 6 logic 2: the restricted-settings state from the Android version, the op's mode and the installer. */
class RestrictedSettingsTest {
    private val apk = "com.google.android.packageinstaller"
    private val play = "com.android.vending"

    @Test
    fun android12AndOlderHaveNoRestrictedSettings() {
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(32, null, apk))
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(29, AppOpsManager.MODE_ERRORED, null))
    }

    @Test
    fun anAllowedOpIsNeverRestrictedWhateverTheInstaller() {
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(33, AppOpsManager.MODE_ALLOWED, null))
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(36, AppOpsManager.MODE_ALLOWED, apk))
    }

    @Test
    fun anIgnoredOpIsBlockedEvenFromPlay() {
        assertEquals(RestrictedSettings.BLOCKED, RestrictedSettings.of(33, AppOpsManager.MODE_IGNORED, apk))
        assertEquals(RestrictedSettings.BLOCKED, RestrictedSettings.of(35, AppOpsManager.MODE_IGNORED, null))
        assertEquals(RestrictedSettings.BLOCKED, RestrictedSettings.of(35, AppOpsManager.MODE_IGNORED, play))
    }

    /** App info offers "Allow restricted settings" only after the system's dialog, which the page itself shows. */
    @Test
    fun anErroredOpIsOnlyLikelyUntilTheUserHasSeenTheSystemDialog() {
        assertEquals(RestrictedSettings.LIKELY, RestrictedSettings.of(33, AppOpsManager.MODE_ERRORED, apk))
        assertEquals(RestrictedSettings.LIKELY, RestrictedSettings.of(36, AppOpsManager.MODE_ERRORED, null))
        assertEquals(RestrictedSettings.LIKELY, RestrictedSettings.of(35, AppOpsManager.MODE_ERRORED, play))
    }

    @Test
    fun aDefaultUnknownOrUnreadableOpFallsBackToTheInstallSource() {
        listOf(AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND, 99, null).forEach { mode ->
            assertEquals("mode $mode from Play", RestrictedSettings.NONE, RestrictedSettings.of(34, mode, play))
            assertEquals("mode $mode from an APK", RestrictedSettings.LIKELY, RestrictedSettings.of(34, mode, apk))
            assertEquals("mode $mode from adb", RestrictedSettings.LIKELY, RestrictedSettings.of(36, mode, null))
        }
    }
}
