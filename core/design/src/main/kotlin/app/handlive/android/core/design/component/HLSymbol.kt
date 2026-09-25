package app.handlive.android.core.design.component

import androidx.annotation.DrawableRes
import app.handlive.android.core.design.R

/**
 * Material Symbols Rounded bundled as vector drawables (Apache 2.0, `assets/licenses/MaterialSymbols-Apache-2.0.txt`):
 * weight 400, grade 0, optical size 24, in the outline (`FILL` 0) and filled (`FILL` 1) variants. Names follow the
 * SF Symbol mapping of design system "Biểu tượng" (`1-foundations/06-bieu-tuong.md`).
 */
enum class HLSymbol(
    @param:DrawableRes val outline: Int,
    @param:DrawableRes val filled: Int,
) {
    Accessibility(R.drawable.ic_symbol_accessibility, R.drawable.ic_symbol_accessibility_fill),
    BatteryFull(R.drawable.ic_symbol_battery_full, R.drawable.ic_symbol_battery_full_fill),
    Check(R.drawable.ic_symbol_check, R.drawable.ic_symbol_check_fill),
    CheckCircle(R.drawable.ic_symbol_check_circle, R.drawable.ic_symbol_check_circle_fill),
    ChevronLeft(R.drawable.ic_symbol_chevron_left, R.drawable.ic_symbol_chevron_left_fill),
    ChevronRight(R.drawable.ic_symbol_chevron_right, R.drawable.ic_symbol_chevron_right_fill),
    Close(R.drawable.ic_symbol_close, R.drawable.ic_symbol_close_fill),
    ContentCopy(R.drawable.ic_symbol_content_copy, R.drawable.ic_symbol_content_copy_fill),
    ContentPaste(R.drawable.ic_symbol_content_paste, R.drawable.ic_symbol_content_paste_fill),
    Delete(R.drawable.ic_symbol_delete, R.drawable.ic_symbol_delete_fill),
    Devices(R.drawable.ic_symbol_devices, R.drawable.ic_symbol_devices_fill),
    Error(R.drawable.ic_symbol_error, R.drawable.ic_symbol_error_fill),
    Image(R.drawable.ic_symbol_image, R.drawable.ic_symbol_image_fill),
    Info(R.drawable.ic_symbol_info, R.drawable.ic_symbol_info_fill),
    Language(R.drawable.ic_symbol_language, R.drawable.ic_symbol_language_fill),
    LaptopMac(R.drawable.ic_symbol_laptop_mac, R.drawable.ic_symbol_laptop_mac_fill),
    Lock(R.drawable.ic_symbol_lock, R.drawable.ic_symbol_lock_fill),
    MobileOff(R.drawable.ic_symbol_mobile_off, R.drawable.ic_symbol_mobile_off_fill),
    MoreHoriz(R.drawable.ic_symbol_more_horiz, R.drawable.ic_symbol_more_horiz_fill),
    Notifications(R.drawable.ic_symbol_notifications, R.drawable.ic_symbol_notifications_fill),
    PhoneIphone(R.drawable.ic_symbol_phone_iphone, R.drawable.ic_symbol_phone_iphone_fill),
    Pin(R.drawable.ic_symbol_pin, R.drawable.ic_symbol_pin_fill),
    Public(R.drawable.ic_symbol_public, R.drawable.ic_symbol_public_fill),
    QrCode(R.drawable.ic_symbol_qr_code_2, R.drawable.ic_symbol_qr_code_2_fill),
    QrCodeScanner(R.drawable.ic_symbol_qr_code_scanner, R.drawable.ic_symbol_qr_code_scanner_fill),
    Sensors(R.drawable.ic_symbol_sensors, R.drawable.ic_symbol_sensors_fill),
    SensorsOff(R.drawable.ic_symbol_sensors_off, R.drawable.ic_symbol_sensors_off_fill),
    Settings(R.drawable.ic_symbol_settings, R.drawable.ic_symbol_settings_fill),
    Smartphone(R.drawable.ic_symbol_smartphone, R.drawable.ic_symbol_smartphone_fill),
    TabletMac(R.drawable.ic_symbol_tablet_mac, R.drawable.ic_symbol_tablet_mac_fill),
    Timer(R.drawable.ic_symbol_timer, R.drawable.ic_symbol_timer_fill),
    Usb(R.drawable.ic_symbol_usb, R.drawable.ic_symbol_usb_fill),
    VisibilityOff(R.drawable.ic_symbol_visibility_off, R.drawable.ic_symbol_visibility_off_fill),
    Warning(R.drawable.ic_symbol_warning, R.drawable.ic_symbol_warning_fill),
    Wifi(R.drawable.ic_symbol_wifi, R.drawable.ic_symbol_wifi_fill),
    WifiFind(R.drawable.ic_symbol_wifi_find, R.drawable.ic_symbol_wifi_find_fill),
    WifiOff(R.drawable.ic_symbol_wifi_off, R.drawable.ic_symbol_wifi_off_fill),
}
