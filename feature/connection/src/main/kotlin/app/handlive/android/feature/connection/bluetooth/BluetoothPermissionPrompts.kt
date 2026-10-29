package app.handlive.android.feature.connection.bluetooth

import app.handlive.android.core.transport.bluetooth.BluetoothLinkConstants
import kotlin.time.Duration

/**
 * Phần Bluetooth của A7.7 (SET-01 API 10): hỏi "Nearby devices" tối đa một lần (API 31+, cần cho quét/quảng bá
 * BLE — trước đó `ACCESS_FINE_LOCATION` đã đủ); xin bật Bluetooth (`ACTION_REQUEST_ENABLE`) tối đa một lần mỗi
 * [BluetoothLinkConstants.ENABLE_PROMPT_INTERVAL], chỉ khi phone không có đường LAN. Chỉ quyết định NÊN hỏi lúc
 * nào; hiển thị dialog/Activity thật và lưu trạng thái đã hỏi là việc của lớp gọi.
 */
class BluetoothPermissionPrompts(
    private val promptInterval: Duration = BluetoothLinkConstants.ENABLE_PROMPT_INTERVAL,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** API 31 (Android 12): `BLUETOOTH_ADVERTISE`/`BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` thay cho vị trí cũ. */
    fun needsNearbyDevicesPermission(apiLevel: Int): Boolean = apiLevel >= API_31

    /** Hỏi khi: cần quyền ở API này, chưa có quyền, và chưa hỏi lần nào (một lần duy nhất, không hỏi lại). */
    fun shouldAskNearbyDevices(
        apiLevel: Int,
        granted: Boolean,
        alreadyAsked: Boolean,
    ): Boolean = needsNearbyDevicesPermission(apiLevel) && !granted && !alreadyAsked

    /** Hỏi khi: không có LAN, Bluetooth đang tắt, và (chưa hỏi lần nào hoặc đã qua [promptInterval] từ lần trước). */
    fun shouldRequestEnable(
        hasLan: Boolean,
        bluetoothOn: Boolean,
        lastAskedAt: Long,
    ): Boolean =
        !hasLan && !bluetoothOn && (lastAskedAt <= 0L || clock() - lastAskedAt >= promptInterval.inWholeMilliseconds)

    private companion object {
        const val API_31 = 31
    }
}
