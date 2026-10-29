package app.handlive.android.feature.connection.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.hours

/** A7.7 phần Bluetooth (SET-01 API 10): số lần hỏi quyền theo API level 31/33/37, chu kỳ xin bật Bluetooth. */
class BluetoothPermissionPromptsTest {
    private val prompts = BluetoothPermissionPrompts()

    @Test
    fun belowApi31NeverNeedsNearbyDevices() {
        assertFalse(prompts.needsNearbyDevicesPermission(apiLevel = 29))
        assertFalse(prompts.needsNearbyDevicesPermission(apiLevel = 30))
    }

    @Test
    fun api31And33And37NeedNearbyDevices() {
        assertTrue(prompts.needsNearbyDevicesPermission(apiLevel = 31))
        assertTrue(prompts.needsNearbyDevicesPermission(apiLevel = 33))
        assertTrue(prompts.needsNearbyDevicesPermission(apiLevel = 37))
    }

    @Test
    fun asksNearbyDevicesOnlyOnceOnApi31Plus() {
        assertTrue(prompts.shouldAskNearbyDevices(apiLevel = 31, granted = false, alreadyAsked = false))
        assertFalse(prompts.shouldAskNearbyDevices(apiLevel = 31, granted = false, alreadyAsked = true))
        assertFalse(prompts.shouldAskNearbyDevices(apiLevel = 31, granted = true, alreadyAsked = false))
        assertFalse(prompts.shouldAskNearbyDevices(apiLevel = 29, granted = false, alreadyAsked = false))
    }

    @Test
    fun requestsEnableOnlyWithoutLanAndWithoutBluetoothOn() {
        var now = 0L
        val p = BluetoothPermissionPrompts(clock = { now })
        assertTrue(p.shouldRequestEnable(hasLan = false, bluetoothOn = false, lastAskedAt = 0))
        assertFalse(p.shouldRequestEnable(hasLan = true, bluetoothOn = false, lastAskedAt = 0))
        assertFalse(p.shouldRequestEnable(hasLan = false, bluetoothOn = true, lastAskedAt = 0))
    }

    @Test
    fun waitsTheFullIntervalBeforeAskingAgain() {
        var now = 0L
        val p = BluetoothPermissionPrompts(promptInterval = 24.hours, clock = { now })
        // lastAskedAt > 0 here: a real previous ask, distinct from the "never asked" sentinel (0L) of the test above.
        val lastAskedAt = 1L
        now = lastAskedAt + 23.hours.inWholeMilliseconds
        assertFalse(p.shouldRequestEnable(hasLan = false, bluetoothOn = false, lastAskedAt))
        now = lastAskedAt + 24.hours.inWholeMilliseconds
        assertTrue(p.shouldRequestEnable(hasLan = false, bluetoothOn = false, lastAskedAt))
    }
}
