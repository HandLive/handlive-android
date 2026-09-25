package app.handlive.android.feature.clipboard.module

import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipIdLedger
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.engine.LoopGuard

/**
 * The clipboard state of this process, all in memory (QC2): the latest clip (QC7, and QC8's "local change"), the
 * clips of the last `CLIP_STALE_AFTER` for "Send Again" and conflict routing, the `clip_id` ledger (QC6) and the loop
 * guard (QC4). Mutated only on the clipboard module's serial dispatcher.
 */
class ClipState(
    private val clock: ClipClock,
) {
    private class Kept(
        val clip: Clip,
        var until: Long,
    )

    val ledger = ClipIdLedger(clock.wall)
    val loopGuard = LoopGuard(clock.elapsed)
    private val recent = LinkedHashMap<String, Kept>()

    /** The latest clip, whatever its source (QC7). */
    var latest: Clip? = null
        private set

    fun remember(clip: Clip) {
        latest = clip
        recent[clip.clipId] = Kept(clip, clip.changedAtMillis + ClipLimits.STALE_AFTER_MILLIS)
        purge()
    }

    /** A clip still held in memory, by `clip_id`. */
    fun find(clipId: String): Clip? {
        purge()
        return recent[clipId]?.clip
    }

    /** A conflict notification offers "Send Again" for 120 s from now (CLIP-01 API 6 logic 3). */
    fun keepForSendAgain(clipId: String) {
        recent[clipId]?.until = clock.wall() + ClipLimits.STALE_AFTER_MILLIS
    }

    /** CLIP-05 cleared it: it is neither replayed nor sent again. */
    fun forget(clipId: String) {
        recent.remove(clipId)
        if (latest?.clipId == clipId) latest = null
    }

    /** QC7: created within `CLIP_STALE_AFTER` by this phone's clock. */
    fun isFresh(clip: Clip): Boolean = clock.wall() - clip.changedAtMillis <= ClipLimits.STALE_AFTER_MILLIS

    private fun purge() {
        val now = clock.wall()
        recent.values.removeAll { it.until < now && it.clip !== latest }
    }
}
