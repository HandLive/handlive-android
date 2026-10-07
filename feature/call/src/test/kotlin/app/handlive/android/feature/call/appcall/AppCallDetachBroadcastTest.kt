package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.call.AppCallControls
import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALL-05 E11 on the wire: the Mac keeps the in-call panel, without End, when the user swipes the in-call notification
 * away during a call that holds the audio mode; the audio mode is watched only while a call is detached, and its end
 * closes the panel as `unknown`.
 */
class AppCallDetachBroadcastTest {
    /** A Telegram call answered on the phone, the Mac connected with app calls in effect. */
    private fun CallHarness.inCall() {
        connect(mac.withAppCalls())
        appPost(AppCallFixtures.telegramRinging())
        advance(4_000)
        appRemove(RINGING_KEY)
        advance(500)
        appPost(AppCallFixtures.telegramInCall())
        advance(60_000)
    }

    @Test
    fun aSwipedInCallNotificationKeepsTheMacPanelInCallWithoutEndUntilTheAudioModeLeaves() =
        runTest {
            val h = CallHarness(this)
            h.inCall()

            h.appRemove(IN_CALL_KEY, byApp = false)

            val detached = h.mac.appCalls().last()
            assertEquals(AppCallState.ONGOING, detached.state)
            assertEquals(AppCallControls(answer = false, decline = false, end = false), detached.controls)
            assertTrue(h.mode.watching)

            h.advance(30_000)
            h.mode.leave()
            h.run()

            val ended = h.mac.appCalls().last()
            assertEquals(AppCallState.ENDED, ended.state)
            assertEquals(AppCallEndReason.UNKNOWN, ended.endReason)
            assertEquals(BASE_TS + 94_500, ended.endedAt)
            assertNull(ended.answeredAt)
            val callIds = h.mac.appCalls().map { it.callId }
            assertEquals(1, callIds.toSet().size)
            assertFalse("the mode is no longer watched", h.mode.watching)
            assertEquals(1, h.mode.watches)
            assertTrue(h.appTracker.current.isEmpty())
        }

    @Test
    fun aSwipedInCallNotificationWithoutACallInTheAudioModeClosesTheMacPanelAsUnknown() =
        runTest {
            val h = CallHarness(this)
            h.inCall()
            h.mode.inCommunication = false

            h.appRemove(IN_CALL_KEY, byApp = false)

            val last = h.mac.appCalls().last()
            assertEquals(AppCallEndReason.UNKNOWN, last.endReason)
            assertEquals(0, h.mode.watches)
        }

    @Test
    fun theAppRemovingItsInCallNotificationStillClosesThePanelAsEnded() =
        runTest {
            val h = CallHarness(this)
            h.inCall()

            h.appRemove(IN_CALL_KEY, byApp = true)

            val last = h.mac.appCalls().last()
            assertEquals(AppCallEndReason.ENDED, last.endReason)
            assertEquals(0, h.mode.watches)
        }

    @Test
    fun theInCallNotificationPostedAgainBringsEndBackAndStopsWatchingTheMode() =
        runTest {
            val h = CallHarness(this)
            h.inCall()
            h.appRemove(IN_CALL_KEY, byApp = false)

            h.appPost(AppCallFixtures.telegramInCall())

            val again = h.mac.appCalls().last()
            assertEquals(AppCallState.ONGOING, again.state)
            assertTrue(again.controls.end)
            assertFalse(h.mode.watching)
            assertEquals(1, h.mode.stops)
        }

    @Test
    fun anUploadOfTheAppAfterTheSwipeNeverBringsEndBackNorHasItsCancelSent() =
        runTest {
            val h = CallHarness(this)
            h.inCall()
            h.appRemove(IN_CALL_KEY, byApp = false)
            val callId =
                h.mac
                    .appCalls()
                    .last()
                    .callId
            val sentBefore = h.mac.appCalls().size
            val cancel = FakeAppIntent("cancel upload")

            h.appPost(
                AppCallFixtures.notification(
                    UPLOAD_KEY,
                    AppCallFixtures.TELEGRAM,
                    ongoing = true,
                    actions = listOf(cancel),
                ),
            )
            val ack = h.action(h.mac, callId, "end")

            assertEquals("nothing changed for the Mac", sentBefore, h.mac.appCalls().size)
            assertFalse(ack.ok)
            assertEquals(ErrorCode.CALL_APP_ACTION_UNAVAILABLE.name, ack.error?.code)
            assertEquals(0, cancel.sends)
        }

    private companion object {
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
        const val UPLOAD_KEY = "0|org.telegram.messenger|77|null|10148"
    }
}
