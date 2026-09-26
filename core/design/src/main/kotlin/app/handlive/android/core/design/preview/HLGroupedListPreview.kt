package app.handlive.android.core.design.preview

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.theme.HandLiveAppearance
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R

/** A shortened Android Settings tab with catalog texts (2-patterns/04-cai-dat.md). */
@Composable
internal fun HLGroupedListPreviewGallery() {
    val sections =
        PreviewTexts(
            clipboard = stringResource(R.string.settings_clipboard),
            footer = stringResource(R.string.settings_auto_clear_footer),
            sync = stringResource(R.string.settings_sync_clipboard),
            blockSensitive = stringResource(R.string.settings_block_sensitive),
            autoClear = stringResource(R.string.settings_auto_clear),
            autoClearValue = stringResource(R.string.settings_auto_clear_1_min),
            internet = stringResource(R.string.settings_internet_connection),
            offReason = stringResource(R.string.pairing_reason_off_on_device, SAMPLE_DEVICE_NAME),
            devices = stringResource(R.string.pairing_devices),
            addDevice = stringResource(R.string.pairing_add_device),
            unpair = stringResource(R.string.pairing_unpair),
        )
    HLGroupedList {
        section(title = sections.clipboard, footer = sections.footer) {
            switchRow(title = sections.sync, checked = true, onCheckedChange = {})
            switchRow(title = sections.blockSensitive, checked = false, onCheckedChange = {})
            navigationRow(title = sections.autoClear, value = sections.autoClearValue, onClick = {})
        }
        section(title = sections.internet) {
            switchRow(
                title = sections.internet,
                checked = false,
                onCheckedChange = {},
                unavailableReason = sections.offReason,
            )
        }
        section(title = sections.devices) {
            actionRow(title = sections.addDevice, onClick = {})
            actionRow(title = sections.unpair, onClick = {}, destructive = true)
        }
    }
}

/** Texts resolved in composition, handed to the non-composable list builder. */
private data class PreviewTexts(
    val clipboard: String,
    val footer: String,
    val sync: String,
    val blockSensitive: String,
    val autoClear: String,
    val autoClearValue: String,
    val internet: String,
    val offReason: String,
    val devices: String,
    val addDevice: String,
    val unpair: String,
)

@Preview(widthDp = 360, heightDp = 640)
@Composable
private fun HLGroupedListPreview(
    @PreviewParameter(HandLiveAppearancePreviewProvider::class) appearance: HandLiveAppearance,
) {
    HandLiveTheme(darkTheme = appearance.isDark, highContrast = appearance.isHighContrast) {
        HLGroupedListPreviewGallery()
    }
}
