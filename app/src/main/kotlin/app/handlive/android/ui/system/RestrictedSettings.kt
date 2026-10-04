package app.handlive.android.ui.system

import android.os.Build

/**
 * Whether Android 13+ may restrict HandLive's restricted settings, Accessibility and Notification access (SET-01
 * API 6 logic 2, field 14). Apps cannot tell for sure: the `android:access_restricted_settings` op refuses to be read
 * without `GET_APP_OPS_STATS`, so only the Android version and the install source decide.
 */
enum class RestrictedSettings {
    /** Android 12 or older, or installed from Google Play. */
    NONE,

    /** Android 13+ and installed from elsewhere (APK, F-Droid, adb): Android may block the page. */
    LIKELY,
    ;

    companion object {
        private const val PLAY_STORE = "com.android.vending"

        fun of(
            sdk: Int,
            installer: String?,
        ): RestrictedSettings = if (sdk >= Build.VERSION_CODES.TIRAMISU && installer != PLAY_STORE) LIKELY else NONE
    }
}
