package app.handlive.android.feature.clipboard.engine

/** The phone's latest local change as QC8 sees it: when it happened (receiver clock) and its origin. */
class LocalChange(
    val clipId: String,
    val changedAtMillis: Long,
    val originTs: Long,
    val originDeviceId: String,
    /** Pairs that have acknowledged this change (the pair it came from counts as acknowledged). */
    val acknowledgedBy: Set<String>,
)

enum class ConflictDecision {
    APPLY,

    /** QC8 (a): keep the local content, `ack ignored/conflict` and send `clipboard/conflict`. */
    KEEP_LOCAL_AND_NOTIFY,

    /** QC8 (b): the incoming clip loses the tie-break; `ack ignored/conflict` without `clipboard/conflict`. */
    KEEP_LOCAL_SILENTLY,
}

/**
 * QC8 conflicts: an incoming `push` meets a local change that this same peer has not acknowledged. Within
 * `CLIP_CONFLICT_WINDOW` the local content wins and the sender is told; older changes are two clips crossing, and
 * the larger `origin_ts` wins, then the larger `origin_device_id` — both sides compare the same way and converge.
 */
object ConflictPolicy {
    const val WINDOW_MILLIS = 500L

    fun decide(
        incomingOriginTs: Long,
        incomingOriginDeviceId: String,
        fromPairId: String,
        local: LocalChange?,
        now: Long,
    ): ConflictDecision =
        when {
            local == null || fromPairId in local.acknowledgedBy -> ConflictDecision.APPLY
            now - local.changedAtMillis <= WINDOW_MILLIS -> ConflictDecision.KEEP_LOCAL_AND_NOTIFY
            incomingWins(incomingOriginTs, incomingOriginDeviceId, local) -> ConflictDecision.APPLY
            else -> ConflictDecision.KEEP_LOCAL_SILENTLY
        }

    /** QC8 (b): the larger `origin_ts` wins, then the larger `origin_device_id` in string order. */
    private fun incomingWins(
        originTs: Long,
        originDeviceId: String,
        local: LocalChange,
    ): Boolean = originTs > local.originTs || (originTs == local.originTs && originDeviceId > local.originDeviceId)
}
