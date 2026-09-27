package app.handlive.android.feature.sms.observe

import app.handlive.android.core.protocol.sms.SmsReadChangedData
import app.handlive.android.core.protocol.sms.SmsUnreadEntry
import org.junit.Assert.assertEquals
import org.junit.Test

/** SMS-05 steps 3–4: changes against the previous unread snapshot. */
class ReadStateTrackerTest {
    private var now = 1_727_150_130_000L
    private val tracker = ReadStateTracker { now }

    @Test
    fun theFirstSnapshotEmitsNothingThenChangesAreReported() {
        assertEquals(emptyList<SmsReadChangedData>(), tracker.changes(listOf(entry(42, 2, 100)), emptyMap()))
        val changes =
            tracker.changes(
                listOf(entry(42, 1, 150), entry(57, 1, 500)),
                emptyMap(),
            )
        assertEquals(listOf(SmsReadChangedData(42, 1, 150), SmsReadChangedData(57, 1, 500)), changes)
    }

    @Test
    fun aConversationReadOnThePhoneReportsZeroAtTheTimeOfTheEvent() {
        tracker.reset(listOf(entry(42, 2, 100), entry(57, 1, 500)))
        assertEquals(listOf(SmsReadChangedData(42, 0, now)), tracker.changes(listOf(entry(57, 1, 500)), emptyMap()))
        assertEquals(emptyList<SmsReadChangedData>(), tracker.changes(listOf(entry(57, 1, 500)), emptyMap()))
    }

    @Test
    fun aConversationThatJustHadAnSmsNewWithTheSameCountIsSkipped() {
        tracker.reset(listOf(entry(42, 1, 100)))
        val announced = mapOf(42L to 2, 57L to 3)
        val changes = tracker.changes(listOf(entry(42, 2, 100), entry(57, 1, 700)), announced)
        assertEquals(listOf(SmsReadChangedData(57, 1, 700)), changes)
    }

    @Test
    fun onlyTheOldestUnreadMovingIsAChangeToo() {
        tracker.reset(listOf(entry(42, 2, 100)))
        assertEquals(listOf(SmsReadChangedData(42, 2, 300)), tracker.changes(listOf(entry(42, 2, 300)), emptyMap()))
    }

    private fun entry(
        threadId: Long,
        count: Int,
        readUpTo: Long,
    ) = SmsUnreadEntry(threadId, count, readUpTo)
}
