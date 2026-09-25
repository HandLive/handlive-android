package app.handlive.android.feature.connection.discovery

import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * discovery-hint.json (0.4.1, CONN-01 API 1–2): `K_disc`, the hint of every hour, the hour index and the TXT `h`
 * that [DiscoveryHints] and [MdnsTxtRecord] advertise at each clock value. The phone only advertises its current hour;
 * the client's rule (the hints of its previous, current and next hour, so a phone clock up to an hour ahead or behind
 * still matches) is rebuilt here from the same primitives to check the match scenarios and that no negative TXT
 * value is taken for its pair.
 */
class DiscoveryHintVectorTest {
    @Test
    fun keysHourlyHintsAndAdvertisedHintsMatch() {
        val keyVectors = vectors.filter { it.str("kind") == "key" }
        for (v in keyVectors) {
            val name = v.str("name")
            val key = DiscoveryHints.key(v.hex("prk"))
            assertEquals(name, v.str("info"), DiscoveryHints.INFO)
            assertEquals(name, v.str("k_disc"), Hex.encode(key))
            for (row in v.objects("hours")) {
                assertEquals(
                    "$name hour ${row.long("hour")}",
                    row.str("hint"),
                    DiscoveryHints.hint(key, row.long("hour")),
                )
            }
            for (row in v.objects("clock")) {
                val now = row.long("now_ms")
                assertEquals("$name at $now", row.long("hour"), DiscoveryHints.hourIndex(now))
                assertEquals(
                    "$name at $now",
                    listOf(row.str("advertised")),
                    DiscoveryHints.forPairs(listOf(prk(name)), now),
                )
                assertEquals("$name at $now", row.strings("accepted"), accepted(prk(name), now))
            }
        }
        assertEquals(2, keyVectors.size)
    }

    @Test
    fun txtRecordOfThePhoneMatchesTheClientsPair() {
        val matches = vectors.filter { it.str("kind") == "match" }
        for (v in matches) {
            val name = v.str("name")
            val hints = DiscoveryHints.forPairs(v.strings("phone_pairs").map(::prk), v.long("phone_now_ms"))
            val txt = MdnsTxtRecord(hints).attributes().getValue(MdnsTxtRecord.KEY_HINTS)
            assertEquals(name, v.str("txt_h"), txt)
            val accepted = accepted(prk(v.str("client_pair")), v.long("client_now_ms"))
            assertEquals(name, v.strings("accepted"), accepted)
            assertEquals(name, listOf(v.str("matched_hint")), txt.split(",").filter { it in accepted })
        }
        assertEquals(setOf("previous", "current", "next"), matches.map { it.str("matched_as") }.toSet())
    }

    @Test
    fun negativeHintsAreNotTakenForThePair() {
        for (n in invalid) {
            val name = n.str("name")
            val prk = prk(n.str("pair"))
            val accepted = accepted(prk, n.long("client_now_ms"))
            assertTrue(name, n.str("txt_h").split(",").none { it in accepted })
            if (n.str("reason") == "hint_outside_window") {
                // The phone really advertises this at its own clock: only the client's two-hour window rejects it.
                assertEquals(name, listOf(n.str("txt_h")), DiscoveryHints.forPairs(listOf(prk), n.long("phone_now_ms")))
            } else {
                // A hint computed the wrong way is never what the phone advertises at that hour.
                assertNotEquals(
                    name,
                    n.str("txt_h"),
                    DiscoveryHints.forPairs(listOf(prk), n.long("client_now_ms")).single(),
                )
            }
        }
    }

    private companion object {
        const val FILE = "discovery-hint.json"
        val vectors: List<JsonObject> by lazy { SharedTestVectors.vectors(FILE) }
        val invalid: List<JsonObject> by lazy { SharedTestVectors.invalidVectors(FILE) }

        /** `PRK` of a pair of pair-prk.json, as the `key` vector of that pair carries it. */
        fun prk(pair: String): ByteArray =
            vectors.single { it.str("kind") == "key" && it.str("name") == pair }.hex("prk")

        /** The client's rule of 0.4.1: the hints of its previous, current and next hour, in that order. */
        fun accepted(
            prk: ByteArray,
            nowMillis: Long,
        ): List<String> {
            val key = DiscoveryHints.key(prk)
            val hour = DiscoveryHints.hourIndex(nowMillis)
            return listOf(hour - 1, hour, hour + 1).map { DiscoveryHints.hint(key, it) }
        }

        fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
    }
}
