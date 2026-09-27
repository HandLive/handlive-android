package app.handlive.android.feature.relay

import kotlin.random.Random

/** `RECONNECT_BACKOFF` (0.10): 0.5 → 1 → 2 → 4 → 8 → 16 → 30 s after failed attempts, each ±20 % jitter. */
internal object RelayBackoff {
    /** Doublings after which the cap is reached (0.5 s × 2⁶ = 32 s > 30 s). */
    private const val MAX_DOUBLINGS = 6

    /** The wait after [attempt] failed attempts in a row (0 = the first retry). */
    fun delayMillis(
        attempt: Int,
        random: Random,
    ): Long {
        val doublings = attempt.coerceIn(0, MAX_DOUBLINGS)
        val base = (RelayConstants.BACKOFF_FIRST_MILLIS shl doublings).coerceAtMost(RelayConstants.BACKOFF_MAX_MILLIS)
        val jitter = 1 + (random.nextDouble() * 2 - 1) * RelayConstants.BACKOFF_JITTER
        return (base * jitter).toLong()
    }
}
