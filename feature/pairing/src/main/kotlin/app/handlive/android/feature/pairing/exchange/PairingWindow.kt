package app.handlive.android.feature.pairing.exchange

import app.handlive.android.feature.pairing.invite.PairingInvite
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicBoolean

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

    /** A PIN window reopens for the next attempt after `PIN_INVALID`. */
    fun release() = claimed.set(false)

    fun isOpen(now: Long): Boolean = now < expiresAt

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

        /** Waits for the PIN; the client's connection stays open meanwhile. */
        suspend fun awaitPin(): String = entry.await()

        /** After `PIN_INVALID` the next attempt needs a new PIN. */
        fun reset() {
            entry = CompletableDeferred()
        }
    }

    companion object {
        const val DURATION_MILLIS = 120_000L
    }
}
