package app.handlive.android.ui.onboarding

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLButtonStyle
import app.handlive.android.core.design.component.HLStepScreen
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.strings.R
import app.handlive.android.ui.system.Manufacturer

/** SET-01 field 1–2: the welcome screen with its privacy explanation and "Get Started". */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.Devices,
        title = stringResource(R.string.setup_welcome_title),
        body = stringResource(R.string.setup_welcome_body_android),
    ) {
        HLButton(stringResource(R.string.common_get_started), onGetStarted, Modifier.fillMaxWidth())
    }
}

/** SET-01 step 3 (Android 13+): the PermissionPrimer before the notification dialog — one "Continue" only. */
@Composable
fun NotificationsPrimer(onContinue: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.Notifications,
        title = stringResource(R.string.permission_notifications_primer_title_android),
        body = stringResource(R.string.permission_notifications_primer_android),
        footer = stringResource(R.string.permission_primer_footer),
    ) {
        HLButton(stringResource(R.string.common_continue), onContinue, Modifier.fillMaxWidth())
    }
}

/** SET-01 step 5b: the PermissionPrimer before the battery optimization exemption dialog. */
@Composable
fun BackgroundPrimer(onContinue: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.BatteryFull,
        title = stringResource(R.string.permission_background_primer_title),
        body = stringResource(R.string.permission_background_primer),
        footer = stringResource(R.string.permission_primer_footer),
    ) {
        HLButton(stringResource(R.string.common_continue), onContinue, Modifier.fillMaxWidth())
    }
}

/** SET-01 E2: the service could not start even in the foreground. */
@Composable
fun ServiceFailedScreen(onRetry: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.Warning,
        title = stringResource(R.string.error_service_start_failed),
        body = null,
    ) {
        HLButton(stringResource(R.string.common_retry), onRetry, Modifier.fillMaxWidth())
    }
}

/**
 * SET-01 fields 7–9: the manufacturer's autostart instructions and "Pause app activity if unused", with "Open
 * Manufacturer Settings", "Done" and "Skip" (not a permission primer, so it may be skipped).
 */
@Composable
fun AutostartScreen(
    manufacturer: Manufacturer?,
    pauseEnabled: Boolean,
    onOpenManufacturer: () -> Unit,
    onOpenPause: () -> Unit,
    onDone: () -> Unit,
    onSkip: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.BatteryFull,
        title = stringResource(R.string.setup_autostart_title),
        body = manufacturer?.let { stringResource(instructionsOf(it)) },
        extra = {
            if (pauseEnabled) {
                HLButton(stringResource(R.string.setup_pause_app_activity), onOpenPause, style = HLButtonStyle.Tinted)
            }
        },
    ) {
        if (manufacturer != null) {
            HLButton(
                stringResource(R.string.setup_open_manufacturer_settings),
                onOpenManufacturer,
                Modifier.fillMaxWidth(),
            )
        }
        HLButton(
            stringResource(R.string.setup_autostart_done),
            onDone,
            Modifier.fillMaxWidth(),
            style = HLButtonStyle.Glass,
        )
        HLButton(stringResource(R.string.common_skip), onSkip, Modifier.fillMaxWidth(), style = HLButtonStyle.Plain)
    }
}

/** SET-01 API 5 configuration table. */
fun instructionsOf(manufacturer: Manufacturer): Int =
    when (manufacturer) {
        Manufacturer.XIAOMI -> R.string.setup_autostart_xiaomi
        Manufacturer.OPPO -> R.string.setup_autostart_oppo
        Manufacturer.SAMSUNG -> R.string.setup_autostart_samsung
    }
