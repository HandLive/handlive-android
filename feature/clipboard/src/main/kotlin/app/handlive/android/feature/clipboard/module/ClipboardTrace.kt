package app.handlive.android.feature.clipboard.module

import app.handlive.android.feature.clipboard.system.ClipLabel
import app.handlive.android.feature.clipboard.system.OwnWrite
import app.handlive.android.feature.clipboard.system.SystemClipboard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** CLIP-05 step 7 verdict. */
enum class ClearVerdict { UNCHANGED, CHANGED, UNKNOWN }

/**
 * CLIP-05 on Android (C17): the trace of the clip HandLive wrote, the auto-clear timer and its safe check. "Unchanged"
 * is concluded only from the clipboard description read with focus — label "HandLive" and a system timestamp within
 * [`wallFrom`, `wallTo`] — directly when A-UI has focus, or through `ClipboardReadActivity` in verification mode
 * while the Accessibility service runs. Any sign of change drops the timer; without a way to verify, the check waits
 * for A-UI's focus (E3). The user's own content is therefore never cleared.
 */
class ClipboardTrace(
    private val context: ClipContext,
) {
    private val platform = context.platform

    @Volatile
    private var own: OwnWrite? = null
    private var pending = false
    private var timer: Job? = null
    private var clipboardFile: File? = null
    private var verification: CompletableDeferred<ClipLabel?>? = null

    /** HandLive wrote a received clip: the new trace replaces the old one (E2) and the old clip file goes. */
    fun onWrite(write: OwnWrite) {
        clipboardFile?.takeIf { it != write.file }?.delete()
        clipboardFile = write.file
        own = write
        pending = false
        schedule(write, context.settings.value.clipAutoClearSeconds)
    }

    /**
     * A sign of change (step 5): the in-app listener, an Accessibility copy signal, a local clip sent — or HandLive
     * read a clip, when its [sha256] differs from the written one.
     */
    fun markChanged(sha256: ByteArray? = null) {
        val write = own ?: return
        if (sha256 == null || !write.sha256.contentEquals(sha256)) write.changed = true
    }

    /** The in-app listener saw HandLive's own write (label and timestamp), not a user's copy. */
    fun isOwnClip(label: ClipLabel?): Boolean = own?.let { verdictOf(it, label) == ClearVerdict.UNCHANGED } == true

    /** E5: the deadline is recomputed from the write time; `0` cancels the timer. */
    fun onAutoClearChanged(seconds: Int) {
        val write = own ?: return
        pending = false
        schedule(write, seconds)
    }

    /** A-UI got focus: a postponed check (E3) runs now. */
    suspend fun onAppFocused() {
        val write = own?.takeIf { pending } ?: return
        check(write)
    }

    /** `ClipboardReadActivity` in verification mode read the description (`null`: no focus within 1 s). */
    fun deliverVerification(label: ClipLabel?) {
        verification?.complete(label)
    }

    private fun schedule(
        write: OwnWrite,
        seconds: Int,
    ) {
        timer?.cancel()
        if (seconds <= 0) return
        val deadline = write.elapsedAt + seconds * MILLIS_PER_SECOND
        timer =
            context.scope.launch {
                // Deep sleep can delay the timer; the deadline is on the monotonic clock that counts sleep (logic 5).
                while (context.clock.elapsed() < deadline) delay(deadline - context.clock.elapsed())
                check(write)
            }
    }

    private suspend fun check(write: OwnWrite) {
        if (own !== write) return
        when (verdict(write)) {
            ClearVerdict.UNCHANGED -> {
                runCatching { platform.writer.clear() }
                write.file?.delete()
                if (clipboardFile == write.file) clipboardFile = null
                own = null
                context.state.forget(write.clipId)
            }

            ClearVerdict.CHANGED -> {
                own = null
            }

            ClearVerdict.UNKNOWN -> {
                pending = true
            }
        }
    }

    private suspend fun verdict(write: OwnWrite): ClearVerdict =
        when {
            write.changed -> ClearVerdict.CHANGED
            platform.focus.appFocused -> verdictOf(write, platform.writer.describe())
            platform.focus.accessibilityRunning -> verdictOf(write, verifyThroughActivity())
            else -> ClearVerdict.UNKNOWN
        }

    private suspend fun verifyThroughActivity(): ClipLabel? {
        val waiter = CompletableDeferred<ClipLabel?>().also { verification = it }
        val label =
            if (platform.focus.startVerification()) {
                withTimeoutOrNull(VERIFY_TIMEOUT_MILLIS) {
                    waiter.await()
                }
            } else {
                null
            }
        verification = null
        return label
    }

    companion object {
        private const val MILLIS_PER_SECOND = 1_000L

        /** 1 s for focus (E3 of CLIP-01) plus the activity start. */
        private const val VERIFY_TIMEOUT_MILLIS = 3_000L

        /** Logic 2: HandLive's clip exactly — its label and a system timestamp within the write interval. */
        fun verdictOf(
            write: OwnWrite,
            label: ClipLabel?,
        ): ClearVerdict =
            when {
                write.changed -> ClearVerdict.CHANGED
                label == null -> ClearVerdict.UNKNOWN
                isHandLiveClip(write, label) -> ClearVerdict.UNCHANGED
                else -> ClearVerdict.CHANGED
            }

        private fun isHandLiveClip(
            write: OwnWrite,
            label: ClipLabel,
        ): Boolean = label.label?.toString() == SystemClipboard.LABEL && label.timestamp in write.wallFrom..write.wallTo
    }
}
