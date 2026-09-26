package app.handlive.android.ui.settings

import app.handlive.android.core.data.settings.HandLiveSettings

/** SET-01 field 15: the state of automatic clipboard sending. */
enum class AutoSendStatus { ON, OFF, NEEDS_ACCESSIBILITY }

/** Everything the Settings screens show, from the settings keys and the phone's state. */
data class SettingsUiState(
    val settings: HandLiveSettings = HandLiveSettings(),
    val accessibilityServiceOn: Boolean = false,
) {
    /** Field 15: on only with the setting, the recorded consent and the service turned on. */
    val autoSendStatus: AutoSendStatus
        get() =
            when {
                !settings.clipAutoSend -> AutoSendStatus.OFF
                settings.clipA11yConsentAt != null && accessibilityServiceOn -> AutoSendStatus.ON
                else -> AutoSendStatus.NEEDS_ACCESSIBILITY
            }
}

/** What the Settings screens ask for; the route turns these into settings writes and system pages. */
interface SettingsActions {
    fun setClipboard(enabled: Boolean)

    /** Turning on goes through the disclosure unless consent and the service already exist (CLIP-01 A1). */
    fun setAutoSend(enabled: Boolean)

    fun setSendImages(enabled: Boolean)

    fun setBlockSensitive(enabled: Boolean)

    fun setAutoClear(seconds: Int)

    fun setInternet(enabled: Boolean)

    fun open(page: SettingsPage)
}

/** Subscreens and system pages reachable from Settings. */
enum class SettingsPage { AUTO_CLEAR, PERMISSIONS, LANGUAGE }

/** The system pages the Permissions & Background screen leads to. */
enum class PermissionTarget { NOTIFICATIONS, BACKGROUND, UNUSED_APP_PAUSE, MANUFACTURER, AUTO_SEND }
