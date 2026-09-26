package app.handlive.android.feature.clipboard.engine

/**
 * QC6 de-duplication: the outcome of the latest 256 `clip_id`s for 10 minutes. A clip that was applied or ignored
 * is a duplicate the next time; a rejected clip may come again (resend after `CLIP_CHECKSUM_MISMATCH`).
 */
class ClipIdLedger(
    private val clock: () -> Long,
    private val capacity: Int = CAPACITY,
    private val windowMillis: Long = WINDOW_MILLIS,
) {
    private class Entry(
        val at: Long,
        val final: Boolean,
    )

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun isDuplicate(clipId: String): Boolean {
        evict()
        return entries[clipId]?.final == true
    }

    /** Applied or ignored: the clip is done for this device. */
    @Synchronized
    fun recordFinal(clipId: String) = record(clipId, final = true)

    /** Rejected: a later resend of the same clip is processed again. */
    @Synchronized
    fun recordRejected(clipId: String) = record(clipId, final = false)

    private fun record(
        clipId: String,
        final: Boolean,
    ) {
        evict()
        entries.remove(clipId)
        entries[clipId] = Entry(clock(), final)
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    private fun evict() {
        val oldest = clock() - windowMillis
        val iterator = entries.values.iterator()
        while (iterator.hasNext() && iterator.next().at < oldest) iterator.remove()
    }

    companion object {
        const val CAPACITY = 256
        const val WINDOW_MILLIS = 10 * 60 * 1000L
    }
}
