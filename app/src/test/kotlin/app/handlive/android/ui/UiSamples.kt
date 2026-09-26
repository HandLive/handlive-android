package app.handlive.android.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.feature.pairing.devices.ClipboardAvailability
import app.handlive.android.feature.pairing.devices.DeviceLink
import app.handlive.android.feature.pairing.devices.DeviceListItem
import app.handlive.android.feature.pairing.devices.SmsAvailability
import app.handlive.android.ui.settings.SettingsActions
import app.handlive.android.ui.settings.SettingsPage
import app.handlive.android.ui.settings.SettingsUiState
import app.handlive.android.ui.settings.SmsAccessState
import app.handlive.android.ui.system.Manufacturer
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.UnusedAppPause

/** Sample data and wrappers shared by the screen tests. */
object UiSamples {
    const val NOW = 1_727_150_000_000L

    val mac =
        DeviceListItem(
            pairId = "3f2b1c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d",
            name = "MacBook của Lan",
            platform = PeerPlatform.MACOS,
            model = "MacBookPro18,3",
            link = DeviceLink.WIFI,
            lastSeenAt = NOW - 120_000,
            appVersion = "1.0.0 (100)",
            clipboard = ClipboardAvailability.OFF_ON_PEER,
            safetyCode = "fc647e0b",
            sms = SmsAvailability.MISSING_PERMISSION,
        )

    val ipad =
        mac.copy(
            pairId = "4a5b6c7d-8e9f-4a1b-9c2d-3e4f5a6b7c8d",
            name = "iPad",
            platform = PeerPlatform.IPADOS,
            link = DeviceLink.DISCONNECTED,
            clipboard = ClipboardAvailability.UNKNOWN,
        )

    val environment =
        PhoneEnvironment(
            notificationPermissionRuntime = true,
            notificationsAllowed = false,
            batteryExempt = false,
            manufacturer = Manufacturer.XIAOMI,
            unusedAppPause = UnusedAppPause.ENABLED,
            restrictedSettingsLikely = true,
        )

    val settings = SettingsUiState(HandLiveSettings(clipA11yConsentAt = NOW), accessibilityServiceOn = true)

    /** SMS on while `READ_CONTACTS` is missing: "Needs permission" with "Grant Permission". */
    val smsNeedsPermission = settings.copy(sms = SmsAccessState(missing = setOf("READ_CONTACTS")))

    object NoActions : SettingsActions {
        override fun setClipboard(enabled: Boolean) = Unit

        override fun setAutoSend(enabled: Boolean) = Unit

        override fun setSendImages(enabled: Boolean) = Unit

        override fun setBlockSensitive(enabled: Boolean) = Unit

        override fun setAutoClear(seconds: Int) = Unit

        override fun setInternet(enabled: Boolean) = Unit

        override fun setSms(enabled: Boolean) = Unit

        override fun grantSms() = Unit

        override fun open(page: SettingsPage) = Unit
    }
}

/** The screen in the HandLive theme with text at [fontScale] (200 % is the acceptance criterion of A1.4). */
@Composable
fun Scaled(
    fontScale: Float,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        HandLiveTheme(darkTheme = false, highContrast = false) { Box { content() } }
    }
}
