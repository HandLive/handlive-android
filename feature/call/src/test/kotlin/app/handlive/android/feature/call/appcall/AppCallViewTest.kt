package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallAnswerMode
import app.handlive.android.core.protocol.call.AppCallAudio
import app.handlive.android.core.protocol.call.AppCallControls
import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `call_event/app_call` from a context: the fields and the `controls` the client may press (CALL-05). */
class AppCallViewTest {
    private val ringing =
        AppCallContext(
            callId = "call-1",
            packageName = AppCallFixtures.TELEGRAM,
            label = "Telegram",
            caller = AppCallFixtures.CALLER,
            state = AppCallState.RINGING,
            startedAt = 1_000L,
            notificationKey = "k",
            answer = FakeAppIntent("answer"),
            decline = FakeAppIntent("decline"),
        )

    @Test
    fun aRingingCallOffersAnswerAndDeclineWhenTheNotificationHasThem() {
        val data = AppCallView.of(ringing, AppCallAnswerMode.DIRECT)

        assertEquals("call-1", data.callId)
        assertEquals(AppCallFixtures.TELEGRAM, data.app.packageName)
        assertEquals("Telegram", data.app.label)
        assertEquals(AppCallFixtures.CALLER, data.caller)
        assertEquals(AppCallState.RINGING, data.state)
        assertEquals(AppCallControls(answer = true, decline = true, end = false), data.controls)
        assertEquals(AppCallAnswerMode.DIRECT, data.answerMode)
        assertEquals(AppCallAudio.PHONE, data.audio)
        assertEquals(1_000L, data.startedAt)
        assertNull(data.answeredAt)
        assertNull(data.endedAt)
        assertNull(data.endReason)
    }

    @Test
    fun eachControlNeedsItsOwnIntent() {
        val noAnswer = AppCallView.of(ringing.copy(answer = null), AppCallAnswerMode.TAP)
        val noDecline = AppCallView.of(ringing.copy(decline = null), AppCallAnswerMode.TAP)

        assertEquals(AppCallControls(answer = false, decline = true, end = false), noAnswer.controls)
        assertEquals(AppCallControls(answer = true, decline = false, end = false), noDecline.controls)
        assertEquals(AppCallAnswerMode.TAP, noAnswer.answerMode)
    }

    @Test
    fun aRingingCallWhoseNotificationIsGoneOffersNothing() {
        val gone = ringing.copy(answer = null, decline = null, unlinkedAt = 2_000L)

        assertEquals(AppCallControls(false, false, false), AppCallView.of(gone, AppCallAnswerMode.DIRECT).controls)
    }

    @Test
    fun anOngoingCallOffersEndOnlyWithAnEndAction() {
        val ongoing = ringing.copy(state = AppCallState.ONGOING, answer = null, decline = null, answeredAt = 3_000L)

        val withEnd = AppCallView.of(ongoing.copy(end = FakeAppIntent("end")), AppCallAnswerMode.DIRECT)
        val without = AppCallView.of(ongoing, AppCallAnswerMode.DIRECT)

        assertEquals(AppCallControls(answer = false, decline = false, end = true), withEnd.controls)
        assertEquals(3_000L, withEnd.answeredAt)
        assertEquals(AppCallControls(false, false, false), without.controls)
    }

    @Test
    fun anEndedCallCarriesItsReasonAndOffersNothing() {
        val ended =
            ringing.copy(
                state = AppCallState.ENDED,
                endedAt = 9_000L,
                endReason = AppCallEndReason.MISSED,
                answer = null,
                decline = null,
            )

        val data = AppCallView.of(ended, AppCallAnswerMode.DIRECT)

        assertEquals(AppCallState.ENDED, data.state)
        assertEquals(9_000L, data.endedAt)
        assertEquals(AppCallEndReason.MISSED, data.endReason)
        assertEquals(AppCallControls(false, false, false), data.controls)
    }

    @Test
    fun anAbsentCallerIsWrittenAsNull() {
        assertNull(AppCallView.of(ringing.copy(caller = null), AppCallAnswerMode.DIRECT).caller)
    }
}
