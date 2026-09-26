package app.handlive.android.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import app.handlive.android.core.design.component.HLActionSheet
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.component.HLGroupedRow
import app.handlive.android.core.design.component.HLLabelValueLayout
import app.handlive.android.core.design.component.HLScreenHeader
import app.handlive.android.core.design.component.HLSheetAction
import app.handlive.android.core.design.component.HLStatusIndicator
import app.handlive.android.core.design.component.HLValueRow
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.devices.ClipboardAvailability
import app.handlive.android.feature.pairing.devices.DeviceListItem
import app.handlive.android.feature.pairing.devices.SmsAvailability

/**
 * PAIR-02 details on the phone: link status, model, last connection (relative time), the peer's app version, the
 * clipboard and SMS with the reason each is off (SET-02 field 24), the Security Code (two groups of four,
 * selectable), and "Unpair" in the last group behind an action sheet (PAIR-03 field 3).
 */
@Composable
fun DeviceDetailsScreen(
    item: DeviceListItem,
    backLabel: String,
    onBack: () -> Unit,
    onUnpair: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    var confirming by remember { mutableStateOf(false) }
    val unpairLabel = stringResource(R.string.pairing_unpair)
    val featuresTitle = stringResource(R.string.pairing_features)
    Column(modifier = Modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        HLScreenHeader(title = item.name, backLabel = backLabel, onBack = onBack)
        HLGroupedList(modifier = Modifier.weight(1f)) {
            section {
                row { HLGroupedRow { HLStatusIndicator(status = item.link.status, deviceName = item.name) } }
                item.model?.let { model -> row { HLValueRow(stringResource(R.string.pairing_model), model) } }
                item.lastSeenAt?.let { at -> row { LastConnectedRow(at, now) } }
                item.appVersion?.let { version ->
                    row { HLValueRow(stringResource(R.string.pairing_app_version), version) }
                }
            }
            if (item.clipboard != ClipboardAvailability.UNKNOWN || item.sms != SmsAvailability.UNKNOWN) {
                section(title = featuresTitle) {
                    if (item.clipboard != ClipboardAvailability.UNKNOWN) row { ClipboardRow(item) }
                    if (item.sms != SmsAvailability.UNKNOWN) row { SmsRow(item) }
                }
            }
            section { row { SecurityCodeRow(item.safetyCode) } }
            section { actionRow(unpairLabel, onClick = { confirming = true }, destructive = true) }
        }
    }
    if (confirming) {
        HLActionSheet(
            title = stringResource(R.string.pairing_unpair_confirm_title, item.name),
            message = stringResource(R.string.pairing_unpair_confirm_message, item.name),
            actions =
                listOf(
                    HLSheetAction(unpairLabel, destructive = true) {
                        confirming = false
                        onUnpair()
                    },
                ),
            cancelLabel = stringResource(R.string.common_cancel),
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun LastConnectedRow(
    at: Long,
    now: Long,
) {
    val locale = LocalConfiguration.current.locales[0]
    HLValueRow(stringResource(R.string.pairing_last_connected), RelativeTime.format(at, now, locale))
}

/** PAIR-02 field 8 for the one Phase 1 feature: "On", "Off on <device>" or "Off". */
@Composable
private fun ClipboardRow(item: DeviceListItem) {
    val value =
        when (item.clipboard) {
            ClipboardAvailability.ON -> stringResource(R.string.common_on)
            ClipboardAvailability.OFF_ON_PEER -> stringResource(R.string.pairing_reason_off_on_device, item.name)
            else -> stringResource(R.string.common_off)
        }
    HLValueRow(stringResource(R.string.settings_clipboard), value)
}

/** PAIR-02 field 8 and SET-02 field 24 for SMS: "On", "Off on <device>", "Off" or the missing permission. */
@Composable
private fun SmsRow(item: DeviceListItem) {
    val value =
        when (item.sms) {
            SmsAvailability.ON -> stringResource(R.string.common_on)
            SmsAvailability.OFF_ON_PEER -> stringResource(R.string.pairing_reason_off_on_device, item.name)
            SmsAvailability.MISSING_PERMISSION -> stringResource(R.string.pairing_reason_missing_sms_permission)
            else -> stringResource(R.string.common_off)
        }
    HLValueRow(stringResource(R.string.settings_sms_messages), value)
}

/** PAIR-02 field 10: 8 lowercase hex digits shown as two groups ("fc64 7e0b"), selectable and copyable. */
@Composable
private fun SecurityCodeRow(code: String) {
    val typography = HandLiveTheme.typography
    val colors = HandLiveTheme.colors
    HLGroupedRow(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        HLLabelValueLayout(
            label = {
                BasicText(
                    text = stringResource(R.string.pairing_security_code),
                    style = typography.body.copy(color = colors.label),
                )
            },
            value = {
                SelectionContainer {
                    BasicText(
                        text = SecurityCode.grouped(code),
                        style =
                            typography.body.copy(
                                color = colors.secondaryLabel,
                                fontFamily = typography.codePin.fontFamily,
                            ),
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Display form of the Security Code. */
object SecurityCode {
    private const val GROUP = 4

    fun grouped(code: String): String = code.chunked(GROUP).joinToString(" ")
}
