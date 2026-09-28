package app.handlive.android.core.transport.session

import app.handlive.android.core.transport.TransportConstants
import java.util.UUID

/**
 * The receiving side's `DEDUP_WINDOW` (0.5.1 rule 2, 0.10): every `id` accepted in the current key epoch and, while
 * the previous key is still accepted, the previous epoch's, each with the `ack` this side sent for it so a duplicate
 * gets that `ack` again for the whole epoch. A direction that reaches [maxIds] ids (the rekey did not complete)
 * overflows; the session is then closed 4410. Stored `ack` bodies are also bounded by [maxAckBytes], oldest dropped
 * first — a duplicate whose `ack` was dropped is still never processed again. Not thread-safe (the channel's lock).
 */
internal class ReplayWindow(
    private val maxIds: Int = TransportConstants.MAX_TRACKED_IDS,
    private val maxAckBytes: Long = TransportConstants.MAX_TRACKED_ACK_BYTES,
) {
    /** What [record] found: a repeat and its earlier `ack` if kept, or a direction over its cap. */
    class Seen(
        val replayed: Boolean,
        val earlierAck: ByteArray?,
        val overflow: Boolean,
    )

    private var current = LinkedHashMap<UUID, ByteArray?>()
    private var previous: LinkedHashMap<UUID, ByteArray?>? = null

    /** Stored acks, oldest first, each with the epoch that holds it (an id may be in both epochs). */
    private val ackOrder = ArrayDeque<Pair<MutableMap<UUID, ByteArray?>, UUID>>()
    private var ackBytes = 0L

    val size: Int get() = current.size + (previous?.size ?: 0)

    /** Bytes of the acks kept, and how many (tests: both stay bounded). */
    val storedAckBytes: Long get() = ackBytes
    val storedAcks: Int get() = ackOrder.size

    /** Records [id] in the epoch whose key opened it ([withPrevious]); a repeat stays recorded where it now is. */
    fun record(
        id: UUID,
        withPrevious: Boolean,
    ): Seen {
        val known = current.containsKey(id) || previous?.containsKey(id) == true
        val earlier = current[id] ?: previous?.get(id)
        val epoch = if (withPrevious) checkNotNull(previous) else current
        if (!epoch.containsKey(id)) epoch[id] = null
        return Seen(known, earlier, current.size >= maxIds)
    }

    /** The `ack` this side sent for request [re]; kept only for an `id` it accepted. */
    fun recordAck(
        re: UUID,
        ack: ByteArray,
    ) {
        val epoch = listOfNotNull(current, previous).firstOrNull { it.containsKey(re) && it[re] == null } ?: return
        epoch[re] = ack
        ackOrder.addLast(epoch to re)
        ackBytes += ack.size
        while (ackBytes > maxAckBytes && ackOrder.isNotEmpty()) {
            val (holder, id) = ackOrder.removeFirst()
            release(holder, id)
        }
    }

    /** Rekey: a new, empty set; the old one stays with the old key, the one before it goes. */
    fun rotate() {
        dropPrevious()
        previous = current
        current = LinkedHashMap()
    }

    /** The previous key is no longer accepted: its ids and acks go, and their places in [ackOrder]. */
    fun dropPrevious() {
        val dropped = previous ?: return
        previous = null
        ackOrder.removeAll { (holder, id) -> holder === dropped && release(dropped, id) }
    }

    /** Forgets the ack of [id] in [holder]; always `true`, so it can drive a `removeAll`. */
    private fun release(
        holder: MutableMap<UUID, ByteArray?>,
        id: UUID,
    ): Boolean {
        holder[id]?.let { ackBytes -= it.size }
        holder[id] = null
        return true
    }
}
