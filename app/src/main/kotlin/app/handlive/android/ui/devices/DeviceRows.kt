package app.handlive.android.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.design.component.HLConnectionStatus
import app.handlive.android.core.design.component.HLGroupedRow
import app.handlive.android.core.design.component.HLIcon
import app.handlive.android.core.design.component.HLStatusIndicator
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.feature.pairing.devices.DeviceLink
import app.handlive.android.feature.pairing.devices.DeviceListItem

private val RowHeight = 72.dp
private val IconTile = 40.dp

/**
 * DeviceRow on Android (72 dp): the platform symbol on a round `tertiary-system-fill` tile, the name on one line
 * truncated with "…", the link status, and a chevron into the details. No `pair_id`, keys or addresses.
 */
@Composable
fun DeviceRow(
    item: DeviceListItem,
    onClick: () -> Unit,
) {
    val colors = HandLiveTheme.colors
    HLGroupedRow(modifier = Modifier.heightIn(min = RowHeight).clickable(role = Role.Button, onClick = onClick)) {
        Box(
            modifier = Modifier.size(IconTile).clip(CircleShape).background(colors.tertiarySystemFill),
            contentAlignment = Alignment.Center,
        ) {
            HLIcon(symbol = item.platform.symbol, contentDescription = null, tint = colors.label)
        }
        Column(modifier = Modifier.weight(1f)) {
            BasicText(
                text = item.name,
                style = HandLiveTheme.typography.body.copy(color = colors.label),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            HLStatusIndicator(status = item.link.status, deviceName = item.name)
        }
        HLIcon(symbol = HLSymbol.ChevronRight, contentDescription = null, tint = colors.tertiaryLabel)
    }
}

/** DeviceRow icons: `laptop_mac`, `phone_iphone`, `tablet_mac` (Icons table). */
val PeerPlatform.symbol: HLSymbol
    get() =
        when (this) {
            PeerPlatform.MACOS -> HLSymbol.LaptopMac
            PeerPlatform.IOS -> HLSymbol.PhoneIphone
            PeerPlatform.IPADOS -> HLSymbol.TabletMac
        }

/** PAIR-02 field 4 as the phone sees it. */
val DeviceLink.status: HLConnectionStatus
    get() =
        when (this) {
            DeviceLink.WIFI -> HLConnectionStatus.ConnectedWiFi
            DeviceLink.INTERNET -> HLConnectionStatus.ConnectedInternet
            DeviceLink.USB -> HLConnectionStatus.Usb
            DeviceLink.DISCONNECTED -> HLConnectionStatus.Disconnected
        }
