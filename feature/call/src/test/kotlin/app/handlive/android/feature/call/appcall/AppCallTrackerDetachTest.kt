package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALL-05 E11: an in-call notification that goes without the app removing it (the user swiped it away) leaves its call
 * on, detached and without an end action, while a call holds the audio mode; otherwise, and when the mode leaves
 * communication, the call ends `unknown`. A new ongoing notification of the package holds a detached call again.
 */
class AppCallTrackerDetachTest {
    private var counter = 0
    private val mode = FakeCommunicationMode()
    private val tracker = AppCallTracker({ "call-${++counter}" }, AppCallFixtures.labels, mode)

    /** A Telegram call answered on the phone: ringing, then the in-call notification [IN_CALL_KEY] holds it. */
    private fun ongoing(): AppCallContext {
        tracker.onPosted(AppCallFixtures.telegramRinging(), T0)
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        return tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 500).single()
    }

    private fun swipe(inCommunication: Boolean): List<AppCallContext> {
        mode.inCommunication = inCommunication
        return tracker.onRemoved(IN_CALL_KEY, T0 + 60_000, byApp = false)
    }

    @Test
    fun theAppRemovingTheInCallNotificationEndsTheCallAsEndedWithoutAskingTheAudioMode() {
        ongoing()

        val ended = tracker.onRemoved(IN_CALL_KEY, T0 + 60_000, byApp = true).single()

        assertEquals(AppCallEndReason.ENDED, ended.endReason)
        assertEquals(T0 + 500, ended.answeredAt)
        assertFalse(tracker.hasDetached)
    }

    @Test
    fun aSwipedInCallNotificationLeavesTheCallOngoingWithoutEndWhileACallHoldsTheAudioMode() {
        assertNotNull(ongoing().end)

        val detached = swipe(inCommunication = true).single()

        assertEquals(AppCallState.ONGOING, detached.state)
        assertTrue(detached.detached)
        assertNull("its end action went with the notification", detached.end)
        assertEquals(T0 + 500, detached.answeredAt)
        assertEquals(listOf(detached), tracker.current)
        assertTrue(tracker.hasDetached)
    }

    @Test
    fun aDetachedCallEndsAsUnknownWhenTheAudioModeLeavesCommunication() {
        ongoing()
        swipe(inCommunication = true)

        val ended = tracker.onLost(AppCallSignal.AUDIO_MODE, T0 + 90_000).single()

        assertEquals(AppCallState.ENDED, ended.state)
        assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
        assertEquals(T0 + 90_000, ended.endedAt)
        assertNull("unknown keeps no answer time (shared schema)", ended.answeredAt)
        assertTrue(tracker.current.isEmpty())
        assertFalse(tracker.hasDetached)
        assertTrue(tracker.onLost(AppCallSignal.AUDIO_MODE, T0 + 91_000).isEmpty())
    }

    @Test
    fun aSwipedInCallNotificationWithoutACallInTheAudioModeEndsTheCallAsUnknownAtOnce() {
        ongoing()

        val ended = swipe(inCommunication = false).single()

        assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
        assertEquals(T0 + 60_000, ended.endedAt)
        assertTrue(tracker.current.isEmpty())
        assertFalse(tracker.hasDetached)
    }

    @Test
    fun theAppPostingItsInCallNotificationAgainHoldsTheDetachedCallAgain() {
        ongoing()
        swipe(inCommunication = true)

        val again = tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 61_000).single()

        assertEquals(AppCallState.ONGOING, again.state)
        assertFalse(again.detached)
        assertNotNull(again.end)
        assertEquals("call-1", again.callId)
        assertFalse(tracker.hasDetached)
        assertEquals(AppCallEndReason.ENDED, tracker.onRemoved(IN_CALL_KEY, T0 + 70_000).single().endReason)
    }

    @Test
    fun aNewOngoingNotificationOfThePackageHoldsTheDetachedCall() {
        ongoing()
        swipe(inCommunication = true)

        val again = tracker.onPosted(AppCallFixtures.telegramInCall(key = NEW_KEY), T0 + 61_000)

        assertEquals(NEW_KEY, again.single().notificationKey)
        assertFalse(again.single().detached)
    }

    @Test
    fun anOngoingNotificationThatStoodWhenTheCallWasDetachedNeverHoldsIt() {
        val player = AppCallFixtures.notification(PLAYER_KEY, AppCallFixtures.TELEGRAM, ongoing = true)
        tracker.onPosted(player, T0 - 10_000)
        ongoing()
        swipe(inCommunication = true)

        assertTrue("an update of the player", tracker.onPosted(player, T0 + 61_000).isEmpty())
        assertTrue(tracker.current.single().detached)
    }

    @Test
    fun anOngoingNotificationOfAnotherPackageNeverHoldsTheDetachedCall() {
        ongoing()
        swipe(inCommunication = true)

        val zalo = AppCallFixtures.notification("z9", AppCallFixtures.ZALO, ongoing = true)

        assertTrue(tracker.onPosted(zalo, T0 + 61_000).isEmpty())
        assertTrue(tracker.current.single().detached)
    }

    @Test
    fun aRingingNotificationTheUserRemovesStillWaitsForTheInCallOne() {
        tracker.onPosted(AppCallFixtures.telegramRinging(), T0)

        val waiting = tracker.onRemoved(RINGING_KEY, T0 + 100, byApp = false).single()

        assertTrue(waiting.waitingForInCall)
        assertFalse(waiting.detached)
    }

    @Test
    fun losingTheListenerEndsADetachedCallToo() {
        ongoing()
        swipe(inCommunication = true)

        assertEquals(AppCallEndReason.UNKNOWN, tracker.onLost(AppCallSignal.LISTENER, T0 + 70_000).single().endReason)
        assertFalse(tracker.hasDetached)
    }

    private companion object {
        const val T0 = 1_727_150_400_000L
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
        const val NEW_KEY = "0|org.telegram.messenger|204|null|10148"
        const val PLAYER_KEY = "0|org.telegram.messenger|9|null|10148"
    }
}
