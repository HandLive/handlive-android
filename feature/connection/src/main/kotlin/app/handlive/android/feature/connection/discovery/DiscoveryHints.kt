package app.handlive.android.feature.connection.discovery

import app.handlive.android.core.crypto.primitives.HkdfSha256
import app.handlive.android.core.crypto.primitives.HmacSha256
import java.nio.ByteBuffer

/**
 * mDNS discovery hints (0.4.1, C6): one hint per pair in TXT `h`, changing every hour so a stranger on the LAN
 * cannot follow the phone. `K_disc` = HKDF(`PRK`, info "handlive/v1/discovery"); hint = the first 8 lowercase hex
 * digits of HMAC-SHA256(`K_disc`, "HLDISC1" ‖ int64 BE `floor(now_ms / 3 600 000)`).
 */
object DiscoveryHints {
    const val INFO = "handlive/v1/discovery"
    const val HOUR_MILLIS = 3_600_000L
    const val MAX_HINTS = 8
    private val LABEL = "HLDISC1".toByteArray(Charsets.US_ASCII)
    private const val HINT_BYTES = 4

    fun key(prk: ByteArray): ByteArray = HkdfSha256.derive(ikm = prk, info = INFO)

    fun hourIndex(nowMillis: Long): Long = Math.floorDiv(nowMillis, HOUR_MILLIS)

    fun hint(
        discoveryKey: ByteArray,
        hour: Long,
    ): String {
        val message = LABEL + ByteBuffer.allocate(Long.SIZE_BYTES).putLong(hour).array()
        return HmacSha256.mac(discoveryKey, message).copyOf(HINT_BYTES).joinToString("") { "%02x".format(it) }
    }

    /** Hints of every active pair for the hour of [nowMillis], at most eight (TXT `h`). */
    fun forPairs(
        prks: List<ByteArray>,
        nowMillis: Long,
    ): List<String> {
        val hour = hourIndex(nowMillis)
        return prks.take(MAX_HINTS).map { hint(key(it), hour) }
    }

    /** Milliseconds until the next hour boundary, when every hint changes. */
    fun millisUntilNextHour(nowMillis: Long): Long = (hourIndex(nowMillis) + 1) * HOUR_MILLIS - nowMillis
}
