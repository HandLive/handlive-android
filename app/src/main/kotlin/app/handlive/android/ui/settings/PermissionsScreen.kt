package app.handlive.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLActionRow
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.component.HLGroupedListScope
import app.handlive.android.core.design.component.HLNavigationRow
import app.handlive.android.core.design.component.HLScreenHeader
import app.handlive.android.core.design.component.HLValueRow
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.ui.onboarding.instructionsOf
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.UnusedAppPause

/**
 * SET-02 field 23 → SET-01 fields 3, 6–11, 15 and 16: notifications, background running (with its warning, E3),
 * "Pause app activity if unused", the manufacturer's autostart instructions, and one card per feature — automatic
 * clipboard sending and SMS with its status and "Grant Permission" or "Open Settings". Every row leads to the page
 * that changes it; the states are read again on every resume.
 */
@Composable
fun PermissionsScreen(
    environment: PhoneEnvironment,
    autoSend: AutoSendStatus,
    sms: FeatureStatus,
    onOpen: (PermissionTarget) -> Unit,
    onBack: () -> Unit,
) {
    val backgroundWarning =
        if (environment.batteryExempt) {
            null
        } else {
            stringResource(
                R.string.permission_background_primer,
            )
        }
    val manufacturerHelp = environment.manufacturer?.let { stringResource(instructionsOf(it)) }
    Column(modifier = Modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        HLScreenHeader(
            title = stringResource(R.string.settings_permissions_background),
            backLabel = stringResource(R.string.settings_title),
            onBack = onBack,
        )
        HLGroupedList(modifier = Modifier.weight(1f)) {
            section {
                row {
                    OnOffRow(
                        R.string.settings_notifications,
                        environment.notificationsAllowed,
                    ) { onOpen(PermissionTarget.NOTIFICATIONS) }
                }
            }
            backgroundSection(environment, backgroundWarning, onOpen)
            manufacturerHelp?.let { help ->
                section(footer = help) {
                    row {
                        HLActionRow(stringResource(R.string.setup_open_manufacturer_settings), destructive = false) {
                            onOpen(PermissionTarget.MANUFACTURER)
                        }
                    }
                }
            }
            section {
                row { AutoSendRow(autoSend) { onOpen(PermissionTarget.AUTO_SEND) } }
                row { HLValueRow(stringResource(R.string.settings_sms_messages), stringResource(sms.label)) }
                smsPermissionAction(sms)?.let { label ->
                    row { HLActionRow(stringResource(label), destructive = false) { onOpen(PermissionTarget.SMS) } }
                }
            }
        }
    }
}

/** Fields 6–7 with the E3 warning under them while the exemption is missing. */
private fun HLGroupedListScope.backgroundSection(
    environment: PhoneEnvironment,
    warning: String?,
    onOpen: (PermissionTarget) -> Unit,
) {
    section(footer = warning) {
        row {
            OnOffRow(
                R.string.settings_run_in_background,
                environment.batteryExempt,
            ) { onOpen(PermissionTarget.BACKGROUND) }
        }
        if (environment.unusedAppPause != UnusedAppPause.NOT_AVAILABLE) {
            row {
                OnOffRow(R.string.setup_pause_app_activity, environment.unusedAppPause == UnusedAppPause.ENABLED) {
                    onOpen(PermissionTarget.UNUSED_APP_PAUSE)
                }
            }
        }
    }
}

/** Field 15: "On", "Off" or "Auto-send isn't on yet". */
@Composable
private fun AutoSendRow(
    status: AutoSendStatus,
    onClick: () -> Unit,
) {
    val value =
        when (status) {
            AutoSendStatus.ON -> stringResource(R.string.common_on)
            AutoSendStatus.OFF -> stringResource(R.string.common_off)
            AutoSendStatus.NEEDS_ACCESSIBILITY -> stringResource(R.string.settings_auto_send_not_on)
        }
    HLNavigationRow(stringResource(R.string.settings_auto_send), value, onClick)
}

@Composable
private fun OnOffRow(
    title: Int,
    on: Boolean,
    onClick: () -> Unit,
) = HLNavigationRow(stringResource(title), stringResource(if (on) R.string.common_on else R.string.common_off), onClick)
