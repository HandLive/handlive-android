package app.handlive.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLActionRow
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.component.HLGroupedListScope
import app.handlive.android.core.design.component.HLNavigationRow
import app.handlive.android.core.design.component.HLScreenHeader
import app.handlive.android.core.design.component.HLSwitchRow
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.ui.main.StatusBanners
import app.handlive.android.ui.main.bannerLabels
import app.handlive.android.ui.main.bannerSections
import java.text.DateFormat

/**
 * SET-02 on the phone: the Clipboard group (fields 1–6, each switch with its one-line description), SMS Messages
 * (field 7, with its permission status and action, SET-01 fields 10, 11 and 16), Internet Connection (field 21),
 * Permissions & Background (field 23) and Language (field 32).
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    banners: StatusBanners,
    actions: SettingsActions,
    languageValue: String,
    modifier: Modifier = Modifier,
    onDataAction: (DataAction) -> Unit = {},
) {
    val labels = bannerLabels()
    val clipboardTitle = stringResource(R.string.settings_clipboard)
    val autoClearFooter = stringResource(R.string.settings_auto_clear_footer)
    val dataTitle = stringResource(R.string.settings_data)
    val settings = state.settings
    Column(modifier = modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        HLScreenHeader(title = stringResource(R.string.settings_title))
        HLGroupedList(modifier = Modifier.weight(1f)) {
            bannerSections(banners, labels)
            clipboardSection(clipboardTitle, autoClearFooter, state, actions)
            smsSection(state, actions)
            section {
                row {
                    Switch(
                        R.string.settings_internet_connection,
                        settings.relayEnabled,
                        actions::setInternet,
                        if (state.relayDeviceRevoked) {
                            R.string.error_relay_device_revoked
                        } else {
                            R.string.settings_internet_connection_description
                        },
                    )
                }
            }
            section {
                row {
                    Navigation(
                        R.string.settings_permissions_background,
                        null,
                    ) { actions.open(SettingsPage.PERMISSIONS) }
                }
                row { Navigation(R.string.settings_language, languageValue) { actions.open(SettingsPage.LANGUAGE) } }
            }
            dataSection(dataTitle, state.relayAvailable, onDataAction)
        }
    }
}

@Composable
private fun Switch(
    title: Int,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: Int,
) = HLSwitchRow(
    stringResource(title),
    checked,
    onChange,
    unavailableReason = null,
    description = stringResource(description),
)

@Composable
private fun Navigation(
    title: Int,
    value: String?,
    onClick: () -> Unit,
) = HLNavigationRow(stringResource(title), value, onClick)

/** Field 2 with its status: the description, "Auto-send isn't on yet" (E8), or field 3 "Agreed on …". */
@Composable
private fun AutoSendSwitch(
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
    HLSwitchRow(
        stringResource(R.string.settings_auto_send),
        state.settings.clipAutoSend,
        onChange,
        unavailableReason = null,
        description = description,
    )
}

/**
 * Field 7: the switch, disabled with "Not supported on this phone" without telephony; while it is on with a
 * permission missing, the status under it and "Grant Permission" or "Open Settings" (SET-01 fields 11, 16).
 */
private fun HLGroupedListScope.smsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val status = state.smsStatus
    section {
        row { SmsSwitch(state.settings.smsEnabled, status, actions::setSms) }
        smsPermissionAction(status)?.let { label ->
            row { HLActionRow(stringResource(label), false, actions::grantSms) }
        }
    }
}

@Composable
private fun SmsSwitch(
    enabled: Boolean,
    status: FeatureStatus,
    onChange: (Boolean) -> Unit,
) {
    val unsupported = status == FeatureStatus.UNSUPPORTED
    HLSwitchRow(
        stringResource(R.string.settings_sms_messages),
        enabled && !unsupported,
        onChange,
        unavailableReason = if (unsupported) stringResource(status.label) else null,
        description = status.takeIf { it.needsAction }?.let { stringResource(it.label) },
    )
}

/** The button a feature card shows for [status]: field 11 "Grant Permission" or field 16 "Open Settings". */
fun smsPermissionAction(status: FeatureStatus): Int? =
    when (status) {
        FeatureStatus.NEEDS_PERMISSION -> R.string.permission_grant
        FeatureStatus.PERMISSION_DENIED -> R.string.common_open_settings
        else -> null
    }

private val FeatureStatus.needsAction: Boolean
    get() = this == FeatureStatus.NEEDS_PERMISSION || this == FeatureStatus.PERMISSION_DENIED

/** Fields 26–27, each behind its confirmation (field 28); "Remove Device from Server" only in a build with a relay. */
private fun HLGroupedListScope.dataSection(
    title: String,
    relayAvailable: Boolean,
    onAction: (DataAction) -> Unit,
) {
    section(title = title) {
        if (relayAvailable) {
            row {
                HLActionRow(stringResource(R.string.settings_remove_from_server), destructive = true) {
                    onAction(DataAction.REMOVE_FROM_SERVER)
                }
            }
        }
        row {
            HLActionRow(stringResource(R.string.settings_delete_all_data), destructive = true) {
                onAction(DataAction.DELETE_ALL)
            }
        }
    }
}

/** Field 6 choices: "Off", "After 1 Minute", "After 5 Minutes". */
fun autoClearLabel(seconds: Int): Int =
    when (seconds) {
        0 -> R.string.common_off
        AUTO_CLEAR_5_MIN -> R.string.settings_auto_clear_5_min
        else -> R.string.settings_auto_clear_1_min
    }

const val AUTO_CLEAR_5_MIN = 300

/** Fields 1–6: the Clipboard group with its footnote about auto-clear. */
private fun HLGroupedListScope.clipboardSection(
    title: String,
    footer: String,
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val settings = state.settings
    section(title = title, footer = footer) {
        row {
            Switch(
                R.string.settings_sync_clipboard,
                settings.clipboardEnabled,
                actions::setClipboard,
                R.string.settings_sync_clipboard_description,
            )
        }
        row { AutoSendSwitch(state, actions::setAutoSend) }
        row {
            Switch(
                R.string.settings_sync_images,
                settings.clipSendImages,
                actions::setSendImages,
                R.string.settings_sync_images_description,
            )
        }
        row {
            Switch(
                R.string.settings_block_sensitive,
                settings.clipBlockSensitive,
                actions::setBlockSensitive,
                R.string.settings_block_sensitive_description,
            )
        }
        row {
            Navigation(R.string.settings_auto_clear, stringResource(autoClearLabel(settings.clipAutoClearSeconds))) {
                actions.open(SettingsPage.AUTO_CLEAR)
            }
        }
    }
}
