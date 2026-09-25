package app.handlive.android.feature.clipboard.engine

/**
 * QC4 loop prevention on the phone: the SHA-256 and time of the clip HandLive just wrote from a peer and of the clip
 * it just sent, on the monotonic clock. A local read equal to the written clip within `CLIP_LOOP_WINDOW` is an echo;
 * on the automatic path so is one equal to the clip sent within that window (CLIP-01 E9). Accessibility copy signals
 * are ignored for 1 s after a write: Android 13+ shows its clipboard overlay for HandLive's own write too.
 */
class LoopGuard(
    private val elapsed: () -> Long,
) {
    private class Mark(
        val sha256: ByteArray,
        val at: Long,
    )

    @Volatile
    private var written: Mark? = null

    @Volatile
    private var sent: Mark? = null

    fun onOwnWrite(sha256: ByteArray) {
        written = Mark(sha256, elapsed())
    }

    fun onSent(sha256: ByteArray) {
        sent = Mark(sha256, elapsed())
    }

    /** [automatic]: the read came from the Accessibility path or the in-app listener, not from a manual action. */
    fun isEcho(
        sha256: ByteArray,
        automatic: Boolean,
    ): Boolean = matches(written, sha256) || (automatic && matches(sent, sha256))

    fun ignoresSignals(): Boolean = written?.let { elapsed() - it.at < ClipLimits.SIGNAL_IGNORE_MILLIS } == true

    private fun matches(
        mark: Mark?,
        sha256: ByteArray,
    ): Boolean =
        mark != null && elapsed() - mark.at <= ClipLimits.LOOP_WINDOW_MILLIS && mark.sha256.contentEquals(sha256)
}
