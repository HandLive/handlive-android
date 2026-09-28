package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.call.CallControls
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.core.protocol.call.CallPresentation
import app.handlive.android.core.protocol.call.HfpControl
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALL-01 step 5 and API 4, CALL-04 API 5: what goes to the relay module for iPhone and iPad without a session, and
 * when — on the virtual clock of the test scheduler.
 */
class CallPushTest {
    @Test
    fun theIncomingPushLeavesAsSoonAsTheNumberIsKnown() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.phone(PhoneState.RINGING)
            assertEquals(listOf(BASE_TS), h.offline.ringing.map { it.first })
            h.advance(120)
            assertTrue(h.offline.incoming.isEmpty())
            h.copy(PhoneState.RINGING, "0900000123")

            val push = h.offline.incoming.single()
            assertEquals("no wait once the number is known", BASE_TS + 120, push.at)
            assertEquals("+84900000123", push.state.number)
            assertEquals("Nguyễn Văn A", push.state.displayName)
            assertEquals(CallPhase.RINGING, push.state.state)
            assertEquals(setOf("pair-mac"), push.connected)
            h.advance(1_000)
            h.copy(PhoneState.RINGING, null)
            assertEquals("one push per call", 1, h.offline.incoming.size)
        }

    @Test
    fun withoutTheNumberThePushLeaves300MsAfterRinging() =
        runTest {
            val h = CallHarness(this)
            h.phone(PhoneState.RINGING)
            h.advance(299)
            assertTrue(h.offline.incoming.isEmpty())
            h.advance(1)
            val push = h.offline.incoming.single()
            assertEquals(BASE_TS + 300, push.at)
            assertNull(push.state.number)
            assertEquals(CallPresentation.UNKNOWN, push.state.presentation)
            assertTrue(push.connected.isEmpty())
        }

    @Test
    fun withoutTheCallLogPermissionThereIsNothingToWaitFor() =
        runTest {
            val h = CallHarness(this)
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            h.phone(PhoneState.RINGING)
            assertEquals(
                BASE_TS,
                h.offline.incoming
                    .single()
                    .at,
            )
        }

    @Test
    fun aWithheldNumberIsPushedOnceBothCopiesSaidSo() =
        runTest {
            val h = CallHarness(this)
            h.phone(PhoneState.RINGING)
            h.advance(5)
            h.copy(PhoneState.RINGING, null)
            h.copy(PhoneState.RINGING, null)
            val push = h.offline.incoming.single()
            assertEquals(BASE_TS + 5, push.at)
            assertEquals(CallPresentation.RESTRICTED, push.state.presentation)
        }

    @Test
    fun thePushCarriesTheStateAnIphoneWouldGet() =
        runTest {
            val h = CallHarness(this)
            h.ring()
            val state =
                h.offline.incoming
                    .single()
                    .state
            assertEquals(CallControls(false, true, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), state.controls)
            assertEquals("phone", state.audioOn)
        }

    @Test
    fun aCallAnsweredBeforeThePushIsNotPushedAndItsRingingEnds() =
        runTest {
            val h = CallHarness(this)
            h.phone(PhoneState.RINGING)
            h.advance(100)
            h.phone(PhoneState.OFFHOOK)
            h.advance(1_000)
            assertTrue(h.offline.incoming.isEmpty())
            assertEquals(listOf(BASE_TS + 100), h.offline.ringingEnded.map { it.first })
        }

    @Test
    fun aWaitingCallIsNeverPushed() =
        runTest {
            val h = CallHarness(this)
            h.ring()
            h.phone(PhoneState.OFFHOOK)
            h.copy(PhoneState.RINGING, "0900000456")
            h.phone(PhoneState.RINGING)
            h.advance(1_000)
            assertEquals(1, h.offline.incoming.size)
            assertEquals(1, h.offline.ringing.size)
            assertEquals(1, h.offline.ringingEnded.size)
        }

    @Test
    fun withTheCallLogAMissedCallIsPushedFromItsEntry() =
        runTest {
            val h = CallHarness(this)
            h.module.watchLog(true)
            h.connect(h.mac)
            h.ring()
            h.advance(25_000)
            h.phone(PhoneState.IDLE)
            assertEquals(listOf(BASE_TS + 25_000), h.offline.ringingEnded.map { it.first })
            assertTrue("no push from the state with READ_CALL_LOG", h.offline.missed.isEmpty())

            h.callLog.add(type = 3, date = BASE_TS + 20, id = 5120)
            h.callLog.add(type = 1, date = BASE_TS + 60_000, id = 5121)
            h.logChanged()
            val (missed, connected) = h.offline.missed.single()
            val logged = missed as MissedCall.Logged
            assertEquals(5120L, logged.new.entry.entryId)
            assertEquals(
                h.mac
                    .states()
                    .last()
                    .callId,
                logged.new.callId,
            )
            assertEquals(setOf("pair-mac"), connected)
        }

    @Test
    fun withoutTheCallLogAMissedCallIsInferredFromTheState() =
        runTest {
            val h = CallHarness(this)
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            h.ring(number = null)
            h.phone(PhoneState.IDLE)
            val inferred =
                h.offline.missed
                    .single()
                    .first as MissedCall.Inferred
            assertEquals(CallPhase.IDLE, inferred.state.state)
            assertEquals(CallEndReason.MISSED, inferred.state.endReason)
            assertEquals(CallControls.NONE, inferred.state.controls)
            assertEquals(
                h.offline.incoming
                    .single()
                    .state.callId,
                inferred.state.callId,
            )
        }

    @Test
    fun declinedAndAnsweredCallsAreNotMissed() =
        runTest {
            val h = CallHarness(this)
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            h.connect(h.mac)
            h.ring(number = null)
            h.action(
                h.mac,
                h.mac
                    .states()
                    .last()
                    .callId,
                "reject",
            )
            h.phone(PhoneState.IDLE)
            h.advance(5_000)
            h.ring(number = null)
            h.phone(PhoneState.OFFHOOK)
            h.phone(PhoneState.IDLE)
            assertTrue(h.offline.missed.isEmpty())
        }

    @Test
    fun turningCallsOffEndsTheRinging() =
        runTest {
            val h = CallHarness(this)
            h.phone(PhoneState.RINGING)
            h.module.onListening()
            h.run()
            assertEquals(1, h.offline.ringingEnded.size)
            h.advance(1_000)
            assertTrue("a pending push is dropped", h.offline.incoming.isEmpty())
        }

    private companion object {
        const val UNAVAILABLE = HfpControl.UNAVAILABLE
    }
}
