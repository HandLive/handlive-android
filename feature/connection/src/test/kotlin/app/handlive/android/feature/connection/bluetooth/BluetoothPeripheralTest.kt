package app.handlive.android.feature.connection.bluetooth

import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.transport.bluetooth.BluetoothAdvertisementData
import app.handlive.android.core.transport.bluetooth.BluetoothLinkIo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quảng bá + server L2CAP của A-SVC (0.4.5 "Advertisement", "Rotation"; điều kiện 2 của spike G7) qua một radio
 * giả: PSM của server đang mở nằm trong service data, hint đổi ở mỗi ranh giới `BT_HINT_PERIOD` cùng PSM và bộ
 * quảng bá, tắt/bật Bluetooth dựng lại tất cả. Thời gian ảo của kotlinx-coroutines-test, không radio thật.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BluetoothPeripheralTest {
    private val period = BluetoothAdvertisementData.HINT_PERIOD_MILLIS
    private val key1 = ByteArray(32) { 1 }
    private val key2 = ByteArray(32) { 2 }
    private val key3 = ByteArray(32) { 3 }

    private class FakeServer(
        override val psm: Int,
    ) : BluetoothServerChannel {
        val incoming = Channel<BluetoothLinkIo>(Channel.UNLIMITED)
        var closed = false

        override suspend fun accept(): BluetoothLinkIo = incoming.receive()

        override fun close() {
            closed = true
            incoming.close()
        }
    }

    private class FakeRadio : BluetoothRadio {
        private var nextPsm = FIRST_PSM
        val servers = mutableListOf<FakeServer>()
        val advertisedHistory = mutableListOf<ByteArray>()
        var advertising: ByteArray? = null

        override suspend fun listen(): BluetoothServerChannel = FakeServer(nextPsm++).also { servers += it }

        override fun startAdvertising(serviceData: ByteArray) {
            check(advertising == null) { "the previous advertising set was not stopped" }
            advertising = serviceData
            advertisedHistory += serviceData
        }

        override fun stopAdvertising() {
            advertising = null
        }
    }

    private object NoIo : BluetoothLinkIo {
        override suspend fun readPreauthRecord(): ByteArray = awaitCancellation()

        override suspend fun readRecord(): ByteArray = awaitCancellation()

        override suspend fun writeRecord(body: ByteArray) = Unit

        override fun close() = Unit
    }

    /** Starts at 10 ms into epoch [EPOCH] so the first boundary is one period minus 10 ms away. */
    private fun TestScope.clock(): () -> Long = { EPOCH * period + START_OFFSET + testScheduler.currentTime }

    private fun expected(
        psm: Int,
        epoch: Long,
        vararg keys: ByteArray,
    ) = Hex.encode(BluetoothAdvertisementData.serviceData(psm, keys.map { BluetoothAdvertisementData.hint(it, epoch) }))

    private fun FakeRadio.current() = advertising?.let(Hex::encode)

    @Test
    fun advertisesThePsmOfTheOpenServerAndTheHintsOfTheCurrentEpoch() =
        runTest {
            val radio = FakeRadio()
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            peripheral.start(backgroundScope, MutableStateFlow(listOf(key1, key2)))
            runCurrent()
            assertEquals(expected(FIRST_PSM, EPOCH, key1, key2), radio.current())
            peripheral.stop()
        }

    @Test
    fun atMostTwoHintsAreAdvertisedMostRecentPairsFirst() =
        runTest {
            val radio = FakeRadio()
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            peripheral.start(backgroundScope, MutableStateFlow(listOf(key1, key2, key3)))
            runCurrent()
            assertEquals(expected(FIRST_PSM, EPOCH, key1, key2), radio.current())
            peripheral.stop()
        }

    @Test
    fun noPairToAdvertiseMeansNoServerAndNoAdvertising() =
        runTest {
            val radio = FakeRadio()
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            peripheral.start(backgroundScope, MutableStateFlow(emptyList()))
            runCurrent()
            assertNull(radio.advertising)
            assertTrue(radio.servers.isEmpty())
            peripheral.stop()
        }

    @Test
    fun hintPsmAndAdvertisingSetRotateTogetherAtTheEpochBoundaryAndLinksStayOpen() =
        runTest {
            val radio = FakeRadio()
            val linkCancelled = CompletableDeferred<Boolean>()
            val peripheral =
                BluetoothPeripheral(
                    radio,
                    accept = {
                        try {
                            awaitCancellation()
                        } finally {
                            linkCancelled.complete(true)
                        }
                    },
                    clock = clock(),
                )
            peripheral.start(backgroundScope, MutableStateFlow(listOf(key1)))
            runCurrent()
            val first = radio.servers.single()
            first.incoming.send(NoIo) // a link accepted before the rotation
            runCurrent()
            advanceTimeBy(period - START_OFFSET - 1)
            runCurrent()
            assertEquals(1, radio.servers.size)
            advanceTimeBy(2)
            runCurrent()
            assertEquals(2, radio.servers.size)
            assertTrue(radio.servers[0].closed)
            assertFalse(radio.servers[1].closed)
            assertEquals(expected(FIRST_PSM + 1, EPOCH + 1, key1), radio.current())
            assertFalse("an open link survives the rotation", linkCancelled.isCompleted)
            peripheral.stop()
        }

    @Test
    fun aChangedPairListRestartsTheSetWithANewServer() =
        runTest {
            val radio = FakeRadio()
            val keys = MutableStateFlow(listOf(key1))
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            peripheral.start(backgroundScope, keys)
            runCurrent()
            keys.value = listOf(key2, key1)
            runCurrent()
            assertTrue(radio.servers[0].closed)
            assertEquals(expected(FIRST_PSM + 1, EPOCH, key2, key1), radio.current())
            keys.value = emptyList()
            runCurrent()
            assertNull(radio.advertising)
            assertTrue(radio.servers.all { it.closed })
            peripheral.stop()
        }

    @Test
    fun eachAcceptedChannelGoesToTheAcceptor() =
        runTest {
            val radio = FakeRadio()
            val accepted = mutableListOf<BluetoothLinkIo>()
            val peripheral = BluetoothPeripheral(radio, accept = { accepted += it }, clock = clock())
            peripheral.start(backgroundScope, MutableStateFlow(listOf(key1)))
            runCurrent()
            val server = radio.servers.single()
            server.incoming.send(NoIo)
            server.incoming.send(NoIo)
            runCurrent()
            assertEquals(2, accepted.size)
            peripheral.stop()
        }

    @Test
    fun bluetoothOffStopsEverythingAndBluetoothOnRebuildsWithANewServer() =
        runTest {
            val radio = FakeRadio()
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            val keys = MutableStateFlow(listOf(key1))
            val lifecycle =
                BluetoothLinkLifecycle(
                    start = { peripheral.start(backgroundScope, keys) },
                    stop = peripheral::stop,
                )
            lifecycle.onAdapterState(BluetoothAdapterState.ON)
            runCurrent()
            assertEquals(expected(FIRST_PSM, EPOCH, key1), radio.current())
            lifecycle.onAdapterState(BluetoothAdapterState.TURNING_OFF)
            runCurrent()
            assertNull(radio.advertising)
            assertTrue(radio.servers.single().closed)
            lifecycle.onAdapterState(BluetoothAdapterState.ON)
            runCurrent()
            assertEquals(expected(FIRST_PSM + 1, EPOCH, key1), radio.current())
            peripheral.stop()
        }

    @Test
    fun aServerThatFailsIsReopenedAfterABriefPause() =
        runTest {
            val radio = FakeRadio()
            val peripheral = BluetoothPeripheral(radio, accept = {}, clock = clock())
            peripheral.start(backgroundScope, MutableStateFlow(listOf(key1)))
            runCurrent()
            radio.servers.single().close() // accept() now fails, as when the stack drops the socket
            runCurrent()
            assertNull(radio.advertising)
            advanceTimeBy(BluetoothPeripheral.RETRY_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(2, radio.servers.size)
            assertEquals(expected(FIRST_PSM + 1, EPOCH, key1), radio.current())
            peripheral.stop()
        }

    private companion object {
        const val FIRST_PSM = 0x0081
        const val EPOCH = 479_762L
        const val START_OFFSET = 10L
    }
}
