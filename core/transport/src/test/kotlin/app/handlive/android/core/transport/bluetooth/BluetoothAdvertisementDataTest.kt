package app.handlive.android.core.transport.bluetooth

import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.int
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Service data của scan response và hint quảng bá (0.4.5 "Advertisement"), đối chiếu bt-advertisement.json. */
class BluetoothAdvertisementDataTest {
    private val advertisement = SharedTestVectors.vectors("bt-advertisement.json")
    private val linkVectors = SharedTestVectors.vectors("bt-link.json")
    private val pair1Disc = linkVectors.first { it.str("kind") == "key" && it.str("name") == "cặp 1" }.hex("k_bt_disc")

    private fun hintsOf(vector: kotlinx.serialization.json.JsonObject): List<ByteArray> =
        vector.getValue("hints").jsonArray.map { Hex.decode(it.jsonPrimitive.content) }

    @Test
    fun serviceDataMatchesEveryScanResponseVector() {
        val scanResponses = advertisement.filter { it.str("kind") == "scan_response" }
        assertEquals(3, scanResponses.size)
        for (vector in scanResponses) {
            val data = BluetoothAdvertisementData.serviceData(vector.int("psm"), hintsOf(vector))
            assertArrayEquals(vector.str("name"), vector.hex("service_data_bytes"), data)
            assertEquals(vector.int("service_data_len"), data.size)
            assertEquals(vector.int("scan_response_total"), vector.int("ad_uuid_struct_bytes") + data.size)
        }
    }

    @Test
    fun pinWindowVectorCarriesOnlyThePinHint() {
        val vector = advertisement.first { it.str("kind") == "pin_window" }
        assertEquals(listOf(Hex.encode(BluetoothAdvertisementData.PIN_HINT)), hintsOf(vector).map(Hex::encode))
        val pinHint = BluetoothAdvertisementData.PIN_HINT
        val data = BluetoothAdvertisementData.serviceData(vector.int("psm"), listOf(pinHint))
        assertArrayEquals(vector.hex("service_data_bytes"), data)
    }

    @Test
    fun hintIsTheHmacOfTheEpochUnderKBtDisc() {
        val hintVector = linkVectors.first { it.str("kind") == "hint" }
        assertEquals(BluetoothAdvertisementData.HINT_PERIOD_MILLIS, hintVector.long("bt_hint_period_ms"))
        for (epoch in hintVector.objects("epochs")) {
            assertArrayEquals(epoch.hex("hint"), BluetoothAdvertisementData.hint(pair1Disc, epoch.long("epoch")))
        }
    }

    @Test
    fun theHintOfTheOutOfWindowVectorIsTheOneThisPhoneWouldAdvertise() {
        val outside =
            SharedTestVectors
                .invalidVectors("bt-advertisement.json")
                .first { it.str("reason") == "hint_outside_window" }
        val epoch = outside.long("advertised_epoch")
        assertArrayEquals(outside.hex("advertised_hint"), BluetoothAdvertisementData.hint(pair1Disc, epoch))
        assertArrayEquals(
            outside.hex("service_data_bytes"),
            BluetoothAdvertisementData.serviceData(PSM, listOf(BluetoothAdvertisementData.hint(pair1Disc, epoch))),
        )
    }

    @Test
    fun epochsChangeEveryFifteenMinutes() {
        val period = BluetoothAdvertisementData.HINT_PERIOD_MILLIS
        assertEquals(0L, BluetoothAdvertisementData.epoch(period - 1))
        assertEquals(1L, BluetoothAdvertisementData.epoch(period))
        assertEquals(1L, BluetoothAdvertisementData.millisUntilNextEpoch(period - 1))
        assertEquals(period, BluetoothAdvertisementData.millisUntilNextEpoch(period))
    }

    @Test
    fun moreThanTwoHintsOrAnOutOfRangePsmIsRefused() {
        val hint = ByteArray(BluetoothAdvertisementData.HINT_SIZE)
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothAdvertisementData.serviceData(PSM, listOf(hint, hint, hint))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BluetoothAdvertisementData.serviceData(0x1_0000, emptyList())
        }
    }

    @Test
    fun serviceUuidIsTheProductUuidNotTheSpikeOne() {
        assertEquals("3b3a01f9-ab27-44a8-bb68-b9b11a488bdb", BluetoothAdvertisementData.SERVICE_UUID.toString())
    }

    private companion object {
        const val PSM = 23456
    }
}
