package app.handlive.android.core.protocol.call

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `call_event` payloads round-trip the JSON examples of 06-call-control (CALL-01…04) byte for byte. */
class CallMessagesTest {
    @Test
    fun ringingAndMissedStatesMatchTheSpecExamples() {
        val ringing = roundTrip(RINGING, CallStateData.serializer())
        assertEquals(CallPhase.RINGING, ringing.state)
        assertEquals(CallControls(true, true, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE), ringing.controls)
        val missed = roundTrip(MISSED, CallStateData.serializer())
        assertEquals(CallEndReason.MISSED, missed.endReason)
        assertEquals(CallControls.NONE, missed.controls)
    }

    @Test
    fun anOngoingCallWithHfpMatchesTheSpecExample() {
        val offhook = roundTrip(OFFHOOK_HFP, CallStateData.serializer())
        assertEquals(1727150405321, offhook.answeredAt)
        assertEquals(CallAudio.MAC, offhook.audioOn)
    }

    @Test
    fun actionsMatchTheSpecExamples() {
        val answer = roundTrip(ANSWER, CallActionRequest.serializer())
        assertEquals(CallAudio.PHONE, answer.audio)
        assertNull(roundTrip(REJECT, CallActionRequest.serializer()).audio)
        roundTrip(END, CallActionRequest.serializer())
        roundTrip(HOLD, CallActionRequest.serializer())
    }

    @Test
    fun actionAcksMatchTheSpecExamples() {
        assertEquals(
            """{"re":"0192f3f1-0b2c-7d3e-8f4a-5b6c7d8e9f01","ok":true,"data":{}}""",
            ack(Ack.success("0192f3f1-0b2c-7d3e-8f4a-5b6c7d8e9f01")),
        )
        val refused =
            Ack.failure(
                "0192f3f1-2c3d-7e4f-9a5b-6c7d8e9f0a12",
                ErrorCode.CALL_ACTION_NOT_ALLOWED,
                "Call is no longer ringing",
                buildJsonObject {
                    put("state", CallPhase.OFFHOOK)
                    put("reason", CallRefusal.STATE)
                },
            )
        assertEquals(
            """{"re":"0192f3f1-2c3d-7e4f-9a5b-6c7d8e9f0a12","ok":false,"error":{"code":"CALL_ACTION_NOT_ALLOWED",""" +
                """"message":"Call is no longer ringing","details":{"state":"offhook","reason":"state"}}}""",
            ack(refused),
        )
        val hfp =
            Ack.failure(
                "0192f3f2-3c4d-7e5f-8a6b-7c8d9e0f1a2b",
                ErrorCode.CALL_HFP_REQUIRED,
                "Hold requires Bluetooth HFP",
                buildJsonObject { put("action", CallAction.HOLD) },
            )
        assertEquals(
            """{"re":"0192f3f2-3c4d-7e5f-8a6b-7c8d9e0f1a2b","ok":false,"error":{"code":"CALL_HFP_REQUIRED",""" +
                """"message":"Hold requires Bluetooth HFP","details":{"action":"hold"}}}""",
            ack(hfp),
        )
    }

    @Test
    fun logSyncRequestsAndPagesMatchTheSpecExamples() {
        assertNull(roundTrip("""{"op":"log_sync","data":{"limit":200}}""", CallLogSyncRequest.serializer()).cursor)
        val next =
            roundTrip(
                """{"op":"log_sync","data":{"cursor":"eyJ2IjoxLCJpZCI6NTEyMH0","limit":200}}""",
                CallLogSyncRequest.serializer(),
            )
        assertEquals("eyJ2IjoxLCJpZCI6NTEyMH0", next.cursor)
        val first = exact(FIRST_PAGE, CallLogSyncResponse.serializer())
        assertNull(first.entries.first().displayName)
        exact(NEXT_PAGE, CallLogSyncResponse.serializer())
    }

    @Test
    fun logNewMatchesTheSpecExample() {
        val new = roundTrip(LOG_NEW, CallLogNewData.serializer())
        assertEquals(CallLogType.MISSED, new.entry.type)
        val unmatched = new.copy(callId = null)
        assertEquals(
            """{"entry":{"entry_id":5120,"number":"+84900000123","display_name":"Nguyễn Văn A","type":"missed",""" +
                """"ts":1727150400123,"duration_s":0,"sub_id":1},"call_id":null}""",
            ProtocolJson.encodeToString(CallLogNewData.serializer(), unmatched),
        )
    }

    @Test
    fun anActionWithoutItsCallIdIsABadRequest() {
        val error =
            runCatching {
                PlaintextCodec.decodeOp("""{"op":"action","data":{"action":"end"}}""".toByteArray(), ACTION)
            }.exceptionOrNull() as ProtocolException
        assertEquals(ErrorCode.BAD_REQUEST, error.code)
    }

    @Test
    fun theActionSetsFollowTheCatalog() {
        assertEquals(setOf("answer", "reject", "end", "hold", "unhold", "dtmf", "mute"), CallAction.ALL)
        assertEquals(setOf("hold", "unhold", "dtmf", "mute"), CallAction.HFP_ONLY)
    }

    /** Decodes [json] (a payload with `op`), encodes it again and expects the same bytes. */
    private fun <T> roundTrip(
        json: String,
        serializer: KSerializer<T>,
    ): T {
        val decoded = PlaintextCodec.decodeOp(json.toByteArray(), serializer)
        assertEquals(json, PlaintextCodec.encodeOp(decoded.op, serializer, decoded.data).toString(Charsets.UTF_8))
        return decoded.data
    }

    /** Decodes [json] (a bare `data` object), encodes it again and expects the same bytes. */
    private fun <T> exact(
        json: String,
        serializer: KSerializer<T>,
    ): T {
        val decoded = ProtocolJson.decodeFromString(serializer, json)
        assertEquals(json, ProtocolJson.encodeToString(serializer, decoded))
        return decoded
    }

    private fun ack(ack: Ack): String = PlaintextCodec.encodeAck(ack).toString(Charsets.UTF_8)

    private companion object {
        const val UNAVAILABLE = HfpControl.UNAVAILABLE
        val ACTION = CallActionRequest.serializer()

        const val RINGING =
            """{"op":"state","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","direction":"incoming",""" +
                """"state":"ringing","waiting":false,"number":"+84900000123","display_name":"Nguyễn Văn A",""" +
                """"presentation":"allowed","sub_id":1,"sim_label":"SIM 1","waiting_number":null,""" +
                """"waiting_display_name":null,"started_at":1727150400123,"answered_at":null,"ended_at":null,""" +
                """"end_reason":null,"controls":{"answer":true,"reject":true,"end":false,"hold":"unavailable",""" +
                """"dtmf":"unavailable","mute":"unavailable"},"hfp_connected":false,"audio_on":"phone"}}"""

        const val MISSED =
            """{"op":"state","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","direction":"incoming",""" +
                """"state":"idle","waiting":false,"number":"+84900000123","display_name":"Nguyễn Văn A",""" +
                """"presentation":"allowed","sub_id":1,"sim_label":"SIM 1","waiting_number":null,""" +
                """"waiting_display_name":null,"started_at":1727150400123,"answered_at":null,""" +
                """"ended_at":1727150425456,"end_reason":"missed","controls":{"answer":false,"reject":false,""" +
                """"end":false,"hold":"unavailable","dtmf":"unavailable","mute":"unavailable"},""" +
                """"hfp_connected":false,"audio_on":"phone"}}"""

        const val OFFHOOK_HFP =
            """{"op":"state","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","direction":"incoming",""" +
                """"state":"offhook","waiting":false,"number":"+84900000123","display_name":"Nguyễn Văn A",""" +
                """"presentation":"allowed","sub_id":1,"sim_label":"SIM 1","waiting_number":null,""" +
                """"waiting_display_name":null,"started_at":1727150400123,"answered_at":1727150405321,""" +
                """"ended_at":null,"end_reason":null,"controls":{"answer":false,"reject":false,"end":true,""" +
                """"hold":"hfp","dtmf":"hfp","mute":"hfp"},"hfp_connected":true,"audio_on":"mac"}}"""

        const val ANSWER =
            """{"op":"action","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","action":"answer",""" +
                """"audio":"phone"}}"""
        const val REJECT =
            """{"op":"action","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","action":"reject"}}"""
        const val END = """{"op":"action","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","action":"end"}}"""
        const val HOLD = """{"op":"action","data":{"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90","action":"hold"}}"""

        const val FIRST_PAGE =
            """{"entries":[{"entry_id":5119,"number":"+84900000456","display_name":null,"type":"outgoing",""" +
                """"ts":1727140000000,"duration_s":62,"sub_id":1},{"entry_id":5120,"number":"+84900000123",""" +
                """"display_name":"Nguyễn Văn A","type":"missed","ts":1727150400123,"duration_s":0,"sub_id":1}],""" +
                """"cursor":"eyJ2IjoxLCJpZCI6NTEyMH0","has_more":false,"reset":false}"""

        const val NEXT_PAGE =
            """{"entries":[{"entry_id":5123,"number":"+84900000123","display_name":"Nguyễn Văn A",""" +
                """"type":"incoming","ts":1727160000000,"duration_s":125,"sub_id":1}],""" +
                """"cursor":"eyJ2IjoxLCJpZCI6NTEyM30","has_more":false,"reset":false}"""

        const val LOG_NEW =
            """{"op":"log_new","data":{"entry":{"entry_id":5120,"number":"+84900000123",""" +
                """"display_name":"Nguyễn Văn A","type":"missed","ts":1727150400123,"duration_s":0,"sub_id":1},""" +
                """"call_id":"0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90"}}"""
    }
}
