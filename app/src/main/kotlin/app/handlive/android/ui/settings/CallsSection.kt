package app.handlive.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLActionRow
import app.handlive.android.core.design.component.HLGroupedListScope
import app.handlive.android.core.design.component.HLSwitchRow
import app.handlive.android.core.strings.R

/**
 * SET-02 fields 10 and 38: the Calls card — its switch with the permission status and action (SET-01 fields 10, 11,
 * 16) — and "Calls from Other Apps" under it, whose footer [footer] explains it. Turned on without Notification access
 * the second one says "Needs permission" and "Grant Permission" opens the primer (SET-01 field 19, E11).
 */
fun HLGroupedListScope.callsSection(
    state: SettingsUiState,
    actions: SettingsActions,
    footer: String,
) {
    section(footer = footer) {
        row {
            FeatureSwitch(R.string.settings_calls, state.settings.callEnabled, state.callStatus) {
                actions.setFeature(PhoneFeature.CALLS, it)
            }
        }
        permissionAction(state.callStatus)?.let { label ->
            row { HLActionRow(stringResource(label), false) { actions.grantFeature(PhoneFeature.CALLS) } }
        }
        row { AppCallsSwitch(state) { actions.setFeature(PhoneFeature.APP_CALLS, it) } }
        permissionAction(state.appCallsStatus)?.let { label ->
            row { HLActionRow(stringResource(label), false) { actions.grantFeature(PhoneFeature.APP_CALLS) } }
        }
    }
}

@Composable
private fun AppCallsSwitch(
    state: SettingsUiState,
    onChange: (Boolean) -> Unit,
) {
    val status = state.appCallsStatus
    HLSwitchRow(
        stringResource(R.string.settings_call_app_calls),
        state.settings.callAppCalls,
        onChange,
        unavailableReason = null,
        description = status.takeIf { it == FeatureStatus.NEEDS_PERMISSION }?.let { stringResource(it.label) },
    )
}
