package app.handlive.android.feature.connection.discovery

import app.handlive.android.core.protocol.testing.Hex
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Hourly hints of 0.4.1 (C6). The expected values were computed independently with Python `hmac`/`hashlib` from the
 * PRKs of pair-prk.json before shared/test-vectors/discovery-hint.json carried them (DiscoveryHintVectorTest); the
 * Mac's HLCrypto tests use the same values, so a Mac finds the phone only if both sides agree byte for byte.
 */
class DiscoveryHintsTest {
    private val prks = SharedTestVectors.vectors("pair-prk.json").associate { it.str("name") to it.hex("prk") }

    @Test
    fun discoveryKeyAndHourlyHintsMatchTheIndependentValues() {
        CASES.forEach { case ->
            val key = DiscoveryHints.key(prks.getValue(case.pair))
            assertEquals(case.pair, case.kDisc, Hex.encode(key))
            case.hints.forEach { (hour, hint) ->
                assertEquals("${case.pair} hour $hour", hint, DiscoveryHints.hint(key, hour))
            }
        }
    }

    @Test
    fun hintsFollowTheHourOfTheClock() {
        val prk = prks.getValue("cặp 1")
        assertEquals(listOf("c3782785"), DiscoveryHints.forPairs(listOf(prk), 1_727_150_000_123))
        assertEquals(listOf("9b813dd8"), DiscoveryHints.forPairs(listOf(prk), 3_599_999))
        assertEquals(listOf("1786f385"), DiscoveryHints.forPairs(listOf(prk), 3_600_000))
        assertEquals(1L, DiscoveryHints.millisUntilNextHour(3_599_999))
        assertEquals(3_600_000L, DiscoveryHints.millisUntilNextHour(3_600_000))
    }

    @Test
    fun atMostEightHintsGoIntoTheTxtRecord() {
        val many = List(10) { index -> ByteArray(32) { index.toByte() } }
        assertEquals(8, DiscoveryHints.forPairs(many, 0).size)
    }

    private class Case(
        val pair: String,
        val kDisc: String,
        val hints: List<Pair<Long, String>>,
    )

    private companion object {
        val CASES =
            listOf(
                Case(
                    "cặp 1",
                    "8260d3f85773e4ce1c6047fa51bdc92bede9784df5e6311bb3068a5291ed448e",
                    listOf(479_763L to "c3782785", 479_762L to "99111c43", 0L to "9b813dd8", 1L to "1786f385"),
                ),
                Case(
                    "cặp 2",
                    "1e2fb5e7a71ef9e8afc8a44365204f6aea3a14b5fdbbdff6b225661b0051ce6a",
                    listOf(479_763L to "16075d63", 479_762L to "cc68a4a9", 0L to "56e8a69c", 1L to "9260b37d"),
                ),
            )
    }
}
