package app.handlive.android.ui.system

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/**
 * How sure HandLive is that Android 13+ blocks its restricted settings, Accessibility and Notification access
 * (SET-01 API 6 logic 2, field 14). Read again on every resume.
 */
enum class RestrictedSettings {
    /** Not restricted: Android 12 or older, installed from Google Play, or "Allow restricted settings" was chosen. */
    NONE,

    /**
     * `ERRORED`: blocked, but App info has no "Allow restricted settings" until the user has tried the page itself,
     * where the system shows its dialog.
     */
    UNTRIED,

    /** `DEFAULT`, unknown or unreadable, and HandLive is not from Play: Android decides from the install source. */
    LIKELY,

    /** `IGNORED`: blocked, and the user has seen the system's dialog; App info offers "Allow restricted settings". */
    BLOCKED,
    ;

    companion object {
        /** `AppOpsManager.OPSTR_ACCESS_RESTRICTED_SETTINGS`, hidden in the SDK; Android 13+. */
        const val OP = "android:access_restricted_settings"
        private const val PLAY_STORE = "com.android.vending"

        /**
         * The mapping of SET-01 API 6 logic 2, as the platform's enhanced confirmation reads the op: `ALLOWED` is
         * never guarded, `ERRORED` and `IGNORED` always are, but only `IGNORED` lets App info clear the restriction;
         * `DEFAULT` falls back to the install source.
         */
        fun of(
            sdk: Int,
            opMode: Int?,
            installer: String?,
        ): RestrictedSettings =
            when {
                sdk < Build.VERSION_CODES.TIRAMISU -> NONE
                opMode == AppOpsManager.MODE_ALLOWED -> NONE
                opMode == AppOpsManager.MODE_IGNORED -> BLOCKED
                opMode == AppOpsManager.MODE_ERRORED -> UNTRIED
                installer == PLAY_STORE -> NONE
                else -> LIKELY
            }

        fun read(
            context: Context,
            installer: String?,
        ): RestrictedSettings {
            val sdk = Build.VERSION.SDK_INT
            return of(sdk, if (sdk >= Build.VERSION_CODES.TIRAMISU) opMode(context) else null, installer)
        }

        /** HandLive's own mode needs no permission; a ROM without the op throws, which counts as unreadable. */
        private fun opMode(context: Context): Int? =
            runCatching {
                context
                    .getSystemService(AppOpsManager::class.java)
                    ?.unsafeCheckOpNoThrow(OP, Process.myUid(), context.packageName)
            }.getOrNull()
    }
}
