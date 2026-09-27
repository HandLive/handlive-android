package app.handlive.android.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.design.component.HLFeedback
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.strings.R
import app.handlive.android.settings.AppLanguageSetting
import app.handlive.android.ui.devices.DevicesScreen
import app.handlive.android.ui.settings.DataActionDialogs
import app.handlive.android.ui.settings.DataResult
import app.handlive.android.ui.settings.SettingsScreen
import app.handlive.android.ui.settings.SettingsUiState

/** The Devices tab bound to the device list of PAIR-02. */
@Composable
fun DevicesTab(main: MainContext) {
    val devices by main.dependencies.pairing.devices
        .collectAsStateWithLifecycle(emptyList())
    DevicesScreen(
        items = devices,
        banners = main.banners,
        onOpen = { main.push(Route.DeviceDetails(it)) },
        onAdd = { main.push(Route.PairDevice) },
    )
}

/**
 * The Settings tab bound to the settings keys, the Accessibility service state, the SMS permissions and the relay,
 * with the dialogs and the result of SET-02 A1–A6.
 */
@Composable
fun SettingsTab(main: MainContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = rememberSettings(main)
    val serviceOn by rememberOnResume {
        main.dependencies.clipboard.consent
            .isServiceEnabled()
    }
    val relay by main.dependencies.relay.status
        .collectAsStateWithLifecycle()
    val state =
        SettingsUiState(
            settings,
            serviceOn,
            rememberSmsAccess(settings.permissionsRequested),
            relayAvailable = relay.available,
            relayDeviceRevoked = relay.deviceRevoked,
            relayPinMismatch = relay.pinMismatch,
        )
    SettingsScreen(
        state = state,
        banners = main.banners,
        actions = SettingsActionsImpl(context, scope, main, state),
        languageValue = stringResource(AppLanguageSetting.current().label),
        onDataAction = main.dependencies.dataActions::ask,
    )
    DataActionsHost(main)
}

/** Field 28–29 and E7 dialogs, and field 30 as feedback: "Removed from the server" or E5. */
@Composable
private fun DataActionsHost(main: MainContext) {
    val actions = main.dependencies.dataActions
    val step by actions.step.collectAsStateWithLifecycle()
    val removed = stringResource(R.string.settings_removed_from_server)
    val unreachable = stringResource(R.string.error_server_unreachable)
    LaunchedEffect(actions) {
        actions.results.collect { result ->
            main.feedback.show(
                if (result == DataResult.REMOVED) {
                    HLFeedback(removed, HLSymbol.CheckCircle)
                } else {
                    HLFeedback(unreachable, HLSymbol.Warning)
                },
            )
        }
    }
    DataActionDialogs(step, actions::confirm, actions::deleteAnyway, actions::cancel)
}
