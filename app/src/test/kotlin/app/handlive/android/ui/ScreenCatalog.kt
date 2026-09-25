package app.handlive.android.ui

import androidx.compose.runtime.Composable
import app.handlive.android.feature.pairing.exchange.PairingFailure
import app.handlive.android.settings.AppLanguage
import app.handlive.android.ui.devices.DeviceDetailsScreen
import app.handlive.android.ui.devices.DevicesScreen
import app.handlive.android.ui.main.StatusBanners
import app.handlive.android.ui.onboarding.AutostartScreen
import app.handlive.android.ui.onboarding.BackgroundPrimer
import app.handlive.android.ui.onboarding.NotificationsPrimer
import app.handlive.android.ui.onboarding.ServiceFailedScreen
import app.handlive.android.ui.onboarding.WelcomeScreen
import app.handlive.android.ui.pairing.CameraPrimer
import app.handlive.android.ui.pairing.PairChoiceScreen
import app.handlive.android.ui.pairing.PairedScreen
import app.handlive.android.ui.pairing.PairingFailedScreen
import app.handlive.android.ui.pairing.PairingProgressScreen
import app.handlive.android.ui.pairing.PinEntryScreen
import app.handlive.android.ui.pairing.ScannerFrame
import app.handlive.android.ui.settings.AutoClearScreen
import app.handlive.android.ui.settings.ConsentScreen
import app.handlive.android.ui.settings.LanguageScreen
import app.handlive.android.ui.settings.PermissionsScreen
import app.handlive.android.ui.settings.RestrictedSettingScreen
import app.handlive.android.ui.settings.SettingsScreen

/** Every screen of A1.4 with sample data, by name, for the text-fit and semantics tests. */
object ScreenCatalog {
    private val warnings = StatusBanners(notificationsOff = true, serviceFailed = true)

    val screens: Map<String, @Composable () -> Unit> =
        linkedMapOf(
            "welcome" to { WelcomeScreen {} },
            "notifications primer" to { NotificationsPrimer {} },
            "background primer" to { BackgroundPrimer {} },
            "service failed" to { ServiceFailedScreen {} },
            "autostart" to { AutostartScreen(UiSamples.environment.manufacturer, true, {}, {}, {}, {}) },
            "devices empty" to { DevicesScreen(emptyList(), StatusBanners(), {}, {}) },
            "devices with warnings" to { DevicesScreen(listOf(UiSamples.mac, UiSamples.ipad), warnings, {}, {}) },
            "device details" to { DeviceDetailsScreen(UiSamples.mac, "Devices", {}, {}, now = UiSamples.NOW) },
            "pair choice" to { PairChoiceScreen({}, {}, {}) },
            "camera primer" to { CameraPrimer {} },
            "scanner" to { ScannerFrame(error = null, onCancel = {}) {} },
            "pairing progress" to { PairingProgressScreen {} },
            "pin entry" to { PinEntryScreen(attemptsLeft = 2, onSubmit = {}, onCancel = {}) },
            "paired" to { PairedScreen("MacBook của Lan", "fc647e0b") {} },
            "pairing failed" to { PairingFailedScreen(PairingFailure.CAMERA_DENIED, {}, {}, {}) },
            "limit reached" to { PairingFailedScreen(PairingFailure.LIMIT_REACHED, {}, {}, {}) },
            "pairing lost" to { PairingFailedScreen(PairingFailure.DISCONNECTED, {}, {}, {}) },
            "settings" to { SettingsScreen(UiSamples.settings, warnings, UiSamples.NoActions, "System Default") },
            "auto-clear" to { AutoClearScreen(60, {}, {}) },
            "language" to { LanguageScreen(AppLanguage.Vietnamese, {}, {}) },
            "permissions" to {
                PermissionsScreen(UiSamples.environment, UiSamples.settings.autoSendStatus, {}, {})
            },
            "consent" to { ConsentScreen({}, {}) },
            "restricted setting" to { RestrictedSettingScreen {} },
        )
}
