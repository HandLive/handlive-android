package app.handlive.android.feature.pairing.exchange

import app.handlive.android.feature.pairing.invite.PairingInvite
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

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
        /** Called once, by the Bluetooth failure that uses the PIN up ([failBluetoothAttempt]). */
        private val onUsedUpOverBluetooth: (Pin) -> Unit = {},
    ) : PairingWindow(expiresAt) {
        @Volatile
        private var entry = CompletableDeferred<String>()

        /** The confirmed PIN right now, without waiting (unlike [awaitPin]): Bluetooth needs the digits the instant
         * `cpace1` arrives (0.4.5 "PIN", A7.2-C), not a suspend point; `null` while A3 is still waiting for one. */
        @Volatile
        var confirmedPin: String? = null
            private set

        /** The user typed the PIN shown on the Mac (A3). */
        fun submit(value: String) {
            if (!entry.complete(value)) entry = CompletableDeferred(value)
            confirmedPin = value
        }

        /** The user confirmed a PIN that no `PIN_INVALID` has discarded yet. */
        override val hasSecret: Boolean get() = entry.isCompleted

        /** Waits for the PIN; the client's connection stays open meanwhile. */
        suspend fun awaitPin(): String = entry.await()

        /** After `PIN_INVALID` the next attempt needs a new PIN. */
        fun reset() {
            confirmedPin = null
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

        /**
         * A Bluetooth channel failed after `cpace1` (0.4.5 "PIN", C3): one attempt on the same counter as the LAN
         * offers, so both transports together get [MAX_OFFERS]. The attempt that uses the PIN up closes the window
         * through [onUsedUpOverBluetooth]; one past it changes nothing.
         */
        fun failBluetoothAttempt() {
            if (takeOffer() && offersLeft == 0) onUsedUpOverBluetooth(this)
        }

        companion object {
            /** `PIN_MAX_ATTEMPTS` (0.10): the Mac's 3 attempts, one offer each — shared with Bluetooth channels
             * that fail after `cpace1` (0.4.5 "PIN", C3; [failBluetoothAttempt]). */
            const val MAX_OFFERS = 3

            /** `BT_PIN_LOCKOUT` (0.10): a window closed by [MAX_OFFERS] failures blocks a new one, LAN or
             * Bluetooth, for this long (PAIR-01 E10). */
            val LOCKOUT_DURATION_MILLIS = 5.minutes.inWholeMilliseconds
        }
    }

    companion object {
        const val DURATION_MILLIS = 120_000L
    }
}
