package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALL-05 E11: an in-call notification that goes without the app removing it (the user swiped it away) leaves its call
 * on, detached and without an end action, while a call holds the audio mode; otherwise, and when the mode leaves
 * communication, the call ends `unknown`. Only an in-call notification of the package holds a detached call again.
 */
class AppCallTrackerDetachTest {
    private var counter = 0
    private val mode = FakeCommunicationMode()
    private val tracker = AppCallTracker({ "call-${++counter}" }, AppCallFixtures.labels, mode)

    /** A Telegram call answered on the phone: ringing, then the in-call notification [IN_CALL_KEY] holds it. */
    private fun ongoing(): AppCallContext {
        tracker.onPosted(AppCallFixtures.telegramRinging(), T0)
        tracker.onRemoved(RINGING_KEY, T0 + 100, byApp = true)
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
        assertEquals(
            AppCallEndReason.ENDED,
            tracker.onRemoved(IN_CALL_KEY, T0 + 70_000, byApp = true).single().endReason,
        )
    }

    @Test
    fun aNewCallStyleOngoingNotificationOfThePackageHoldsTheDetachedCallWithItsHangUp() {
        ongoing()
        swipe(inCommunication = true)
        val hangUp = FakeAppIntent("hang up")
        val inCall =
            AppCallFixtures.notification(
                NEW_KEY,
                AppCallFixtures.TELEGRAM,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = hangUp,
            )

        val again = tracker.onPosted(inCall, T0 + 61_000).single()

        assertEquals(NEW_KEY, again.notificationKey)
        assertFalse(again.detached)
        assertSame(hangUp, again.end)
        assertFalse(tracker.hasDetached)
    }

    @Test
    fun aNewOngoingNotificationOfTheCallCategoryHoldsTheDetachedCallWithItsSingleAction() {
        ongoing()
        swipe(inCommunication = true)
        val end = FakeAppIntent("end")
        val inCall =
            AppCallFixtures.notification(
                NEW_KEY,
                AppCallFixtures.TELEGRAM,
                ongoing = true,
                actions = listOf(end),
                category = "call",
            )

        val again = tracker.onPosted(inCall, T0 + 61_000).single()

        assertFalse(again.detached)
        assertSame(end, again.end)
    }

    @Test
    fun anOrdinaryOngoingNotificationOfThePackageNeverHoldsTheDetachedCallNorGivesItEnd() {
        ongoing()
        swipe(inCommunication = true)
        val cancel = FakeAppIntent("cancel upload")
        val upload =
            AppCallFixtures.notification(UPLOAD_KEY, AppCallFixtures.TELEGRAM, ongoing = true, actions = listOf(cancel))

        assertTrue("an upload with Cancel", tracker.onPosted(upload, T0 + 61_000).isEmpty())
        val detached = tracker.current.single()
        assertTrue(detached.detached)
        assertNull(detached.end)
        assertTrue(
            "its removal does not end the call",
            tracker.onRemoved(UPLOAD_KEY, T0 + 62_000, byApp = true).isEmpty(),
        )
        assertTrue(tracker.current.single().detached)
        assertEquals(0, cancel.sends)
    }

    @Test
    fun theAudioModeLeavingEndsOnlyTheDetachedCallNotAnotherAppsCallInProgress() {
        ongoing()
        val whatsApp =
            AppCallFixtures.notification(
                WHATSAPP_KEY,
                WHATSAPP,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = FakeAppIntent("hang up"),
            )
        val other = tracker.onPosted(whatsApp, T0 + 1_000).single()
        swipe(inCommunication = true)

        val ended = tracker.onLost(AppCallSignal.AUDIO_MODE, T0 + 90_000)

        assertEquals(listOf("call-1"), ended.map { it.callId })
        assertEquals(AppCallEndReason.UNKNOWN, ended.single().endReason)
        val left = tracker.current.single()
        assertEquals(other.callId, left.callId)
        assertEquals(AppCallState.ONGOING, left.state)
        assertNotNull("its End stays", left.end)
    }

    @Test
    fun theAudioModeLeavingEndsEveryDetachedCall() {
        ongoing()
        val zalo =
            AppCallFixtures.notification(
                ZALO_KEY,
                AppCallFixtures.ZALO,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
            )
        tracker.onPosted(zalo, T0 + 1_000)
        swipe(inCommunication = true)
        tracker.onRemoved(ZALO_KEY, T0 + 61_000, byApp = false)

        val ended = tracker.onLost(AppCallSignal.AUDIO_MODE, T0 + 90_000)

        assertEquals(2, ended.size)
        assertTrue(ended.all { it.endReason == AppCallEndReason.UNKNOWN })
        assertTrue(tracker.current.isEmpty())
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
        const val UPLOAD_KEY = "0|org.telegram.messenger|77|null|10148"
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_KEY = "0|com.whatsapp|1|null|10200"
        const val ZALO_KEY = "0|com.zing.zalo|5|null|10300"
    }
}
