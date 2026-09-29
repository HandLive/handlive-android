package app.handlive.spike.web.logic

/**
 * Debounce for `WEB_SETTLE` (W3): a page is reported once its address has stayed the same for [settleMs], and only
 * once until it changes. While the user types in the URL bar the candidate is cleared, so nothing half-typed ever
 * settles. Not thread-safe: the service calls it on the main thread only.
 */
class SettleGate<T : Any>(
    private val settleMs: Long = WEB_SETTLE_MS,
) {
    private var candidate: T? = null
    private var since = 0L
    private var reported: T? = null

    /** The address seen now (null: none, or the bar is focused). Returns the time at which [due] may fire. */
    fun observe(
        value: T?,
        nowMs: Long,
    ): Long? {
        if (value == null) {
            candidate = null
            return null
        }
        if (value != candidate) {
            candidate = value
            since = nowMs
        }
        return if (value == reported) null else since + settleMs
    }

    /** The candidate to report now, if it has been stable for [settleMs] and was not reported yet. */
    fun due(nowMs: Long): T? {
        val value = candidate ?: return null
        if (value == reported || nowMs - since < settleMs) return null
        reported = value
        return value
    }

    /** The reported page ended (browser left, screen off, private): the same page reported again counts as new. */
    fun reset() {
        candidate = null
        reported = null
    }

    companion object {
        const val WEB_SETTLE_MS = 1_500L
    }
}
