package app.handlive.android.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.settings.AppLanguageSetting
import app.handlive.android.ui.devices.DevicesScreen
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

/** The Settings tab bound to the settings keys and the Accessibility service state. */
@Composable
fun SettingsTab(main: MainContext) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = rememberSettings(main)
    val serviceOn by rememberOnResume {
        main.dependencies.clipboard.consent
            .isServiceEnabled()
    }
    val state = SettingsUiState(settings, serviceOn, rememberSmsAccess(settings.permissionsRequested))
    SettingsScreen(
        state = state,
        banners = main.banners,
        actions = SettingsActionsImpl(context, scope, main, state),
        languageValue = stringResource(AppLanguageSetting.current().label),
    )
}
