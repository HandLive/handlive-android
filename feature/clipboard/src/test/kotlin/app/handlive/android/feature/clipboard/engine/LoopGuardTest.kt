package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** QC4 and CLIP-01 E9: echoes of the written clip (any path) and of the sent clip (automatic path) for 5 s. */
class LoopGuardTest {
    private var elapsed = 10_000L
    private val guard = LoopGuard { elapsed }
    private val written = byteArrayOf(1, 2, 3)
    private val sent = byteArrayOf(4, 5, 6)

    @Test
    fun theWrittenClipIsAnEchoOnEveryPathForFiveSecondsAndKnowsItsDevice() {
        guard.onOwnWrite(written, "MacBook của Lan")
        assertTrue(guard.isEcho(written, automatic = true))
        assertTrue(guard.isEcho(written, automatic = false))
        assertEquals("MacBook của Lan", guard.justReceivedFrom(written))
        assertNull(guard.justReceivedFrom(sent))
        elapsed += ClipLimits.LOOP_WINDOW_MILLIS + 1
        assertFalse(guard.isEcho(written, automatic = true))
        assertNull(guard.justReceivedFrom(written))
    }

    @Test
    fun theSentClipIsAnEchoOnlyOnTheAutomaticPath() {
        guard.onSent(sent)
        assertTrue(guard.justSent(sent))
        assertTrue(guard.isEcho(sent, automatic = true))
        assertFalse(guard.isEcho(sent, automatic = false))
        assertFalse(guard.isEcho(written, automatic = true))
    }

    @Test
    fun copySignalsAreIgnoredForOneSecondAfterAWrite() {
        assertFalse(guard.ignoresSignals())
        guard.onOwnWrite(written, "MacBook của Lan")
        assertTrue(guard.ignoresSignals())
        elapsed += ClipLimits.SIGNAL_IGNORE_MILLIS
        assertFalse(guard.ignoresSignals())
    }
}
