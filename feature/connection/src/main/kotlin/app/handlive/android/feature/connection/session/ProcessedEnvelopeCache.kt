package app.handlive.android.feature.connection.session

import app.handlive.android.core.protocol.ack.Ack

/**
 * `id`s processed in the last 5 minutes, at most 1 000 (0.5.1 rule 2, `DEDUP_WINDOW`): a repeated request gets its
 * old `ack` again and is not processed twice. Not thread-safe; one per session, used from its receive loop.
 */
class ProcessedEnvelopeCache(
    private val clock: () -> Long,
    private val windowMillis: Long = WINDOW_MILLIS,
    private val capacity: Int = CAPACITY,
) {
    /** A processed id; [ack] is `null` for events and for requests whose `ack` is not sent yet. */
    class Entry(
        val seenAt: Long,
        var ack: Ack?,
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun find(id: String): Entry? {
        evict()
        return entries[id]
    }

    fun remember(id: String) {
        evict()
        entries[id] = Entry(clock(), null)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun recordAck(ack: Ack) {
        entries[ack.re]?.ack = ack
    }

    private fun evict() {
        val oldest = clock() - windowMillis
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && iterator.next().seenAt < oldest) iterator.remove()
    }

    companion object {
        const val WINDOW_MILLIS = 5 * 60 * 1000L
        const val CAPACITY = 1_000
    }
}
