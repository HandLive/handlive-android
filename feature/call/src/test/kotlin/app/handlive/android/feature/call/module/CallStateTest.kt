package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallControls
import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallLogType
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.core.protocol.call.CallPresentation
import app.handlive.android.core.protocol.call.HfpControl
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CALL-01 API 1 logic 3–6: `call_event/state` per session, when it is sent, and when it is not. */
class CallStateTest {
    @Test
    fun everySessionGetsItsOwnStateWithItsControls() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.sim(1, PhoneState.RINGING)
            h.ring()

            val mac = h.mac.states().single()
            val iphone = h.iphone.states().single()
            assertEquals(mac.callId, iphone.callId)
            assertEquals(CallDirection.INCOMING, mac.direction)
            assertEquals(CallPhase.RINGING, mac.state)
            assertEquals("+84900000123", mac.number)
            assertEquals("Nguyễn Văn A", mac.displayName)
            assertEquals(CallPresentation.ALLOWED, mac.presentation)
            assertEquals(1, mac.subId)
            assertEquals("SIM 1", mac.simLabel)
            assertEquals(BASE_TS, mac.startedAt)
            assertEquals(CallControls(true, true, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), mac.controls)
            assertEquals(
                "answer is for a Mac only",
                CallControls(false, true, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE),
                iphone.controls,
            )
            assertFalse(mac.hfpConnected)
            assertEquals("phone", mac.audioOn)
        }

    @Test
    fun aNumberAfterTheFirstRingingIsSentAgainWithTheSameCallId() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.phone(PhoneState.RINGING)
            h.copy(PhoneState.RINGING, null)
            h.advance(40)
            h.copy(PhoneState.RINGING, "0900000123")
            h.copy(PhoneState.RINGING, "0900000123")

            val states = h.mac.states()
            assertEquals(2, states.size)
            assertNull(states[0].number)
            assertEquals(CallPresentation.UNKNOWN, states[0].presentation)
            assertEquals(states[0].callId, states[1].callId)
            assertEquals("+84900000123", states[1].number)
            assertEquals("Nguyễn Văn A", states[1].displayName)
        }

    @Test
    fun anAnsweredCallThenItsEnd() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            h.advance(5_198)
            h.phone(PhoneState.OFFHOOK)
            h.advance(120_000)
            h.phone(PhoneState.IDLE)

            val (ringing, offhook, idle) = h.mac.states()
            assertEquals(CallPhase.OFFHOOK, offhook.state)
            assertEquals(BASE_TS + 5_198, offhook.answeredAt)
            assertEquals(CallControls(false, false, true, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), offhook.controls)
            assertEquals(CallPhase.IDLE, idle.state)
            assertEquals(CallEndReason.ENDED, idle.endReason)
            assertEquals(BASE_TS + 125_198, idle.endedAt)
            assertEquals(CallControls.NONE, idle.controls)
            assertEquals(ringing.callId, idle.callId)
        }

    @Test
    fun aWaitingCallIsInformationOnly() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            h.phone(PhoneState.OFFHOOK)
            h.copy(PhoneState.RINGING, "0900000456")
            h.phone(PhoneState.RINGING)

            val waiting = h.mac.states().last()
            assertTrue(waiting.waiting)
            assertEquals(CallPhase.RINGING, waiting.state)
            assertEquals("+84900000456", waiting.waitingNumber)
            assertEquals("Trần Thị B", waiting.waitingDisplayName)
            assertEquals(CallControls.NONE, waiting.controls)

            h.phone(PhoneState.OFFHOOK)
            val back = h.mac.states().last()
            assertFalse(back.waiting)
            assertNull(back.waitingNumber)
            assertTrue(back.controls.end)
        }

    @Test
    fun aClientConnectingMidwayGetsTheCallButNotOneThatEnded() =
        runTest {
            val h = CallHarness(this)
            h.ring()
            h.phone(PhoneState.OFFHOOK)
            assertTrue(h.mac.states().isEmpty())

            h.connect(h.mac)
            val midway = h.mac.states().single()
            assertEquals(CallPhase.OFFHOOK, midway.state)

            h.phone(PhoneState.IDLE)
            h.connect(h.iphone)
            assertTrue("an ended call goes only where it was seen", h.iphone.states().isEmpty())
            assertEquals(
                CallPhase.IDLE,
                h.mac
                    .states()
                    .last()
                    .state,
            )

            h.mac.reconnect()
            h.connect(h.mac)
            assertEquals("a new session after the end gets nothing", 2, h.mac.states().size)
        }

    @Test
    fun sessionsWithoutCallsInEffectGetNothing() =
        runTest {
            val h = CallHarness(this)
            h.iphone.effective.value = emptySet()
            h.connect(h.mac, h.iphone)
            h.ring()
            assertTrue(h.iphone.states().isEmpty())

            // Calls become effective on it while the call rings (capability/update): it gets the call now (E8).
            h.iphone.effective.value = setOf(app.handlive.android.core.transport.capability.Feature.CALL)
            h.run()
            assertEquals(
                CallPhase.RINGING,
                h.iphone
                    .states()
                    .single()
                    .state,
            )
            assertEquals(1, h.mac.states().size)
        }

    @Test
    fun controlsFollowTheAnswerPermission() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            h.access.granted -= AndroidPermissions.ANSWER_PHONE_CALLS
            h.module.onPermissionsChanged()
            h.run()

            val (before, after) = h.mac.states()
            assertTrue(before.controls.answer)
            assertEquals(CallControls.NONE, after.controls)
            assertEquals(before.callId, after.callId)
        }

    @Test
    fun theCallLogCorrectsAMissedCallOnce() =
        runTest {
            val h = CallHarness(this)
            h.module.watchLog(true)
            h.connect(h.mac)
            h.ring()
            h.advance(8_000)
            h.phone(PhoneState.IDLE)
            assertEquals(
                CallEndReason.MISSED,
                h.mac
                    .states()
                    .last()
                    .endReason,
            )

            h.callLog.add(type = 5, date = BASE_TS + 30)
            h.logChanged()
            val corrected = h.mac.states().last()
            assertEquals(3, h.mac.states().size)
            assertEquals(CallPhase.IDLE, corrected.state)
            assertEquals(CallEndReason.REJECTED, corrected.endReason)
            val new = h.mac.logNews().single()
            assertEquals(CallLogType.REJECTED, new.entry.type)
            assertEquals(corrected.callId, new.callId)

            h.logChanged()
            assertEquals("exactly one correction", 3, h.mac.states().size)
        }

    @Test
    fun aCallAnsweredOnAnotherDeviceIsCorrectedToAnsweredElsewhere() =
        runTest {
            val h = CallHarness(this)
            h.module.watchLog(true)
            h.connect(h.mac)
            h.ring()
            h.phone(PhoneState.IDLE)
            h.callLog.add(type = 7, date = BASE_TS + 10)
            h.logChanged()
            assertEquals(
                CallEndReason.ANSWERED_ELSEWHERE,
                h.mac
                    .states()
                    .last()
                    .endReason,
            )
            assertEquals(
                CallLogType.INCOMING,
                h.mac
                    .logNews()
                    .single()
                    .entry.type,
            )
        }

    private companion object {
        const val UNAVAILABLE = HfpControl.UNAVAILABLE
    }
}
