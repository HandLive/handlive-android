package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QC4 and CLIP-01 E9: echoes of the written clip (any path) and of the sent clip (automatic path) for 5 s. */
class LoopGuardTest {
    private var elapsed = 10_000L
    private val guard = LoopGuard { elapsed }
    private val written = byteArrayOf(1, 2, 3)
    private val sent = byteArrayOf(4, 5, 6)

    @Test
    fun theWrittenClipIsAnEchoOnEveryPathForFiveSeconds() {
        guard.onOwnWrite(written)
        assertTrue(guard.isEcho(written, automatic = true))
        assertTrue(guard.isEcho(written, automatic = false))
        elapsed += ClipLimits.LOOP_WINDOW_MILLIS + 1
        assertFalse(guard.isEcho(written, automatic = true))
    }

    @Test
    fun theSentClipIsAnEchoOnlyOnTheAutomaticPath() {
        guard.onSent(sent)
        assertTrue(guard.isEcho(sent, automatic = true))
        assertFalse(guard.isEcho(sent, automatic = false))
        assertFalse(guard.isEcho(written, automatic = true))
    }

    @Test
    fun copySignalsAreIgnoredForOneSecondAfterAWrite() {
        assertFalse(guard.ignoresSignals())
        guard.onOwnWrite(written)
        assertTrue(guard.ignoresSignals())
        elapsed += ClipLimits.SIGNAL_IGNORE_MILLIS
        assertFalse(guard.ignoresSignals())
    }
}
