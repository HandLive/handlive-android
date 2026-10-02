package app.handlive.android.core.protocol

import app.handlive.android.core.protocol.call.AppCallData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `call_event/app_call` (CALL-05 API 1): the spec's three example messages decode and encode back unchanged. */
class AppCallMessagesTest {
    private val ringing = example("ringing", "true,\"decline\":true,\"end\":false", "null", "null", "null")
    private val ongoing = example("ongoing", "false,\"decline\":false,\"end\":true", "1727150405321", "null", "null")
    private val ended =
        example("ended", "false,\"decline\":false,\"end\":false", "1727150405321", "1727150530456", "\"ended\"")

    /** The example messages of CALL-05 API 1, one per state. */
    private fun example(
        state: String,
        answer: String,
        answeredAt: String,
        endedAt: String,
        endReason: String,
    ) = """
        {"op":"app_call","data":{"call_id":"0192f3f6-2c3d-7e4f-8a5b-6c7d8e9f0a1b",
        "app":{"package":"org.telegram.messenger","label":"Telegram"},"caller":"Nguyễn Văn A","state":"$state",
        "controls":{"answer":$answer},"answer_mode":"direct","audio":"phone","started_at":1727150400123,
        "answered_at":$answeredAt,"ended_at":$endedAt,"end_reason":$endReason}}
        """.trimIndent()

    @Test
    fun theSpecExamplesRoundTripWithEveryNullableFieldWritten() {
        for (example in listOf(ringing, ongoing, ended)) {
            val decoded = PlaintextCodec.decodeOp(example.toByteArray(), AppCallData.serializer())
            assertEquals(CallOp.APP_CALL, decoded.op)
            val encoded = PlaintextCodec.encodeOp(CallOp.APP_CALL, AppCallData.serializer(), decoded.data)
            assertEquals(Json.parseToJsonElement(example), Json.parseToJsonElement(String(encoded)))
        }
    }

    @Test
    fun aMissingCallerIsWrittenAsNullNotLeftOut() {
        val data = PlaintextCodec.decodeOp(ringing.toByteArray(), AppCallData.serializer()).data.copy(caller = null)

        val json = String(PlaintextCodec.encodeOp(CallOp.APP_CALL, AppCallData.serializer(), data))

        assertTrue(json, json.contains("\"caller\":null"))
        assertTrue(json, json.contains("\"answered_at\":null"))
    }
}
