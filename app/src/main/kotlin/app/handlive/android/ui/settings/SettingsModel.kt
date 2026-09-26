package app.handlive.android.ui.settings

import app.handlive.android.core.data.settings.HandLiveSettings

/** SET-01 field 15: the state of automatic clipboard sending. */
enum class AutoSendStatus { ON, OFF, NEEDS_ACCESSIBILITY }

/** Everything the Settings screens show, from the settings keys and the phone's state. */
data class SettingsUiState(
    val settings: HandLiveSettings = HandLiveSettings(),
    val accessibilityServiceOn: Boolean = false,
    val sms: SmsAccessState = SmsAccessState(),
    /** This build has a relay (`RELAY_HOST`): "Remove Device from Server" makes sense (SET-02 field 26). */
    val relayAvailable: Boolean = false,
    /** CONN-03 E3: the relay refused this device; field 21 says so until the user turns it back on. */
    val relayDeviceRevoked: Boolean = false,
) {
    /** SET-02 field 7 with SET-01 field 10: the SMS switch and its feature card. */
    val smsStatus: FeatureStatus get() = sms.status(settings.smsEnabled)

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

    /** SET-02 field 7: turning on with permissions missing runs SET-01 part B (the key is saved either way). */
    fun setSms(enabled: Boolean)

    /** SET-01 fields 11 and 16: the SMS primer and its request, or the App info page once denied for good. */
    fun grantSms()

    fun open(page: SettingsPage)
}

/** Subscreens and system pages reachable from Settings. */
enum class SettingsPage { AUTO_CLEAR, PERMISSIONS, LANGUAGE }

/** The system pages the Permissions & Background screen leads to. */
enum class PermissionTarget { NOTIFICATIONS, BACKGROUND, UNUSED_APP_PAUSE, MANUFACTURER, AUTO_SEND, SMS }
