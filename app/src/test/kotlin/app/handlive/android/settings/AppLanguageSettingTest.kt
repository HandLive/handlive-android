package app.handlive.android.settings

import android.provider.Settings
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-02 field 32: system default, English or Vietnamese; Android 13+ uses the system page. */
@RunWith(RobolectricTestRunner::class)
class AppLanguageSettingTest {
    @After
    fun tearDown() = AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())

    @Test
    fun localesMapToTheThreeChoices() {
        assertEquals(AppLanguage.System, AppLanguageSetting.fromLocales(LocaleListCompat.getEmptyLocaleList()))
        assertEquals(AppLanguage.English, AppLanguageSetting.fromLocales(LocaleListCompat.forLanguageTags("en-GB")))
        assertEquals(AppLanguage.Vietnamese, AppLanguageSetting.fromLocales(LocaleListCompat.forLanguageTags("vi-VN")))
        // A language the catalog does not have falls back to the system choice (English is the fallback text).
        assertEquals(AppLanguage.System, AppLanguageSetting.fromLocales(LocaleListCompat.forLanguageTags("fr")))
    }

    @Test
    @Config(sdk = [31])
    fun android12ChoosesInsideTheAppAndTheChoiceIsKept() {
        assertFalse(AppLanguageSetting.usesSystemPage)
        AppLanguageSetting.apply(AppLanguage.Vietnamese)
        assertEquals(AppLanguage.Vietnamese, AppLanguageSetting.current())
        AppLanguageSetting.apply(AppLanguage.System)
        assertEquals(AppLanguage.System, AppLanguageSetting.current())
    }

    @Test
    @Config(sdk = [33])
    fun android13OpensTheSystemLanguagePageOfThisApp() {
        assertTrue(AppLanguageSetting.usesSystemPage)
        val intent = AppLanguageSetting.systemPageIntent(ApplicationProvider.getApplicationContext())
        assertEquals(Settings.ACTION_APP_LOCALE_SETTINGS, intent.action)
        assertEquals("package:app.handlive.android", intent.dataString)
    }
}
