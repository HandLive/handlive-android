package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallAudio
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CALL-02 API 1–3 and CALL-03 API 1: `call_event/action`, each error code where the error table puts it. */
class CallActionTest {
    @Test
    fun answerFromAMacAcceptsTheRingingCall() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId

            val ack = h.action(h.mac, callId, "answer", CallAudio.MAC)
            assertTrue(ack.ok)
            assertEquals(JsonObject(emptyMap()), ack.data)
            assertEquals(listOf("accept"), h.telecom.calls)
            assertEquals(CallAudio.MAC, h.tracker.current?.requestedAudio)
            h.phone(PhoneState.OFFHOOK)
            assertEquals(
                CallPhase.OFFHOOK,
                h.mac
                    .states()
                    .last()
                    .state,
            )
        }

    @Test
    fun rejectEndsTheRingingCallAsRejected() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.iphone)
            h.ring()
            val callId =
                h.iphone
                    .states()
                    .single()
                    .callId

            assertTrue(h.action(h.iphone, callId, "reject").ok)
            assertEquals(listOf("end"), h.telecom.calls)
            h.advance(150)
            h.phone(PhoneState.IDLE)
            assertEquals(
                CallEndReason.REJECTED,
                h.iphone
                    .states()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun endEndsTheOngoingCall() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            h.phone(PhoneState.OFFHOOK)
            h.telecom.phone = PhoneState.OFFHOOK
            h.advance(5_000)
            val callId =
                h.mac
                    .states()
                    .last()
                    .callId

            assertTrue(h.action(h.mac, callId, "end").ok)
            h.phone(PhoneState.IDLE)
            assertEquals(
                CallEndReason.ENDED,
                h.mac
                    .states()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun theFeatureOffComesFirst() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.access.enabled = false
            h.access.granted.clear()
            assertError(ErrorCode.FEATURE_DISABLED, h.action(h.mac, "", "hold"))
            val id = h.request(h.mac, "action", "{}")
            assertError(ErrorCode.FEATURE_DISABLED, h.mac.acks().last { it.re == id })
        }

    @Test
    fun aSessionWithoutCallsIsRefusedWhateverOtherSessionsAllow() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.iphone.effective.value = emptySet()
            // Checked before the permissions: nothing is suggested to the phone.
            h.access.granted.clear()
            assertError(ErrorCode.FEATURE_DISABLED, h.action(h.iphone, "", "end"))
            val sync = h.request(h.iphone, "log_sync", """{"limit":200}""")
            assertError(ErrorCode.FEATURE_DISABLED, h.iphone.acks().last { it.re == sync })
            assertTrue(h.permissionsAsked.isEmpty())
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun malformedActionsAreBadRequests() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            for (data in listOf(
                """{"action":"answer"}""",
                """{"call_id":"$callId"}""",
                """{"call_id":"$callId","action":"transfer"}""",
                """{"call_id":"$callId","action":"answer","audio":"speaker"}""",
            )) {
                val id = h.request(h.mac, "action", data)
                assertError(ErrorCode.BAD_REQUEST, h.mac.acks().last { it.re == id })
            }
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun holdDtmfAndMuteNeedHfpEvenWithoutACall() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.access.granted.clear()
            for (action in listOf("hold", "unhold", "dtmf", "mute")) {
                val ack = h.action(h.mac, "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90", action)
                assertError(ErrorCode.CALL_HFP_REQUIRED, ack)
                assertEquals(JsonPrimitive(action), ack.error?.details?.get("action"))
            }
        }

    @Test
    fun aCallIdOtherThanTheCurrentOneIsNotFound() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90", "answer"))
            h.ring()
            val first =
                h.mac
                    .states()
                    .single()
                    .callId
            h.phone(PhoneState.IDLE)
            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, first, "reject"))
            h.ring()
            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, first, "reject"))
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun endCallFalseOnAnIdlePhoneIsNotFoundAndOtherwiseRefusedBySystem() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.telecom.endCallResult = false
            h.telecom.phone = PhoneState.IDLE
            assertError(ErrorCode.CALL_NOT_FOUND, h.action(h.mac, callId, "reject"))

            h.phone(PhoneState.OFFHOOK)
            h.advance(3_000)
            h.telecom.phone = null
            val refused = h.action(h.mac, callId, "end")
            assertNotAllowed(refused, CallPhase.OFFHOOK, "system")
            h.phone(PhoneState.IDLE)
            assertEquals(
                "a refused decline leaves no flag",
                CallEndReason.ENDED,
                h.mac
                    .states()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun theAnswerPermissionIsCheckedBeforeTheState() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.access.granted -= AndroidPermissions.ANSWER_PHONE_CALLS

            val ack = h.action(h.mac, callId, "end")
            assertError(ErrorCode.PERMISSION_MISSING, ack)
            assertEquals(JsonPrimitive("android.permission.ANSWER_PHONE_CALLS"), ack.error?.details?.get("permission"))
            assertEquals(listOf("pair-mac" to "android.permission.ANSWER_PHONE_CALLS"), h.permissionsAsked)
        }

    @Test
    fun aSecurityExceptionFromTelecomIsAMissingPermission() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.telecom.securityException = true

            assertError(ErrorCode.PERMISSION_MISSING, h.action(h.mac, callId, "reject"))
            assertEquals("the capability goes out again", 1, h.permissionLost)
            h.phone(PhoneState.IDLE)
            assertEquals(
                CallEndReason.MISSED,
                h.mac
                    .states()
                    .last()
                    .endReason,
            )
        }

    @Test
    fun theStateMustAllowTheAction() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            assertNotAllowed(h.action(h.mac, callId, "end"), CallPhase.RINGING, "state")
            assertNotAllowed(h.action(h.iphone, callId, "answer"), CallPhase.RINGING, "platform")

            h.phone(PhoneState.OFFHOOK)
            assertNotAllowed(h.action(h.mac, callId, "answer"), CallPhase.OFFHOOK, "state")
            assertNotAllowed(h.action(h.mac, callId, "reject"), CallPhase.OFFHOOK, "state")
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun aWaitingCallRefusesEveryAction() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            h.phone(PhoneState.OFFHOOK)
            h.phone(PhoneState.RINGING)
            val callId =
                h.mac
                    .states()
                    .last()
                    .callId
            for (action in listOf("answer", "reject", "end")) {
                assertNotAllowed(h.action(h.mac, callId, action), CallPhase.RINGING, "waiting")
            }
            assertTrue(h.telecom.calls.isEmpty())
        }

    @Test
    fun oneCommandPerCallWithinThreeSeconds() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            h.telecom.endCallResult = false
            h.telecom.phone = PhoneState.RINGING
            assertNotAllowed(h.action(h.mac, callId, "reject"), CallPhase.RINGING, "system")
            assertNotAllowed(h.action(h.iphone, callId, "reject"), CallPhase.RINGING, "state")
            h.advance(2_999)
            assertNotAllowed(h.action(h.mac, callId, "answer"), CallPhase.RINGING, "state")
            h.advance(1)
            assertTrue(h.action(h.mac, callId, "answer").ok)
            assertEquals(listOf("end", "accept"), h.telecom.calls)
        }

    @Test
    fun aRepeatedIdGetsTheSameAckAndTheCommandRunsOnce() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            val data = """{"call_id":"$callId","action":"answer"}"""
            val id = h.request(h.mac, "action", data)
            h.request(h.mac, "action", data, id = id)

            val acks = h.mac.acks().filter { it.re == id }
            assertEquals(2, acks.size)
            assertEquals(acks[0], acks[1])
            assertEquals(listOf("accept"), h.telecom.calls)
        }

    private fun assertError(
        code: ErrorCode,
        ack: Ack,
    ) {
        assertEquals(false, ack.ok)
        assertEquals(code.name, ack.error?.code)
    }

    private fun assertNotAllowed(
        ack: Ack,
        state: String,
        reason: String,
    ) {
        assertError(ErrorCode.CALL_ACTION_NOT_ALLOWED, ack)
        assertEquals(JsonPrimitive(state), ack.error?.details?.get("state"))
        assertEquals(JsonPrimitive(reason), ack.error?.details?.get("reason"))
    }
}
