package app.handlive.android.feature.call.context

import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallPresentation
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.FakeNumbers
import app.handlive.android.feature.call.testing.FakeSimLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CALL-01 API 1 logic 2, API 2 and API 3: the call context state machine of A-CALL, fed like the listeners feed it. */
class CallTrackerTest {
    private var next = 0
    private val numbers = FakeNumbers()
    private val sims = FakeSimLabels()
    private var callerId = true
    private val tracker = CallTracker({ "call-${++next}" }, numbers, sims) { callerId }

    init {
        tracker.reset()
    }

    @Test
    fun anIncomingCallRingsThenIsMissed() {
        val ringing = tracker.onPhoneState(PhoneState.RINGING, T0).single()
        assertEquals("call-1", ringing.callId)
        assertEquals(CallDirection.INCOMING, ringing.direction)
        assertEquals(PhoneState.RINGING, ringing.phoneState)
        assertEquals(T0, ringing.startedAt)
        assertNull(ringing.number)
        assertEquals(CallPresentation.UNKNOWN, ringing.presentation)

        val idle = tracker.onPhoneState(PhoneState.IDLE, T0 + 25_333).single()
        assertEquals("call-1", idle.callId)
        assertEquals(PhoneState.IDLE, idle.phoneState)
        assertEquals(T0 + 25_333, idle.endedAt)
        assertEquals(CallEndReason.MISSED, idle.endReason)
        assertNull(idle.answeredAt)
        assertNull(tracker.current)
        assertTrue("the same state twice changes nothing", tracker.onPhoneState(PhoneState.IDLE, T0 + 26_000).isEmpty())
    }

    @Test
    fun aNumberThatArrivesAfterTheFirstRingingKeepsTheCallId() {
        tracker.onPhoneState(PhoneState.RINGING, T0)
        tracker.onBroadcast(copy(PhoneState.RINGING, null, T0 + 3))
        assertNull("the copy without the key carries no number", checkNotNull(tracker.current).number)
        val known = tracker.onBroadcast(copy(PhoneState.RINGING, "0900000123", T0 + 40)).single()
        assertEquals("call-1", known.callId)
        assertEquals("+84900000123", known.number)
        assertEquals("Nguyễn Văn A", known.displayName)
        assertEquals(CallPresentation.ALLOWED, known.presentation)
        assertTrue(known.numberSettled)
        assertTrue(
            "a repeat changes nothing",
            tracker.onBroadcast(copy(PhoneState.RINGING, "0900000123", T0 + 41)).isEmpty(),
        )
    }

    @Test
    fun aCopyWithTheNumberBeforeTheListenerIsHeldForTwoSeconds() {
        tracker.onBroadcast(copy(PhoneState.RINGING, "0900000456", T0 - 1_500))
        val ringing = tracker.onPhoneState(PhoneState.RINGING, T0).single()
        assertEquals("+84900000456", ringing.number)
        assertEquals("Trần Thị B", ringing.displayName)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 1_000)

        tracker.onBroadcast(copy(PhoneState.RINGING, "0900000123", T0 + 10_000))
        val late = tracker.onPhoneState(PhoneState.RINGING, T0 + 12_001).single()
        assertNull("a copy older than 2 s is dropped", late.number)
    }

    @Test
    fun aWithheldNumberIsRestrictedOnlyWithTheCallLogPermission() {
        tracker.onPhoneState(PhoneState.RINGING, T0)
        val empty = tracker.onBroadcast(copy(PhoneState.RINGING, "", T0 + 2)).single()
        assertEquals(CallPresentation.RESTRICTED, empty.presentation)
        assertTrue(empty.numberSettled)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 5_000)

        // Two copies without the key: the number-carrying copy had an empty number (API 3 logic 3).
        tracker.onPhoneState(PhoneState.RINGING, T0 + 10_000)
        tracker.onBroadcast(copy(PhoneState.RINGING, null, T0 + 10_001))
        val withheld = tracker.onBroadcast(copy(PhoneState.RINGING, null, T0 + 10_002)).single()
        assertEquals(CallPresentation.RESTRICTED, withheld.presentation)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 15_000)

        // E2: without READ_CALL_LOG only the copy without the number arrives.
        callerId = false
        tracker.onPhoneState(PhoneState.RINGING, T0 + 20_000)
        tracker.onBroadcast(copy(PhoneState.RINGING, null, T0 + 20_001))
        tracker.onBroadcast(copy(PhoneState.RINGING, null, T0 + 20_002))
        val unknown = checkNotNull(tracker.current)
        assertEquals(CallPresentation.UNKNOWN, unknown.presentation)
        assertNull(unknown.number)
        assertFalse(unknown.numberSettled)
    }

    @Test
    fun anAnsweredCallGetsItsAnswerTimeAndEndsAsEnded() {
        tracker.onPhoneState(PhoneState.RINGING, T0)
        val offhook = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 5_198).single()
        assertEquals(PhoneState.OFFHOOK, offhook.phoneState)
        assertEquals(T0 + 5_198, offhook.answeredAt)
        val idle = tracker.onPhoneState(PhoneState.IDLE, T0 + 130_000).single()
        assertEquals(CallEndReason.ENDED, idle.endReason)
        assertEquals(T0 + 5_198, idle.answeredAt)
    }

    @Test
    fun anOutgoingCallHasNoNumberAndNoAnswerTime() {
        assertTrue("registration on an idle phone", tracker.onPhoneState(PhoneState.IDLE, T0).isEmpty())
        val dialed = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 5_000).single()
        assertEquals(CallDirection.OUTGOING, dialed.direction)
        assertTrue(tracker.onBroadcast(copy(PhoneState.OFFHOOK, "0900000456", T0 + 5_001)).isEmpty())
        assertNull(checkNotNull(tracker.current).number)
        val ended = tracker.onPhoneState(PhoneState.IDLE, T0 + 65_000).single()
        assertNull(ended.answeredAt)
        assertEquals(CallEndReason.ENDED, ended.endReason)
    }

    @Test
    fun theContextIsRebuiltFromTheStateReportedAtRegistration() {
        assertTrue(tracker.onPhoneState(PhoneState.IDLE, T0).isEmpty())
        tracker.reset()
        val midway = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 1).single()
        assertEquals(CallDirection.UNKNOWN, midway.direction)
        assertNull(midway.answeredAt)
        tracker.reset()
        val ringing = tracker.onPhoneState(PhoneState.RINGING, T0 + 2).single()
        assertEquals(CallDirection.INCOMING, ringing.direction)
        assertNotEquals(midway.callId, ringing.callId)
    }

    @Test
    fun aCallWaitingKeepsTheFirstCallAndClearsWhenItStopsRinging() {
        tracker.ring("0900000123", T0)
        val answered = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 4_000).single()
        tracker.onBroadcast(copy(PhoneState.RINGING, "0900000456", T0 + 30_000))
        val waiting = tracker.onPhoneState(PhoneState.RINGING, T0 + 30_010).single()
        assertEquals(answered.callId, waiting.callId)
        assertTrue(waiting.waiting)
        assertEquals(PhoneState.RINGING, waiting.phoneState)
        assertEquals("+84900000123", waiting.number)
        assertEquals("+84900000456", waiting.waitingNumber)
        assertEquals("Trần Thị B", waiting.waitingDisplayName)
        assertFalse("a waiting call is no ringing incoming call", waiting.ringingIncoming)

        val back = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 40_000).single()
        assertFalse(back.waiting)
        assertNull(back.waitingNumber)
        assertNull(back.waitingDisplayName)
        assertEquals("+84900000123", back.number)
        assertEquals(T0 + 4_000, back.answeredAt)
        assertEquals(CallEndReason.ENDED, tracker.onPhoneState(PhoneState.IDLE, T0 + 50_000).single().endReason)
    }

    @Test
    fun theSimIsTheOnlyOneReportingTheNewStateWithin500Ms() {
        tracker.onSimState(SimReport(1, PhoneState.IDLE, T0 - 5_000))
        tracker.onPhoneState(PhoneState.RINGING, T0)
        val labeled = tracker.onSimState(SimReport(2, PhoneState.RINGING, T0 + 12)).single()
        assertEquals(2, labeled.subId)
        assertEquals("SIM 2", labeled.simLabel)
        val both = tracker.onSimState(SimReport(1, PhoneState.RINGING, T0 + 20)).single()
        assertNull("several SIMs reporting: undetermined", both.subId)
        assertNull(both.simLabel)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 9_000)

        tracker.onPhoneState(PhoneState.RINGING, T0 + 20_000)
        assertTrue(tracker.onSimState(SimReport(1, PhoneState.RINGING, T0 + 20_600)).isEmpty())
        assertNull("a report after 500 ms does not count", checkNotNull(tracker.current).subId)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 30_000)

        sims.single = true
        tracker.onSimState(SimReport(1, PhoneState.OFFHOOK, T0 + 39_900))
        val oneSim = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 40_000).single()
        assertEquals(1, oneSim.subId)
        assertNull("one SIM: no label", oneSim.simLabel)
    }

    @Test
    fun aDeclineWithinThreeSecondsEndsAsRejected() {
        tracker.ring("0900000123", T0)
        tracker.declined(T0 + 1_000)
        assertEquals(CallEndReason.REJECTED, tracker.onPhoneState(PhoneState.IDLE, T0 + 1_200).single().endReason)

        tracker.ring("0900000123", T0 + 10_000)
        tracker.declined(T0 + 10_500)
        assertEquals(CallEndReason.MISSED, tracker.onPhoneState(PhoneState.IDLE, T0 + 13_600).single().endReason)
    }

    @Test
    fun aCallLogEntryMatchesOneRecentlyEndedContextAndCorrectsItOnce() {
        val first = tracker.ring("0900000123", T0)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 20_000)
        val outgoing = tracker.onPhoneState(PhoneState.OFFHOOK, T0 + 30_000).single()
        tracker.onPhoneState(PhoneState.IDLE, T0 + 40_000)

        val recent = tracker.recent
        assertNull("wrong number", recent.match(T0 + 100, CallDirection.INCOMING, "+84900000456", T0 + 41_000))
        assertNull(
            "too far from started_at",
            tracker.recent.match(T0 + 5_100, CallDirection.INCOMING, null, T0 + 41_000),
        )
        assertEquals(
            first.callId,
            tracker.recent.match(T0 + 100, CallDirection.INCOMING, "+84900000123", T0 + 41_000)?.callId,
        )
        assertNull(
            "matched already",
            tracker.recent.match(T0 + 100, CallDirection.INCOMING, "+84900000123", T0 + 41_000),
        )
        assertEquals(
            outgoing.callId,
            tracker.recent.match(T0 + 30_050, CallDirection.OUTGOING, "+84900000456", T0 + 41_000)?.callId,
        )

        val corrected = tracker.recent.correct(first.callId, CallEndReason.REJECTED)
        assertEquals(CallEndReason.REJECTED, corrected?.endReason)
        assertNull("exactly one correction", tracker.recent.correct(first.callId, CallEndReason.ANSWERED_ELSEWHERE))
        assertNull("an ended call is no missed call", tracker.recent.correct(outgoing.callId, CallEndReason.REJECTED))
    }

    @Test
    fun endedContextsAreForgottenAfterSixtySeconds() {
        tracker.ring("0900000123", T0)
        tracker.onPhoneState(PhoneState.IDLE, T0 + 1_000)
        assertNull(tracker.recent.match(T0 + 10, CallDirection.INCOMING, "+84900000123", T0 + 61_001))
    }

    @Test
    fun resetForgetsTheCall() {
        tracker.ring("0900000123", T0)
        tracker.reset()
        assertNull(tracker.current)
        assertTrue("the next IDLE after a reset ends nothing", tracker.onPhoneState(PhoneState.IDLE, T0 + 1).isEmpty())
    }

    private fun CallTracker.ring(
        number: String,
        at: Long,
    ): CallContext {
        onBroadcast(copy(PhoneState.RINGING, number, at))
        return onPhoneState(PhoneState.RINGING, at).single()
    }

    private fun copy(
        state: PhoneState,
        number: String?,
        at: Long,
    ) = BroadcastCopy(state, number != null, number, at)

    private companion object {
        const val T0 = BASE_TS
    }
}
