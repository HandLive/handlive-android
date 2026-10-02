package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.appcall.AppCallFixtures.CALLER
import app.handlive.android.feature.call.appcall.AppCallFixtures.TELEGRAM
import app.handlive.android.feature.call.appcall.AppCallFixtures.ZALO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALL-05 contexts: created from a call notification, linked from ringing to ongoing when the same package posts an
 * ongoing notification — never one that stood before the call — before the ringing one is removed or within
 * `APP_CALL_LINK_WINDOW` (by post time) after, ended on removal with a reason, forgotten once ended.
 */
class AppCallTrackerTest {
    private var counter = 0
    private val tracker = AppCallTracker({ "call-${++counter}" }, AppCallFixtures.labels)
    private val window = CallConstants.APP_CALL_LINK_WINDOW_MILLIS

    private fun ring(
        at: Long = T0,
        notification: AppNotification = AppCallFixtures.telegramRinging(),
    ): AppCallContext = tracker.onPosted(notification, at).single()

    @Test
    fun aRingingNotificationCreatesARingingContextWithTheAppAndTheCaller() {
        val answer = FakeAppIntent("answer")
        val decline = FakeAppIntent("decline")
        val context = ring(notification = AppCallFixtures.telegramRinging(answer = answer, decline = decline))

        assertEquals("call-1", context.callId)
        assertEquals(TELEGRAM, context.packageName)
        assertEquals("Telegram", context.label)
        assertEquals(CALLER, context.caller)
        assertEquals(AppCallState.RINGING, context.state)
        assertEquals(T0, context.startedAt)
        assertNull(context.answeredAt)
        assertSame(answer, context.answer)
        assertSame(decline, context.decline)
        assertNull(context.end)
        assertEquals(listOf(context), tracker.current)
        assertEquals(context, tracker.find("call-1"))
    }

    @Test
    fun theCallerIsTheNotificationTitleOrNullWhenAbsentOrBlank() {
        assertNull(ring(notification = AppCallFixtures.telegramRinging(caller = null)).caller)
        assertNull(ring(notification = AppCallFixtures.telegramRinging(key = "b", caller = "  ")).caller)
    }

    @Test
    fun longNamesAreCutToTheWireLimits() {
        val long = AppCallFixtures.notification("k", ZALO, 1, caller = "a".repeat(300))
        val labels = AppLabels { "b".repeat(100) }
        val context = AppCallTracker({ "c" }, labels).onPosted(long, T0).single()

        assertEquals(CallConstants.APP_CALL_CALLER_MAX, context.caller?.length)
        assertEquals(CallConstants.APP_CALL_LABEL_MAX, context.label.length)
    }

    @Test
    fun aRepostOfTheRingingNotificationUpdatesTheContextInPlace() {
        val first = ring()
        val newAnswer = FakeAppIntent("answer2")
        val update =
            tracker.onPosted(
                AppCallFixtures.telegramRinging(answer = newAnswer, caller = "Trần Thị B"),
                T0 + 1_000,
            )

        val context = update.single()
        assertEquals(first.callId, context.callId)
        assertEquals(T0, context.startedAt)
        assertSame(newAnswer, context.answer)
        assertEquals("Trần Thị B", context.caller)
        assertEquals(1, tracker.current.size)
    }

    @Test
    fun aRingingContextLinksToTheOngoingNotificationOfTheSamePackageWithinTheWindow() {
        val ringing = ring()
        val removed = tracker.onRemoved(RINGING_KEY, T0 + 4_000).single()
        assertEquals("waiting for the in-call notification", AppCallState.RINGING, removed.state)
        assertNull("the removed notification offers nothing", removed.answer)
        assertNull(removed.decline)

        val end = FakeAppIntent("end")
        val linked =
            tracker.onPosted(AppCallFixtures.telegramInCall(actions = listOf(end)), T0 + 4_000 + window).single()

        assertEquals(ringing.callId, linked.callId)
        assertEquals(AppCallState.ONGOING, linked.state)
        assertEquals(T0 + 4_000 + window, linked.answeredAt)
        assertEquals(T0, linked.startedAt)
        assertEquals(CALLER, linked.caller)
        assertSame(end, linked.end)
        assertNull(linked.answer)
        assertEquals(listOf(linked), tracker.current)
    }

    @Test
    fun anOngoingNotificationAfterTheWindowDoesNotLink() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 4_000)

        val late = tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 4_000 + window + 1)

        assertTrue("an ordinary notification creates nothing", late.isEmpty())
        assertTrue(tracker.onLinkWindowEnd("call-1", T0 + 4_000, T0 + 4_000 + window).single().ended)
    }

    @Test
    fun anOngoingNotificationWithoutCallStyleLinksButCreatesNothingAlone() {
        val alone = AppCallFixtures.notification("c", TELEGRAM, ongoing = true, actions = listOf(FakeAppIntent("e")))
        assertTrue(tracker.onPosted(alone, T0).isEmpty())
        assertTrue(tracker.current.isEmpty())
        tracker.onRemoved("c", T0 + 50)

        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        val end = FakeAppIntent("end")
        val linked =
            tracker.onPosted(
                AppCallFixtures.notification("c", TELEGRAM, ongoing = true, actions = listOf(end)),
                T0 + 400,
            )

        assertEquals(AppCallState.ONGOING, linked.single().state)
        assertSame(end, linked.single().end)
    }

    @Test
    fun aNotificationThatCannotBeAnInCallNotificationIsReadNoFurtherThanItsShape() {
        var reads = 0
        val read: () -> Unit = { reads++ }
        // One that stood before the call; then one of another app, one that is not ongoing, one after the window.
        tracker.onPosted(
            AppCallFixtures.notification("a", TELEGRAM, ongoing = true, onCallerRead = read, onIntentsRead = read),
            T0 - 10_000,
        )
        ring()
        tracker.onPosted(
            AppCallFixtures.notification("a", TELEGRAM, ongoing = true, onCallerRead = read, onIntentsRead = read),
            T0 + 10,
        )
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        tracker.onPosted(
            AppCallFixtures.notification("b", ZALO, ongoing = true, onCallerRead = read, onIntentsRead = read),
            T0 + 150,
        )
        tracker.onPosted(
            AppCallFixtures.notification("c", TELEGRAM, ongoing = false, onCallerRead = read, onIntentsRead = read),
            T0 + 200,
        )
        tracker.onPosted(
            AppCallFixtures.notification("d", TELEGRAM, ongoing = true, onCallerRead = read, onIntentsRead = read),
            T0 + 100 + window + 1,
        )

        assertEquals("nothing but the shape was read", 0, reads)
    }

    @Test
    fun anOngoingNotificationThatStoodBeforeTheCallIsNeverItsInCallNotification() {
        val music =
            AppCallFixtures.notification(MUSIC_KEY, TELEGRAM, ongoing = true, actions = listOf(FakeAppIntent("x")))
        tracker.onPosted(music, T0 - 60_000)
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 4_000)

        val update =
            AppCallFixtures.notification(MUSIC_KEY, TELEGRAM, ongoing = true, actions = listOf(FakeAppIntent("pause")))
        assertTrue("an update within 3 s is not linked", tracker.onPosted(update, T0 + 5_000).isEmpty())
        assertEquals(AppCallState.RINGING, tracker.find("call-1")?.state)
        assertEquals(
            AppCallEndReason.MISSED,
            tracker
                .onLinkWindowEnd("call-1", T0 + 4_000, T0 + 4_000 + window)
                .single()
                .endReason,
        )
    }

    @Test
    fun anUpdateOfAStandingNotificationWhileTheCallRingsIsNeverItsInCallNotification() {
        tracker.onPosted(AppCallFixtures.notification(MUSIC_KEY, TELEGRAM, ongoing = true), T0 - 60_000)
        ring()
        tracker.onPosted(AppCallFixtures.notification(MUSIC_KEY, TELEGRAM, ongoing = true), T0 + 1_000)

        val removed = tracker.onRemoved(RINGING_KEY, T0 + 2_000).single()

        assertEquals("still waiting for the in-call notification", AppCallState.RINGING, removed.state)
        assertTrue(removed.waitingForInCall)
    }

    @Test
    fun anInCallNotificationPostedBeforeTheRingingOneIsRemovedLinksAtTheRemoval() {
        val ringing = ring()
        val end = FakeAppIntent("end")

        val early = tracker.onPosted(AppCallFixtures.telegramInCall(actions = listOf(end)), T0 + 1_500)
        assertTrue("the ringing notification still stands", early.isEmpty())
        assertEquals(AppCallState.RINGING, tracker.find(ringing.callId)?.state)

        val linked = tracker.onRemoved(RINGING_KEY, T0 + 1_600).single()

        assertEquals(ringing.callId, linked.callId)
        assertEquals(AppCallState.ONGOING, linked.state)
        assertEquals("answered when the in-call notification was posted", T0 + 1_500, linked.answeredAt)
        assertSame(end, linked.end)
        assertNull(linked.unlinkedAt)
        assertTrue("it holds the call now", tracker.onRemoved(IN_CALL_KEY, T0 + 9_000).single().ended)
    }

    @Test
    fun anEarlyInCallNotificationRemovedBeforeTheRingingOneIsForgotten() {
        ring()
        tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 500)
        tracker.onRemoved(IN_CALL_KEY, T0 + 700)

        val removed = tracker.onRemoved(RINGING_KEY, T0 + 1_000).single()

        assertTrue(removed.waitingForInCall)
    }

    @Test
    fun theWindowComparesThePostTimeWithTheRemoval() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 1_000)

        val inside = tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 1_000 + 2_900).single()
        assertEquals("posted 2.9 s after the removal", AppCallState.ONGOING, inside.state)

        val other = AppCallTracker({ "other" }, AppCallFixtures.labels)
        other.onPosted(AppCallFixtures.telegramRinging(), T0)
        other.onRemoved(RINGING_KEY, T0 + 1_000)
        assertTrue(
            "posted 3.2 s after the removal",
            other.onPosted(AppCallFixtures.telegramInCall(), T0 + 1_000 + 3_200).isEmpty(),
        )
    }

    @Test
    fun aRingingCallWithoutACallerNeverAsksTheInCallNotificationForOne() {
        var callerReads = 0
        ring(notification = AppCallFixtures.telegramRinging(caller = null))
        tracker.onRemoved(RINGING_KEY, T0 + 100)

        val linked =
            tracker.onPosted(AppCallFixtures.telegramInCall(onCallerRead = { callerReads++ }), T0 + 300).single()

        assertEquals(AppCallState.ONGOING, linked.state)
        assertNull("the in-call title is not a caller", linked.caller)
        assertEquals(0, callerReads)
    }

    @Test
    fun theInCallNotificationOfALinkIsReadForItsActionsNotForItsCaller() {
        var callerReads = 0
        var intentReads = 0
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)

        val inCall =
            AppCallFixtures.notification(
                IN_CALL_KEY,
                TELEGRAM,
                ongoing = true,
                actions = listOf(FakeAppIntent("end")),
                onCallerRead = { callerReads++ },
                onIntentsRead = { intentReads++ },
            )
        tracker.onPosted(inCall, T0 + 300)

        assertEquals(0, callerReads)
        assertEquals(1, intentReads)
    }

    @Test
    fun onlyAnOngoingNotificationOfTheSamePackageLinks() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0)

        val notOngoing = tracker.onPosted(AppCallFixtures.telegramInCall(ongoing = false), T0 + 100)
        val otherApp =
            tracker.onPosted(
                AppCallFixtures.notification("z", ZALO, ongoing = true, actions = listOf(FakeAppIntent("x"))),
                T0 + 200,
            )

        assertTrue(notOngoing.isEmpty())
        assertTrue(otherApp.isEmpty())
        assertEquals(AppCallState.RINGING, tracker.find("call-1")?.state)
    }

    @Test
    fun aCallStyleOngoingNotificationLinksWithItsHangUpIntent() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 500)
        val hangUp = FakeAppIntent("hangup")
        val ongoing =
            AppCallFixtures.notification(
                "style",
                TELEGRAM,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = hangUp,
                actions = listOf(FakeAppIntent("a"), FakeAppIntent("b")),
            )

        val linked = tracker.onPosted(ongoing, T0 + 900).single()

        assertEquals("call-1", linked.callId)
        assertSame(hangUp, linked.end)
    }

    @Test
    fun aCallStyleOngoingNotificationWhileTheRingingOneStandsLinks() {
        ring()
        val ongoing =
            AppCallFixtures.notification(
                "style",
                TELEGRAM,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = FakeAppIntent("hangup"),
            )

        val linked = tracker.onPosted(ongoing, T0 + 900).single()
        assertEquals("call-1", linked.callId)
        assertEquals(AppCallState.ONGOING, linked.state)
        assertTrue(
            "the ringing notification going away changes nothing",
            tracker.onRemoved(RINGING_KEY, T0 + 950).isEmpty(),
        )
        assertEquals(1, tracker.current.size)
    }

    @Test
    fun theSameNotificationChangingFromIncomingToOngoingKeepsTheContext() {
        ring()
        val ongoing =
            AppCallFixtures.notification(
                RINGING_KEY,
                TELEGRAM,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = FakeAppIntent("hangup"),
            )

        val linked = tracker.onPosted(ongoing, T0 + 2_000).single()

        assertEquals("call-1", linked.callId)
        assertEquals(AppCallState.ONGOING, linked.state)
        assertEquals(T0 + 2_000, linked.answeredAt)
        assertEquals(1, tracker.current.size)
    }

    @Test
    fun anOngoingCallStyleNotificationWithoutARingingContextStartsAnOngoingOne() {
        val ongoing =
            AppCallFixtures.notification(
                "outgoing",
                TELEGRAM,
                callType = AppCallFixtures.CALL_TYPE_ONGOING,
                ongoing = true,
                hangUp = FakeAppIntent("hangup"),
                caller = CALLER,
            )

        val context = tracker.onPosted(ongoing, T0).single()

        assertEquals(AppCallState.ONGOING, context.state)
        assertEquals(CALLER, context.caller)
        assertNull("nobody answered it on this phone", context.answeredAt)
        assertNotNull(context.end)
    }

    @Test
    fun anOrdinaryOngoingNotificationWithoutARingingContextIsIgnored() {
        assertTrue(tracker.onPosted(AppCallFixtures.telegramInCall(), T0).isEmpty())
        assertTrue(tracker.current.isEmpty())
    }

    @Test
    fun removingTheInCallNotificationEndsTheCall() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 500)

        val ended = tracker.onRemoved(IN_CALL_KEY, T0 + 60_000).single()

        assertTrue(ended.ended)
        assertEquals(AppCallState.ENDED, ended.state)
        assertEquals(AppCallEndReason.ENDED, ended.endReason)
        assertEquals(T0 + 60_000, ended.endedAt)
        assertNull(tracker.find("call-1"))
        assertTrue("the context is forgotten", tracker.current.isEmpty())
    }

    @Test
    fun theInCallNotificationUpdatesTheEndActionAsItsActionsChange() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        val first = FakeAppIntent("end")
        tracker.onPosted(AppCallFixtures.telegramInCall(actions = listOf(first)), T0 + 500)

        val crowded =
            tracker.onPosted(
                AppCallFixtures.telegramInCall(actions = listOf(first, FakeAppIntent("mute"))),
                T0 + 1_000,
            )
        assertNull("two actions: no end", crowded.single().end)

        val second = FakeAppIntent("end2")
        val back = tracker.onPosted(AppCallFixtures.telegramInCall(actions = listOf(second)), T0 + 1_500)
        assertSame(second, back.single().end)
    }

    @Test
    fun aDeclineSentByHandLiveEndsTheContextAsDeclinedAtOnce() {
        ring()
        tracker.markSent("call-1", AppCallAction.DECLINE)

        val ended = tracker.onRemoved(RINGING_KEY, T0 + 12).single()

        assertTrue(ended.ended)
        assertEquals(AppCallEndReason.DECLINED, ended.endReason)
        assertEquals(T0 + 12, ended.endedAt)
        assertTrue(tracker.current.isEmpty())
    }

    @Test
    fun aRingingNotificationThatVanishesAndNothingFollowsIsMissed() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 20_000)

        val ended = tracker.onLinkWindowEnd("call-1", T0 + 20_000, T0 + 20_000 + window).single()

        assertEquals(AppCallEndReason.MISSED, ended.endReason)
        assertEquals(T0 + 20_000 + window, ended.endedAt)
        assertTrue(tracker.current.isEmpty())
    }

    @Test
    fun anAnswerSentButNoInCallNotificationIsUnknown() {
        ring()
        tracker.markSent("call-1", AppCallAction.ANSWER)
        tracker.onRemoved(RINGING_KEY, T0 + 160)

        val ended = tracker.onLinkWindowEnd("call-1", T0 + 160, T0 + 160 + window).single()

        assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
    }

    @Test
    fun theWindowEndOfALinkedOrUnknownCallChangesNothing() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 500)

        assertTrue(tracker.onLinkWindowEnd("call-1", T0 + 100, T0 + 100 + window).isEmpty())
        assertTrue(tracker.onLinkWindowEnd("nope", T0 + 100, T0 + 100 + window).isEmpty())
        assertEquals(AppCallState.ONGOING, tracker.find("call-1")?.state)
    }

    @Test
    fun aTimerOfAnEarlierWaitChangesNothing() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        tracker.onPosted(AppCallFixtures.telegramRinging(), T0 + 200)
        tracker.onRemoved(RINGING_KEY, T0 + 2_000)

        assertTrue("the first wait is over", tracker.onLinkWindowEnd("call-1", T0 + 100, T0 + 100 + window).isEmpty())
        assertEquals(AppCallState.RINGING, tracker.find("call-1")?.state)
        assertTrue(tracker.onLinkWindowEnd("call-1", T0 + 2_000, T0 + 2_000 + window).single().ended)
    }

    @Test
    fun theRemovalOfAnUntrackedNotificationChangesNothing() {
        ring()
        assertTrue(tracker.onRemoved("0|other|1|null|1", T0 + 10).isEmpty())
        assertEquals(AppCallState.RINGING, tracker.find("call-1")?.state)
    }

    @Test
    fun callsOfDifferentAppsAreIndependent() {
        val telegram = ring()
        val zalo =
            tracker
                .onPosted(AppCallFixtures.notification("z1", ZALO, 1, caller = "Z"), T0 + 10)
                .single()
        assertEquals(setOf(telegram.callId, zalo.callId), tracker.current.map { it.callId }.toSet())

        tracker.onRemoved("z1", T0 + 20)
        val zaloInCall =
            AppCallFixtures.notification("z2", ZALO, ongoing = true, actions = listOf(FakeAppIntent("end")))
        assertEquals(zalo.callId, tracker.onPosted(zaloInCall, T0 + 30).single().callId)
        assertEquals(AppCallState.RINGING, tracker.find(telegram.callId)?.state)
        assertEquals(AppCallState.ONGOING, tracker.find(zalo.callId)?.state)
    }

    @Test
    fun screeningAndOrdinaryNotificationsCreateNothingAndReadNothing() {
        var reads = 0
        val screening =
            AppCallFixtures.notification(
                "s",
                TELEGRAM,
                AppCallFixtures.CALL_TYPE_SCREENING,
                onCallerRead = { reads++ },
            )
        val message = AppCallFixtures.notification("m", TELEGRAM, onCallerRead = { reads++ })

        assertTrue(tracker.onPosted(screening, T0).isEmpty())
        assertTrue(tracker.onPosted(message, T0).isEmpty())
        assertTrue(tracker.current.isEmpty())
        assertEquals(0, reads)
    }

    @Test
    fun losingTheListenerEndsEveryContextAsUnknown() {
        ring()
        tracker.onPosted(AppCallFixtures.notification("z", ZALO, 1), T0 + 5)

        val ended = tracker.onListenerLost(T0 + 1_000)

        assertEquals(2, ended.size)
        assertTrue(ended.all { it.ended && it.endReason == AppCallEndReason.UNKNOWN && it.endedAt == T0 + 1_000 })
        assertTrue(tracker.current.isEmpty())
    }

    @Test
    fun onlyACallThatEndedAsEndedKeepsItsAnswerTime() {
        ring()
        tracker.onRemoved(RINGING_KEY, T0 + 100)
        tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 500)

        val lost = tracker.onListenerLost(T0 + 2_000).single()

        assertEquals(AppCallEndReason.UNKNOWN, lost.endReason)
        assertNull("unknown means no answer time (call_event/app_call)", lost.answeredAt)

        tracker.onPosted(AppCallFixtures.telegramRinging(), T0 + 3_000)
        tracker.onRemoved(RINGING_KEY, T0 + 3_100)
        tracker.onPosted(AppCallFixtures.telegramInCall(), T0 + 3_500)
        assertEquals(T0 + 3_500, tracker.onRemoved(IN_CALL_KEY, T0 + 9_000).single().answeredAt)
    }

    @Test
    fun theContextNeverShowsTheCallerInItsText() {
        val context = ring()
        assertFalse(context.toString().contains(CALLER))
        assertFalse(context.toString().contains("Nguy"))
    }

    private companion object {
        const val T0 = 1_727_150_400_000L
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
        const val MUSIC_KEY = "0|org.telegram.messenger|7|download|10148"
    }
}
