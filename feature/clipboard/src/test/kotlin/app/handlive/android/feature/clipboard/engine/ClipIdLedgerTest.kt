package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** QC6 de-duplication: the latest 256 `clip_id` values for 10 minutes; rejected clips may come again. */
class ClipIdLedgerTest {
    private var now = 1_727_150_000_000L
    private val ledger = ClipIdLedger({ now })

    @Test
    fun appliedOrIgnoredClipsAreDuplicatesRejectedOnesAreNot() {
        ledger.recordFinal("a")
        ledger.recordRejected("b")
        assertTrue(ledger.isDuplicate("a"))
        assertFalse(ledger.isDuplicate("b"))
        ledger.recordFinal("b")
        assertTrue(ledger.isDuplicate("b"))
    }

    @Test
    fun entriesExpireAfterTenMinutes() {
        ledger.recordFinal("a")
        now += ClipIdLedger.WINDOW_MILLIS
        assertTrue(ledger.isDuplicate("a"))
        now += 1
        assertFalse(ledger.isDuplicate("a"))
    }

    @Test
    fun onlyTheLatest256AreKept() {
        repeat(ClipIdLedger.CAPACITY + 1) { ledger.recordFinal("clip-$it") }
        assertFalse(ledger.isDuplicate("clip-0"))
        assertTrue(ledger.isDuplicate("clip-1"))
        assertTrue(ledger.isDuplicate("clip-${ClipIdLedger.CAPACITY}"))
    }
}
