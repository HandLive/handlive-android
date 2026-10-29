package app.handlive.android.feature.connection.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dựng lại khi `STATE_ON` (điều kiện 2 của spike G7), bằng adapter giả — không đụng radio thật. */
class BluetoothLinkLifecycleTest {
    @Test
    fun startsOnceWhenTheAdapterTurnsOn() {
        var starts = 0
        val lifecycle = BluetoothLinkLifecycle(start = { starts++ }, stop = {})
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        assertEquals(1, starts)
        assertTrue(lifecycle.isRunning)
    }

    @Test
    fun doesNotRestartWhileAlreadyRunning() {
        var starts = 0
        val lifecycle = BluetoothLinkLifecycle(start = { starts++ }, stop = {})
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        assertEquals(1, starts)
    }

    @Test
    fun stopsWhenTheAdapterTurnsOff() {
        var stops = 0
        val lifecycle = BluetoothLinkLifecycle(start = {}, stop = { stops++ })
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        lifecycle.onAdapterState(BluetoothAdapterState.OFF)
        assertEquals(1, stops)
        assertFalse(lifecycle.isRunning)
    }

    @Test
    fun stopsOnTurningOffTooSoItDoesNotKeepAdvertisingDuringTheTransition() {
        var stops = 0
        val lifecycle = BluetoothLinkLifecycle(start = {}, stop = { stops++ })
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        lifecycle.onAdapterState(BluetoothAdapterState.TURNING_OFF)
        assertEquals(1, stops)
    }

    @Test
    fun doesNotStopTwiceOrStartOnTurningOn() {
        var starts = 0
        var stops = 0
        val lifecycle = BluetoothLinkLifecycle(start = { starts++ }, stop = { stops++ })
        lifecycle.onAdapterState(BluetoothAdapterState.OFF)
        lifecycle.onAdapterState(BluetoothAdapterState.TURNING_ON)
        assertEquals(0, starts)
        assertEquals(0, stops)
    }

    @Test
    fun flappingOffAndOnRestartsEachTime() {
        var starts = 0
        var stops = 0
        val lifecycle = BluetoothLinkLifecycle(start = { starts++ }, stop = { stops++ })
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        lifecycle.onAdapterState(BluetoothAdapterState.OFF)
        lifecycle.onAdapterState(BluetoothAdapterState.ON)
        assertEquals(2, starts)
        assertEquals(1, stops)
    }
}
