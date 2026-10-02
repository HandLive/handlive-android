package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallAnswerMode
import app.handlive.android.core.protocol.call.AppCallControls
import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.appcall.AppCallFixtures.CALLER
import app.handlive.android.feature.call.appcall.AppCallFixtures.TELEGRAM
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.call.testing.FakeClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `call_event/app_call` (CALL-05, S→C): to every session with app calls in effect and to no other, once per change,
 * `ended` once, the current calls to a session that gets app calls in effect.
 */
class AppCallBroadcastTest {
    private val window = CallConstants.APP_CALL_LINK_WINDOW_MILLIS

    /** When the end of the link window is decided: the window and the grace for posts still on their way. */
    private val windowEnd = window + CallConstants.APP_CALL_LINK_GRACE_MILLIS

    @Test
    fun aRingingCallReachesOnlyTheSessionsWithAppCallsInEffect() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls(), h.iphone)

            h.appPost(AppCallFixtures.telegramRinging())

            val data = h.mac.appCalls().single()
            assertEquals(AppCallState.RINGING, data.state)
            assertEquals(TELEGRAM, data.app.packageName)
            assertEquals("Telegram", data.app.label)
            assertEquals(CALLER, data.caller)
            assertEquals(AppCallControls(answer = true, decline = true, end = false), data.controls)
            assertEquals(AppCallAnswerMode.DIRECT, data.answerMode)
            assertEquals(BASE_TS, data.startedAt)
            assertTrue("iPhone and iPad get nothing", h.iphone.events().isEmpty())
            assertTrue("no telephony state either", h.mac.states().isEmpty())
        }

    @Test
    fun aSessionWithOnlyTelephonyCallsInEffectGetsNoAppCall() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            assertEquals(setOf(Feature.CALL), h.mac.effective.value)

            h.appPost(AppCallFixtures.telegramRinging())

            assertTrue(h.mac.events().isEmpty())
        }

    @Test
    fun aSessionWithAppCallsButNotTelephonyGetsAppCalls() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.mac.effective.value = setOf(Feature.APP_CALLS)

            h.appPost(AppCallFixtures.telegramRinging())

            assertEquals(1, h.mac.appCalls().size)
        }

    @Test
    fun anIdenticalCallIsNotSentTwice() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            val notification = AppCallFixtures.telegramRinging()

            h.appPost(notification)
            h.appPost(AppCallFixtures.telegramRinging())
            h.appPost(AppCallFixtures.telegramRinging(caller = "Trần Thị B"))

            val sent = h.mac.appCalls()
            assertEquals(listOf(CALLER, "Trần Thị B"), sent.map { it.caller })
            assertEquals(1, sent.map { it.callId }.toSet().size)
        }

    @Test
    fun aCallGoesFromRingingToOngoingToEndedWithOneCallIdAndEndedOnce() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())

            h.appPost(AppCallFixtures.telegramRinging())
            h.advance(4_000)
            h.appRemove(RINGING_KEY)
            h.advance(500)
            h.appPost(AppCallFixtures.telegramInCall())
            h.advance(60_000)
            h.appRemove(IN_CALL_KEY)
            h.advance(10_000)

            val sent = h.mac.appCalls()
            assertEquals(
                listOf(AppCallState.RINGING, AppCallState.RINGING, AppCallState.ONGOING, AppCallState.ENDED),
                sent.map { it.state },
            )
            assertEquals(1, sent.map { it.callId }.toSet().size)
            assertEquals(
                "the ringing notification's removal takes the buttons away",
                AppCallControls(false, false, false),
                sent[1].controls,
            )
            assertEquals(AppCallControls(answer = false, decline = false, end = true), sent[2].controls)
            assertEquals(BASE_TS + 4_500, sent[2].answeredAt)
            val ended = sent.last()
            assertEquals(AppCallEndReason.ENDED, ended.endReason)
            assertEquals(BASE_TS + 64_500, ended.endedAt)
            assertEquals(AppCallControls(false, false, false), ended.controls)
            assertTrue("forgotten after ended", h.appTracker.current.isEmpty())
        }

    @Test
    fun aCallThatNothingFollowsEndsAsMissedWhenTheWindowPasses() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())

            h.advance(20_000)
            h.appRemove(RINGING_KEY)
            h.advance(windowEnd - 1)
            assertEquals(
                AppCallState.RINGING,
                h.mac
                    .appCalls()
                    .last()
                    .state,
            )
            h.advance(1)

            val ended = h.mac.appCalls().last()
            assertEquals(AppCallState.ENDED, ended.state)
            assertEquals(AppCallEndReason.MISSED, ended.endReason)
            assertEquals("when A-CALL decided it", BASE_TS + 20_000 + windowEnd, ended.endedAt)
        }

    @Test
    fun anInCallNotificationInsideTheWindowKeepsTheCallAlive() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            h.appRemove(RINGING_KEY)
            h.advance(1_500)
            h.appPost(AppCallFixtures.telegramInCall())

            h.advance(10 * windowEnd)

            assertEquals(
                AppCallState.ONGOING,
                h.mac
                    .appCalls()
                    .last()
                    .state,
            )
            assertEquals(1, h.appTracker.current.size)
        }

    @Test
    fun anInCallNotificationPostedInsideTheWindowButDeliveredAfterItStillLinks() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            val removedAt = h.wall()
            h.appRemove(RINGING_KEY)

            // Posted 2.9 s after the removal, delivered 3.3 s after it: past the window, before its end is decided.
            h.advance(window + 300)
            h.appPost(AppCallFixtures.telegramInCall(), postTime = removedAt + 2_900)
            h.advance(10 * windowEnd)

            val last = h.mac.appCalls().last()
            assertEquals(AppCallState.ONGOING, last.state)
            assertEquals(removedAt + 2_900, last.answeredAt)
        }

    @Test
    fun aPostAfterTheWindowDoesNotLinkEvenWhenItArrivesBeforeTheEndIsDecided() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            val removedAt = h.wall()
            h.appRemove(RINGING_KEY)

            h.advance(window + 300)
            h.appPost(AppCallFixtures.telegramInCall(), postTime = removedAt + 3_200)
            h.advance(windowEnd)

            val last = h.mac.appCalls().last()
            assertEquals(AppCallState.ENDED, last.state)
            assertEquals(AppCallEndReason.MISSED, last.endReason)
        }

    @Test
    fun anEndedCallGoesOnlyToTheSessionsThatSawIt() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls(), h.iphone)
            h.appPost(AppCallFixtures.telegramRinging())
            h.appRemove(RINGING_KEY)
            h.advance(windowEnd)

            assertEquals(
                AppCallState.ENDED,
                h.mac
                    .appCalls()
                    .last()
                    .state,
            )
            assertTrue(h.iphone.appCalls().isEmpty())
            val late = FakeClient("Mac mini", "pair-mini", h.wall).withAppCalls()
            h.connect(late)
            assertTrue("a session after the end gets nothing", late.appCalls().isEmpty())
        }

    @Test
    fun aSessionThatGetsAppCallsInEffectGetsTheCurrentCall() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.appPost(AppCallFixtures.telegramRinging())
            assertTrue(h.mac.appCalls().isEmpty())

            h.mac.withAppCalls()
            h.run()

            assertEquals(
                AppCallState.RINGING,
                h.mac
                    .appCalls()
                    .single()
                    .state,
            )
        }

    @Test
    fun aReconnectedSessionGetsTheCurrentCallAgain() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())

            h.disconnect(h.mac)
            h.mac.reconnect()
            h.connect(h.mac)

            assertEquals(2, h.mac.appCalls().size)
            assertEquals(h.mac.appCalls()[0], h.mac.appCalls()[1])
        }

    @Test
    fun theAnswerModeFollowsTheBackgroundStartExemption() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            assertEquals(
                AppCallAnswerMode.DIRECT,
                h.mac
                    .appCalls()
                    .last()
                    .answerMode,
            )

            h.exemption.held = false
            h.appEnvironmentChanged()
            assertEquals(
                AppCallAnswerMode.TAP,
                h.mac
                    .appCalls()
                    .last()
                    .answerMode,
            )

            h.exemption.held = true
            h.appEnvironmentChanged()
            assertEquals(
                AppCallAnswerMode.DIRECT,
                h.mac
                    .appCalls()
                    .last()
                    .answerMode,
            )
            assertEquals(3, h.mac.appCalls().size)
        }

    @Test
    fun twoCallsOfDifferentAppsAreTwoCallIds() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())

            h.appPost(AppCallFixtures.telegramRinging())
            h.appPost(AppCallFixtures.notification("z1", AppCallFixtures.ZALO, 1, caller = "Z"))

            assertEquals(
                2,
                h.mac
                    .appCalls()
                    .map { it.callId }
                    .toSet()
                    .size,
            )
            assertEquals(
                setOf("Telegram", "Zalo"),
                h.mac
                    .appCalls()
                    .map { it.app.label }
                    .toSet(),
            )
        }

    @Test
    fun withAppCallsOffNothingIsSentAndTheCallsInProgressEnd() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())

            h.appAccess.enabled = false
            h.appEnvironmentChanged()

            val ended = h.mac.appCalls().last()
            assertEquals(AppCallState.ENDED, ended.state)
            assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
            val before = h.mac.appCalls().size

            h.appPost(AppCallFixtures.telegramRinging(key = "another"))
            assertEquals("a notification is ignored while off", before, h.mac.appCalls().size)
            assertTrue(h.appTracker.current.isEmpty())
        }

    @Test
    fun losingTheListenerEndsTheCallsAsUnknown() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())

            h.appListenerLost()

            val ended = h.mac.appCalls().last()
            assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
        }

    @Test
    fun theCallerNeverAppearsInAnEventOfAnotherSession() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls(), h.iphone)
            h.appPost(AppCallFixtures.telegramRinging())

            assertFalse(h.iphone.events().any { it.contains("Nguy") })
            assertNull(h.iphone.appCalls().firstOrNull())
        }

    private companion object {
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
    }
}
