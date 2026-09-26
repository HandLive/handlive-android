package app.handlive.android.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import app.handlive.android.core.strings.R

/**
 * SET-02 field 32 (C20, 0.12.3): HandLive follows the system language unless the user picks one for this app.
 * Each choice is labelled in its own language ("English", "Tiếng Việt").
 */
enum class AppLanguage(
    val languageTag: String?,
    @param:StringRes val label: Int,
) {
    System(null, R.string.settings_language_system),
    English("en", R.string.settings_language_en),
    Vietnamese("vi", R.string.settings_language_vi),
}

/**
 * Per-app language. Android 13+ has a system page for it (listing the languages of `locales_config.xml`); Android
 * 10–12 choose inside HandLive and AppCompat applies and stores the choice (`autoStoreLocales`). The session is not
 * interrupted: activities are recreated by the system.
 */
object AppLanguageSetting {
    /** Android 13+ opens the system page instead of showing the in-app choice. */
    val usesSystemPage: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(): AppLanguage = fromLocales(AppCompatDelegate.getApplicationLocales())

    fun apply(language: AppLanguage) {
        val locales =
            language.languageTag?.let(LocaleListCompat::forLanguageTags) ?: LocaleListCompat.getEmptyLocaleList()
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** `Settings.ACTION_APP_LOCALE_SETTINGS` for this package (Android 13+). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun systemPageIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", context.packageName, null))

    internal fun fromLocales(locales: LocaleListCompat): AppLanguage =
        when (locales.takeUnless { it.isEmpty }?.get(0)?.language) {
            "en" -> AppLanguage.English
            "vi" -> AppLanguage.Vietnamese
            else -> AppLanguage.System
        }
}
