package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The telephony routing of `call_event/action` that the routing by `call_id` (telephony or app call) must keep:
 * which error a request gets first, whichever other sessions or other call kinds exist.
 */
class CallActionRoutingTest {
    private val unknownId = "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90"

    @Test
    fun aSessionWithoutCallsGetsFeatureDisabledBeforeAnyOtherCheck() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.mac.effective.value = emptySet()
            h.ring()

            for (action in listOf("answer", "reject", "end", "hold", "bogus")) {
                assertCode(ErrorCode.FEATURE_DISABLED, h.action(h.mac, unknownId, action))
            }
            val malformed = h.request(h.mac, "action", "{}")
            assertCode(ErrorCode.FEATURE_DISABLED, h.mac.acks().last { it.re == malformed })
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun anUnknownCallIdOnASessionWithCallsKeepsTheTelephonyOrder() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()

            assertCode(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "answer"))
            assertCode(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "reject"))
            assertCode(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, unknownId, "end"))
            assertCode(ErrorCode.CALL_HFP_REQUIRED, h.action(h.mac, unknownId, "mute"))
            val malformed = h.request(h.mac, "action", """{"call_id":"$unknownId","action":"answer","audio":"x"}""")
            assertCode(ErrorCode.BAD_REQUEST, h.mac.acks().last { it.re == malformed })
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun theCurrentTelephonyCallIsStillControlledByItsCallIdOnlyOnItsOwnSessions() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.iphone.effective.value = emptySet()

            assertCode(ErrorCode.FEATURE_DISABLED, h.action(h.iphone, callId, "reject"))
            assertTrue(h.telecom.calls.isEmpty())
            assertTrue(h.action(h.mac, callId, "reject").ok)
            assertEquals(listOf("end"), h.telecom.calls)
            h.advance(150)
            h.phone(PhoneState.IDLE)
            assertEquals(
                CallPhase.IDLE,
                h.mac
                    .states()
                    .last()
                    .state,
            )
        }

    @Test
    fun theFeatureOffOnThePhoneRefusesEvenACurrentCallId() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.access.enabled = false

            assertCode(ErrorCode.FEATURE_DISABLED, h.action(h.mac, callId, "answer"))
            assertTrue(h.telecom.calls.isEmpty())
        }

    private fun assertCode(
        code: ErrorCode,
        ack: Ack,
    ) {
        assertEquals(false, ack.ok)
        assertEquals(code.name, ack.error?.code)
    }
}
