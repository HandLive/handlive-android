package app.handlive.android.feature.connection.session

import app.handlive.android.core.protocol.ack.Ack
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The `id`s a pair's envelopes already used and their `ack`s (0.5.1 rule 2), kept per pair rather than per session for
 * 5 minutes: the transport rejects a repeat anywhere in the key epoch (`SessionCipher.accept`); this ledger keeps the
 * `ack`s to resend and covers the next session:
 * when a client switches between the LAN and the relay and retries a request with the same `id` on its new session,
 * it gets the old `ack` back instead of the request being processed twice. Thread-safe.
 */
class EnvelopeLedger(
    clock: () -> Long,
) {
    private val lock = Mutex()
    private val cache = ProcessedEnvelopeCache(clock)

    /** `true` when [id] is new (and is now remembered); for a duplicate, `false` and its `ack` if one was sent. */
    suspend fun firstDelivery(id: String): Pair<Boolean, Ack?> =
        lock.withLock {
            val entry = cache.find(id)
            if (entry == null) {
                cache.remember(id)
                true to null
            } else {
                false to entry.ack
            }
        }

    suspend fun recordAck(ack: Ack) = lock.withLock { cache.recordAck(ack) }

    /** The `ack` sent for [id], if it is still kept. */
    suspend fun ackFor(id: String): Ack? = lock.withLock { cache.find(id)?.ack }
}
