package app.handlive.android.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.core.design.component.HLFeedback
import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.revoke.UnpairResult
import app.handlive.android.settings.AppLanguageSetting
import app.handlive.android.ui.devices.DeviceDetailsScreen
import app.handlive.android.ui.pairing.PairingFlow
import app.handlive.android.ui.settings.AutoClearScreen
import app.handlive.android.ui.settings.ConsentScreen
import app.handlive.android.ui.settings.FeatureStatus
import app.handlive.android.ui.settings.LanguageScreen
import app.handlive.android.ui.settings.PermissionTarget
import app.handlive.android.ui.settings.PermissionsScreen
import app.handlive.android.ui.settings.RestrictedSettingScreen
import app.handlive.android.ui.settings.SettingsUiState
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.SystemPages
import kotlinx.coroutines.launch

/** The subscreen on top of the stack. */
@Composable
fun RouteContent(
    main: MainContext,
    route: Route,
    environment: PhoneEnvironment,
) {
    when (route) {
        Route.PairDevice -> {
            PairingRoute(main)
        }

        is Route.DeviceDetails -> {
            DeviceDetailsRoute(main, route.pairId)
        }

        Route.AutoClear -> {
            AutoClearRoute(main)
        }

        Route.Language -> {
            LanguageScreen(AppLanguageSetting.current(), AppLanguageSetting::apply, main::pop)
        }

        Route.Permissions -> {
            PermissionsRoute(main, environment)
        }

        Route.Consent -> {
            ConsentRoute(main, environment)
        }

        Route.RestrictedSetting -> {
            val context = LocalContext.current
            RestrictedSettingScreen {
                main.pop()
                openAccessibility(context, main)
            }
        }

        Route.SmsPermission -> {
            SmsPermissionRoute(main)
        }
    }
}

@Composable
private fun PairingRoute(main: MainContext) {
    val paired = stringResource(R.string.pairing_paired)
    PairingFlow(
        coordinator = main.dependencies.pairing.coordinator,
        onPaired = {
            main.feedback.show(HLFeedback(paired))
            main.pop()
        },
        onClose = main::pop,
    )
}

@Composable
private fun DeviceDetailsRoute(
    main: MainContext,
    pairId: String,
) {
    val scope = rememberCoroutineScope()
    val devices by main.dependencies.pairing.devices
        .collectAsStateWithLifecycle(null)
    val item = devices?.firstOrNull { it.pairId == pairId }
    val done = stringResource(R.string.pairing_unpaired)
    val pendingTemplate = item?.name?.let { stringResource(R.string.pairing_unpaired_pending, it) }
    if (devices != null && item == null) main.pop()
    item ?: return
    DeviceDetailsScreen(
        item = item,
        backLabel = stringResource(R.string.pairing_devices),
        onBack = main::pop,
        onUnpair = {
            scope.launch {
                val result =
                    main.dependencies.pairing.unpair
                        .unpair(pairId)
                main.feedback.show(HLFeedback(if (result == UnpairResult.DONE) done else pendingTemplate ?: done))
                main.pop()
            }
        },
    )
}

@Composable
private fun AutoClearRoute(main: MainContext) {
    val scope = rememberCoroutineScope()
    val settings = rememberSettings(main)
    AutoClearScreen(
        seconds = settings.clipAutoClearSeconds,
        onSelect = { seconds ->
            scope.launch {
                main.dependencies.data.settings
                    .set(SettingsKeys.CLIP_AUTO_CLEAR_S, seconds)
            }
        },
        onBack = main::pop,
    )
}

@Composable
private fun PermissionsRoute(
    main: MainContext,
    environment: PhoneEnvironment,
) {
    val context = LocalContext.current
    val settings = rememberSettings(main)
    val serviceOn by rememberOnResume {
        main.dependencies.clipboard.consent
            .isServiceEnabled()
    }
    val state = SettingsUiState(settings, serviceOn, rememberSmsAccess(settings.permissionsRequested))
    PermissionsScreen(
        environment = environment,
        autoSend = state.autoSendStatus,
        sms = state.smsStatus,
        onOpen = { target -> openPermissionTarget(context, main, environment, state, target) },
        onBack = main::pop,
    )
}

private fun openPermissionTarget(
    context: Context,
    main: MainContext,
    environment: PhoneEnvironment,
    state: SettingsUiState,
    target: PermissionTarget,
) {
    when (target) {
        PermissionTarget.NOTIFICATIONS -> {
            SystemPages.open(context, SystemPages.notificationSettings(context))
        }

        PermissionTarget.BACKGROUND -> {
            if (environment.batteryExempt) {
                SystemPages.open(context, SystemPages.batterySettings())
            } else {
                SystemPages.open(context, SystemPages.batteryExemption(context), SystemPages.batterySettings())
            }
        }

        PermissionTarget.UNUSED_APP_PAUSE -> {
            SystemPages.open(context, SystemPages.unusedAppRestrictions(context))
        }

        PermissionTarget.MANUFACTURER -> {
            environment.manufacturer?.let { SystemPages.open(context, SystemPages.manufacturerAutostart(context, it)) }
        }

        PermissionTarget.AUTO_SEND -> {
            if (state.settings.clipA11yConsentAt == null) main.push(Route.Consent) else openAccessibility(context, main)
        }

        PermissionTarget.SMS -> {
            if (state.smsStatus == FeatureStatus.PERMISSION_DENIED) {
                SystemPages.open(context, SystemPages.appDetails(context))
            } else {
                main.push(Route.SmsPermission)
            }
        }
    }
}

@Composable
private fun ConsentRoute(
    main: MainContext,
    environment: PhoneEnvironment,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consent = main.dependencies.clipboard.consent
    ConsentScreen(
        onAgree = {
            scope.launch { consent.agree(System.currentTimeMillis()) }
            main.pop()
            if (environment.restrictedSettingsLikely) {
                main.push(
                    Route.RestrictedSetting,
                )
            } else {
                openAccessibility(context, main)
            }
        },
        onSendManually = {
            scope.launch { consent.sendManually() }
            main.pop()
        },
    )
}

/** CLIP-01 A2 / SET-01 step 12: Settings › Accessibility, where the user turns HandLive on. */
private fun openAccessibility(
    context: Context,
    main: MainContext,
) {
    SystemPages.open(
        context,
        main.dependencies.clipboard.consent
            .settingsIntent(),
    )
}
