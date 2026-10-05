package app.handlive.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLActionSheet
import app.handlive.android.core.design.component.HLSheetAction
import app.handlive.android.core.design.component.HLSwitchRow
import app.handlive.android.core.strings.R
import java.text.DateFormat

/**
 * Field 2 with its status: the description, "Auto-send isn't on yet" (E8), or field 3 "Agreed on …". On without the
 * service, a tap asks first (field 39) instead of turning automatic sending off.
 */
@Composable
internal fun AutoSendSwitch(
    state: SettingsUiState,
    onChange: (Boolean) -> Unit,
) {
    val consentAt = state.settings.clipA11yConsentAt
    val description =
        when {
            state.autoSendStatus == AutoSendStatus.NEEDS_ACCESSIBILITY -> {
                stringResource(R.string.settings_auto_send_not_on)
            }

            consentAt != null && state.autoSendStatus == AutoSendStatus.ON -> {
                val locale = LocalConfiguration.current.locales[0]
                stringResource(
                    R.string.settings_auto_send_consented_at,
                    DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(consentAt),
                    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(consentAt),
                )
            }

            else -> {
                stringResource(R.string.settings_auto_send_description)
            }
        }
    var asking by rememberSaveable { mutableStateOf(false) }
    HLSwitchRow(
        stringResource(R.string.settings_auto_send),
        state.settings.clipAutoSend,
        { checked ->
            when (autoSendSwitchTap(state.autoSendStatus)) {
                AutoSendSwitchTap.APPLY -> onChange(checked)
                AutoSendSwitchTap.ASK -> asking = true
            }
        },
        unavailableReason = null,
        description = description,
    )
    if (asking) AutoSendNotOnSheet(onChange) { asking = false }
}

/**
 * SET-02 field 39: "Turn On" goes the way to the service (CLIP-01 A1, SET-01 field 14 or Accessibility), "Send
 * Manually" turns automatic sending off (CLIP-01 E1); Cancel, Back or a tap outside change nothing (E10).
 */
@Composable
private fun AutoSendNotOnSheet(
    onChange: (Boolean) -> Unit,
    close: () -> Unit,
) {
    fun choose(enabled: Boolean) {
        close()
        onChange(enabled)
    }
    HLActionSheet(
        title = stringResource(R.string.settings_auto_send_not_on_title),
        message = stringResource(R.string.settings_auto_send_not_on_message),
        actions =
            listOf(
                HLSheetAction(stringResource(R.string.settings_auto_send_turn_on)) { choose(true) },
                HLSheetAction(stringResource(R.string.clipboard_consent_send_manually)) { choose(false) },
            ),
        cancelLabel = stringResource(R.string.common_cancel),
        onDismiss = close,
    )
}
