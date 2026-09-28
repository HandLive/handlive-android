package app.handlive.android.feature.call.module

import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.call.testing.FakeClient
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every `call_event` message and `ack` the phone emits passes the strict sender-side schemas of shared/schemas. */
class CallMessageSchemaTest {
    @Test
    fun theEventsOfAWholeDayOfCallsAreValid() =
        runTest {
            val h = CallHarness(this)
            h.module.watchLog(true)
            h.connect(h.mac, h.iphone)
            // A known caller rings on SIM 1, is answered from the Mac, a second call waits, then the call ends.
            h.sim(1, PhoneState.RINGING)
            h.ring()
            h.action(
                h.mac,
                h.mac
                    .states()
                    .last()
                    .callId,
                "answer",
            )
            h.phone(PhoneState.OFFHOOK)
            h.copy(PhoneState.RINGING, "0900000456")
            h.phone(PhoneState.RINGING)
            h.phone(PhoneState.OFFHOOK)
            h.advance(60_000)
            h.phone(PhoneState.IDLE)
            // A withheld caller is missed; the call log then says it was declined on the phone.
            h.advance(1_000)
            h.phone(PhoneState.RINGING)
            h.copy(PhoneState.RINGING, null)
            h.copy(PhoneState.RINGING, null)
            h.advance(9_000)
            h.phone(PhoneState.IDLE)
            h.callLog.add(type = 5, date = BASE_TS + 61_020, number = "") { it.copy(presentation = 2) }
            h.logChanged()
            // A call dialed on the phone, and one declined from the iPhone.
            h.advance(1_000)
            h.phone(PhoneState.OFFHOOK)
            h.advance(20_000)
            h.phone(PhoneState.IDLE)
            h.ring()
            h.action(
                h.iphone,
                h.iphone
                    .states()
                    .last()
                    .callId,
                "reject",
            )
            h.phone(PhoneState.IDLE)
            h.callLog.add(type = 2, date = BASE_TS + 71_000, number = "0900000456") { it.copy(durationS = 20) }
            h.logChanged()

            val ops = assertEventsValid(h.mac) + assertEventsValid(h.iphone)
            assertEquals(setOf(CallOp.STATE, CallOp.LOG_NEW), ops)
            assertTrue(h.mac.states().size >= 10)
        }

    @Test
    fun aCallBuiltMidwayAndItsPushStatesAreValid() =
        runTest {
            val h = CallHarness(this)
            h.phone(PhoneState.OFFHOOK)
            h.connect(h.mac)
            h.phone(PhoneState.IDLE)
            assertEventsValid(h.mac)

            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            h.phone(PhoneState.RINGING)
            h.phone(PhoneState.IDLE)
            val pushed =
                h.offline.incoming.map { it.state } +
                    h.offline.missed.map { (it.first as MissedCall.Inferred).state }
            assertEquals(2, pushed.size)
            pushed.forEach { state ->
                val plaintext = PlaintextCodec.encodeOp(CallOp.STATE, CallStateData.serializer(), state)
                JsonSchemaValidation.assertValid("call_event-state.schema.json", String(plaintext))
            }
        }

    @Test
    fun actionAcksAreValid() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.ring()
            val callId =
                h.mac
                    .states()
                    .single()
                    .callId
            val acks =
                listOf(
                    h.action(h.iphone, callId, "answer"),
                    h.action(h.mac, callId, "hold"),
                    h.action(h.mac, callId, "dtmf"),
                    h.action(h.mac, "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90", "reject"),
                    h.action(h.mac, callId, "end"),
                    h.action(h.mac, callId, "answer"),
                )
            h.access.granted -= AndroidPermissions.ANSWER_PHONE_CALLS
            val missing = h.action(h.mac, callId, "reject")
            h.access.enabled = false
            val disabled = h.action(h.mac, callId, "reject")
            (acks + missing + disabled).forEach { assertAckValid("call_event-action", it) }
        }

    @Test
    fun logSyncAcksAreValid() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(type = 1, date = BASE_TS - 60_000) { it.copy(durationS = 125) }
            h.callLog.add(type = 3, date = BASE_TS - 30_000, number = null)
            h.callLog.add(type = 6, date = BASE_TS - 20_000, number = "0911111111") { it.copy(accountId = null) }
            val acks = mutableListOf<Ack>()
            for (data in listOf("""{"limit":2}""", """{"cursor":"not a cursor","limit":200}""", """{"limit":0}""")) {
                val id = h.request(h.mac, "log_sync", data)
                acks += h.mac.acks().last { it.re == id }
            }
            h.callLog.failing = true
            val failed = h.request(h.mac, "log_sync", """{"limit":200}""")
            acks += h.mac.acks().last { it.re == failed }
            h.callLog.failing = false
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            val missing = h.request(h.mac, "log_sync", """{"limit":200}""")
            acks += h.mac.acks().last { it.re == missing }
            acks.forEach { assertAckValid("call_event-log_sync", it) }
            assertEquals(listOf(true, true, false, false, false), acks.map { it.ok })
        }

    private fun assertEventsValid(client: FakeClient): Set<String> =
        client.sent
            .filter { it.type == MessageType.CALL_EVENT }
            .map { event ->
                val op = PlaintextCodec.decodePayload(event.plaintext).op
                JsonSchemaValidation.assertValid("call_event-$op.schema.json", String(event.plaintext))
                op
            }.toSet()

    private fun assertAckValid(
        schema: String,
        ack: Ack,
    ) {
        val json = String(PlaintextCodec.encodeAck(ack))
        JsonSchemaValidation.assertValid(
            if (ack.ok) "$schema.schema.json#/\$defs/ack" else "$schema.schema.json#/\$defs/ack-failure",
            json,
        )
        JsonSchemaValidation.assertValid("ack.schema.json", json)
    }
}
