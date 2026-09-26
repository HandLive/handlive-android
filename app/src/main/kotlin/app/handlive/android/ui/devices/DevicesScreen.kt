package app.handlive.android.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.component.HLScreenHeader
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.devices.DeviceListItem
import app.handlive.android.ui.main.StatusBanners
import app.handlive.android.ui.main.bannerLabels
import app.handlive.android.ui.main.bannerSections

/**
 * The Devices tab (PAIR-02 on the phone): the paired Mac, iPhone and iPad as [DeviceRow]s, updated as sessions
 * change, and "Add Device" (field 11). Without a pair: the empty state with one next step.
 */
@Composable
fun DevicesScreen(
    items: List<DeviceListItem>,
    banners: StatusBanners,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        HLScreenHeader(title = stringResource(R.string.pairing_devices))
        if (items.isEmpty() && !banners.any) {
            EmptyDevices(onAdd)
        } else {
            val addLabel = stringResource(R.string.pairing_add_device)
            val labels = bannerLabels()
            HLGroupedList(modifier = Modifier.weight(1f)) {
                bannerSections(banners, labels)
                if (items.isEmpty()) {
                    section { row { EmptyDevices(onAdd) } }
                } else {
                    section {
                        items.forEach { item -> row { DeviceRow(item) { onOpen(item.pairId) } } }
                    }
                    section { actionRow(addLabel, onAdd) }
                }
            }
        }
    }
}

/** Empty state: a `brand-title` title, one sentence, one button (Writing, "Empty states"). */
@Composable
private fun EmptyDevices(onAdd: () -> Unit) {
    val colors = HandLiveTheme.colors
    val spacing = HandLiveTheme.spacing
    Column(
        modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(spacing.space24),
        verticalArrangement = Arrangement.spacedBy(spacing.space16),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = stringResource(R.string.pairing_empty_title_android),
            style = HandLiveTheme.typography.brandTitle.copy(color = colors.label, textAlign = TextAlign.Center),
            modifier = Modifier.semantics { heading() },
        )
        BasicText(
            text = stringResource(R.string.pairing_empty_body_android),
            style = HandLiveTheme.typography.body.copy(color = colors.secondaryLabel, textAlign = TextAlign.Center),
        )
        HLButton(stringResource(R.string.pairing_add_device), onAdd)
    }
}
