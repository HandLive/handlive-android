package app.handlive.android.feature.sms.send

import app.handlive.android.feature.sms.SmsConstants

/**
 * `SMS_SEND_LIMIT` (SMS-04 logic 8): each pair may have at most 10 accepted `sms/send` in a rolling minute and 100 in
 * a rolling day, so a compromised or faulty client cannot send SMS at the user's expense without limit. Only new
 * messages count: a retried `id` or `local_id` never reaches [tryAcquire]. Not thread-safe (the SMS worker).
 */
class SendLimiter(
    /** A monotonic clock in ms: a wall clock the user can set back or forward would reset the windows. */
    private val clock: () -> Long,
) {
    private val accepted = HashMap<String, ArrayDeque<Long>>()
    private val noticedAt = HashMap<String, Long>()

    /** SMS-04 field 12: `true` for the first refusal of [pairId] in a rolling day, `false` for later ones. */
    fun noticeDue(pairId: String): Boolean {
        val now = clock()
        val last = noticedAt[pairId]
        if (last != null && now - last < SmsConstants.DAY_MILLIS) return false
        noticedAt[pairId] = now
        return true
    }

    /** `null` and the send is counted, or the wait in ms (≥ 1) until [pairId] may send again, nothing counted. */
    fun tryAcquire(pairId: String): Long? {
        val now = clock()
        val times = accepted.getOrPut(pairId) { ArrayDeque() }
        while (times.isNotEmpty() && times.first() <= now - SmsConstants.DAY_MILLIS) times.removeFirst()
        val lastMinute = times.count { it > now - SmsConstants.MINUTE_MILLIS }
        val waits =
            listOfNotNull(
                wait(times, lastMinute, SmsConstants.SEND_LIMIT_PER_MINUTE, SmsConstants.MINUTE_MILLIS, now),
                wait(times, times.size, SmsConstants.SEND_LIMIT_PER_DAY, SmsConstants.DAY_MILLIS, now),
            )
        if (waits.isNotEmpty()) return waits.max().coerceAtLeast(1)
        times.addLast(now)
        return null
    }

    /** Over [limit] in [window]: the one that leaves the window last frees the slot for the next send. */
    private fun wait(
        times: ArrayDeque<Long>,
        inWindow: Int,
        limit: Int,
        window: Long,
        now: Long,
    ): Long? = if (inWindow < limit) null else times[times.size - limit] + window - now
}
