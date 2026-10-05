package app.handlive.android.ui.settings

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.capability.AndroidPermissions

/** SET-01 field 15: the state of automatic clipboard sending. */
enum class AutoSendStatus { ON, OFF, NEEDS_ACCESSIBILITY }

/** What a tap on the Auto-Send switch (SET-02 field 2) does: change the setting at once, or ask first. */
enum class AutoSendSwitchTap { APPLY, ASK }

/**
 * The switch shows on while the service is not on yet ("Auto-send isn't on yet"): a tap there may mean "make it work"
 * as much as "turn it off", so the sheet asks; every other state applies the tap as before.
 */
fun autoSendSwitchTap(status: AutoSendStatus): AutoSendSwitchTap =
    if (status == AutoSendStatus.NEEDS_ACCESSIBILITY) AutoSendSwitchTap.ASK else AutoSendSwitchTap.APPLY

/** Everything the Settings screens show, from the settings keys and the phone's state. */
data class SettingsUiState(
    val settings: HandLiveSettings = HandLiveSettings(),
    val accessibilityServiceOn: Boolean = false,
    val sms: FeatureAccess = FeatureAccess(),
    val calls: FeatureAccess = FeatureAccess(),
    /** HandLive has Notification access (CALL-05): the special access calls from other apps need (SET-01 N1–N2). */
    val notificationAccess: Boolean = true,
    /** This build has a relay (`RELAY_HOST`): "Remove Device from Server" makes sense (SET-02 field 26). */
    val relayAvailable: Boolean = false,
    /** CONN-03 E3: the relay refused this device; field 21 says so until the user turns it back on. */
    val relayDeviceRevoked: Boolean = false,
    /** CONN-03 E7: the relay's certificate matched none of the pins; field 21 says so. */
    val relayPinMismatch: Boolean = false,
) {
    /** SET-02 field 21: the relay error of CONN-03 E3 or E7 in place of the description, else the description. */
    val internetDescription: Int
        get() =
            when {
                relayDeviceRevoked -> R.string.error_relay_device_revoked
                relayPinMismatch -> R.string.error_relay_pin_mismatch
                else -> R.string.settings_internet_connection_description
            }

    /** SET-02 field 7 with SET-01 field 10: the SMS switch and its feature card. */
    val smsStatus: FeatureStatus get() = sms.status(settings.smsEnabled)

    /** SET-02 field 10 with SET-01 field 10: the Calls switch and its feature card. */
    val callStatus: FeatureStatus get() = calls.status(settings.callEnabled)

    /**
     * SET-02 field 38 with SET-01 field 10 and E11: calls from other apps are on only with their own switch, the Calls
     * switch and Notification access; they need no telephony, so this never says "unsupported".
     */
    val appCallsStatus: FeatureStatus get() = status(PhoneFeature.APP_CALLS)

    fun access(feature: PhoneFeature): FeatureAccess =
        when (feature) {
            PhoneFeature.SMS -> sms
            PhoneFeature.CALLS -> calls
            PhoneFeature.APP_CALLS -> notificationAccessOf()
        }

    /** The special access of calls from other apps as a missing permission: never denied for good, no telephony. */
    private fun notificationAccessOf() =
        FeatureAccess(missing = if (notificationAccess) emptySet() else setOf(AndroidPermissions.NOTIFICATION_LISTENER))

    fun status(feature: PhoneFeature): FeatureStatus =
        when (feature) {
            PhoneFeature.SMS -> smsStatus
            PhoneFeature.CALLS -> callStatus
            PhoneFeature.APP_CALLS -> access(feature).status(settings.callEnabled && settings.callAppCalls)
        }

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

    /**
     * SET-02 field 7, 10 or 38: turning on with permissions missing runs SET-01 part B — for calls from other apps the
     * Notification access primer, steps N1–N2 (the key is saved either way).
     */
    fun setFeature(
        feature: PhoneFeature,
        enabled: Boolean,
    )

    /** SET-01 fields 11 and 16: the feature's primer and its request, or the App info page once denied for good. */
    fun grantFeature(feature: PhoneFeature)

    fun open(page: SettingsPage)
}

/**
 * The features with a switch, a permission primer and a feature card: SMS and calls (SET-02 fields 7 and 10), and
 * calls from other apps (field 38, Notification access instead of runtime permissions).
 */
enum class PhoneFeature { SMS, CALLS, APP_CALLS }

/** Subscreens and system pages reachable from Settings. */
enum class SettingsPage { AUTO_CLEAR, PERMISSIONS, LANGUAGE }

/** The system pages the Permissions & Background screen leads to. */
enum class PermissionTarget {
    NOTIFICATIONS,
    BACKGROUND,
    UNUSED_APP_PAUSE,
    MANUFACTURER,
    AUTO_SEND,
    SMS,
    CALLS,
    APP_CALLS,
}
