package app.handlive.android.ui.system

import org.junit.Assert.assertEquals
import org.junit.Test

/** SET-01 API 6 logic 2: the restricted-settings state from the Android version and the installer. */
class RestrictedSettingsTest {
    @Test
    fun android12AndOlderHaveNoRestrictedSettings() {
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(32, "com.google.android.packageinstaller"))
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(29, null))
    }

    @Test
    fun android13AndNewerMayRestrictThemOutsideGooglePlay() {
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(33, "com.android.vending"))
        assertEquals(RestrictedSettings.NONE, RestrictedSettings.of(36, "com.android.vending"))
        assertEquals(
            "an APK",
            RestrictedSettings.LIKELY,
            RestrictedSettings.of(33, "com.google.android.packageinstaller"),
        )
        assertEquals("F-Droid", RestrictedSettings.LIKELY, RestrictedSettings.of(35, "org.fdroid.fdroid"))
        assertEquals("adb", RestrictedSettings.LIKELY, RestrictedSettings.of(36, null))
    }
}
