package app.handlive.android.core.design.component

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import app.handlive.android.core.design.theme.HandLiveColors
import app.handlive.android.core.strings.R

/**
 * Link state between the phone and a paired client, as the phone can see it (`components/StatusIndicator/README.md`,
 * 0.11). The phone is the server: it never shows "Điện thoại ngoại tuyến" or "Cần ghép nối lại" — those states
 * belong to the Mac and iPhone — and the camera state arrives with Phase 5.
 */
enum class HLConnectionStatus {
    ConnectedWiFi,
    ConnectedInternet,
    Usb,
    Connecting,

    /** Not connected ("Mất kết nối" / "Disconnected"). */
    Disconnected,
}

/** Full status text (`status.*`). */
@get:StringRes
internal val HLConnectionStatus.textRes: Int
    get() =
        when (this) {
            HLConnectionStatus.ConnectedWiFi -> R.string.status_connected_wifi
            HLConnectionStatus.ConnectedInternet -> R.string.status_connected_internet
            HLConnectionStatus.Usb -> R.string.status_connected_usb
            HLConnectionStatus.Connecting -> R.string.status_connecting
            HLConnectionStatus.Disconnected -> R.string.status_disconnected
        }

/** TalkBack sentence with the peer's name (`status.*_to`), only for the connected states. */
@get:StringRes
internal val HLConnectionStatus.withDeviceRes: Int?
    get() =
        when (this) {
            HLConnectionStatus.ConnectedWiFi -> R.string.status_connected_wifi_to
            HLConnectionStatus.ConnectedInternet -> R.string.status_connected_internet_to
            HLConnectionStatus.Usb -> R.string.status_connected_usb_to
            HLConnectionStatus.Connecting, HLConnectionStatus.Disconnected -> null
        }

/** The pulsing dot (`duration-pulse`) belongs to "Đang kết nối…" only on the phone. */
internal val HLConnectionStatus.pulses: Boolean
    get() = this == HLConnectionStatus.Connecting

/** Symbol of the states that do not pulse (StatusIndicator README): `wifi`, `public`, `usb`, `wifi_off`. */
internal val HLConnectionStatus.symbol: HLSymbol?
    get() =
        when (this) {
            HLConnectionStatus.ConnectedWiFi -> HLSymbol.Wifi
            HLConnectionStatus.ConnectedInternet -> HLSymbol.Public
            HLConnectionStatus.Usb -> HLSymbol.Usb
            HLConnectionStatus.Disconnected -> HLSymbol.WifiOff
            HLConnectionStatus.Connecting -> null
        }

/** Connected is green, connecting orange, disconnected grey — never red for an ordinary disconnection. */
internal fun HLConnectionStatus.tint(colors: HandLiveColors): Color =
    when (this) {
        HLConnectionStatus.ConnectedWiFi,
        HLConnectionStatus.ConnectedInternet,
        HLConnectionStatus.Usb,
        -> colors.statusConnected

        HLConnectionStatus.Connecting -> colors.statusConnecting

        HLConnectionStatus.Disconnected -> colors.statusOffline
    }
