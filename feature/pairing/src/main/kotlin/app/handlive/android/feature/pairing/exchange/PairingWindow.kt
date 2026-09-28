package app.handlive.android.feature.pairing.exchange

import app.handlive.android.feature.pairing.invite.PairingInvite
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * An open pairing window (PAIR-01 step 6, A4): `/v1/pair` accepts one client while it is open and not expired
 * (`PAIRING_WINDOW` = 120 s). A QR window knows the client's key and `pairing_secret` from the code; a PIN window
 * receives the PIN the user types on the phone, possibly again after a wrong one.
 */
sealed class PairingWindow(
    val expiresAt: Long,
) {
    private val claimed = AtomicBoolean(false)

    /** One client per window (API 2 rule 4); `false` when another connection already took it. */
    fun claim(): Boolean = claimed.compareAndSet(false, true)

    /** The window takes a new client after a lost connection or, for a PIN window, after `PIN_INVALID`. */
    fun release() = claimed.set(false)

    fun isOpen(now: Long): Boolean = now < expiresAt

    /** The key material the offer needs is here: always for a QR code, for a PIN once the user confirmed it (A3). */
    open val hasSecret: Boolean get() = true

    class Qr(
        val invite: PairingInvite,
        expiresAt: Long,
    ) : PairingWindow(expiresAt)

    class Pin(
        expiresAt: Long,
    ) : PairingWindow(expiresAt) {
        @Volatile
        private var entry = CompletableDeferred<String>()

        /** The user typed the PIN shown on the Mac (A3). */
        fun submit(value: String) {
            if (!entry.complete(value)) entry = CompletableDeferred(value)
        }

        /** The user confirmed a PIN that no `PIN_INVALID` has discarded yet. */
        override val hasSecret: Boolean get() = entry.isCompleted

        /** Waits for the PIN; the client's connection stays open meanwhile. */
        suspend fun awaitPin(): String = entry.await()

        /** After `PIN_INVALID` the next attempt needs a new PIN. */
        fun reset() {
            entry = CompletableDeferred()
        }

        private val offers = AtomicInteger(0)

        /**
         * Takes one of the [MAX_OFFERS] `pair/offer` this PIN allows (A4, `PIN_MAX_ATTEMPTS`); `false` once they are
         * used up. The phone counts its own offers and never trusts the client's `attempts_left`.
         */
        fun takeOffer(): Boolean = offers.incrementAndGet() <= MAX_OFFERS

        /** Offers left for this PIN, shown after a wrong PIN (field 7). */
        val offersLeft: Int get() = (MAX_OFFERS - offers.get()).coerceAtLeast(0)

        companion object {
            /** `PIN_MAX_ATTEMPTS` (0.10): the Mac's 3 attempts, one offer each. */
            const val MAX_OFFERS = 3
        }
    }

    companion object {
        const val DURATION_MILLIS = 120_000L
    }
}
