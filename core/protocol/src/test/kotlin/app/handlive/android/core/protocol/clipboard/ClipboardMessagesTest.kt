package app.handlive.android.core.protocol.clipboard

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.envelope.OpPayload
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The `clipboard` payloads round-trip the examples of 04-clipboard (CLIP-01 API 5–6, CLIP-03 API 3 and 5). */
class ClipboardMessagesTest {
    @Test
    fun inlineTextPushMatchesTheSpecExample() {
        val json =
            """{"op":"push","data":{"clip_id":"0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e","kind":"text",""" +
                """"mime":"text/plain","text":"Order number: HL-240917-0042","sensitive":false,""" +
                """"origin_ts":1727150100123,"source":"auto",""" +
                """"origin_device_id":"8c7d6e5f-4a3b-8c2d-9e1f-0a1b2c3d4e5f"}}"""
        val push = roundTrip(json, ClipboardPushData.serializer())
        assertEquals("Order number: HL-240917-0042", push.text)
        assertNull(push.transfer)
    }

    @Test
    fun chunkedImagePushMatchesTheSpecExample() {
        val json =
            """{"op":"push","data":{"clip_id":"0192f3f1-2c3d-7e4f-8a5b-6c7d8e9f0a1b","kind":"image",""" +
                """"mime":"image/png","transfer":{"transfer_id":"0192f3f1-2c3e-7a10-9b20-c30d40e50f60",""" +
                """"size":5242880,"sha256":"n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg","chunk_size":65536,""" +
                """"chunk_count":80},"width":2880,"height":1800,"sensitive":false,"origin_ts":1727150200456,""" +
                """"source":"mac","origin_device_id":"5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718"}}"""
        val push = roundTrip(json, ClipboardPushData.serializer())
        assertEquals(80, push.transfer?.chunkCount)
        assertNull(push.text)
    }

    @Test
    fun cancelAndConflictMatchTheSpecExamples() {
        roundTrip(
            """{"op":"cancel","data":{"transfer_id":"0192f3f1-2c3e-7a10-9b20-c30d40e50f60","reason":"superseded"}}""",
            ClipboardCancelData.serializer(),
        )
        roundTrip(
            """{"op":"conflict","data":{"clip_id":"0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e",""" +
                """"origin_device_id":"8c7d6e5f-4a3b-8c2d-9e1f-0a1b2c3d4e5f",""" +
                """"device_id":"5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718","device_name":"MacBook của Lan"}}""",
            ClipboardConflictData.serializer(),
        )
    }

    @Test
    fun ackDataCarriesStatusReasonAndTransfer() {
        val ignored =
            ClipboardAckData(
                clipId = "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e",
                status = ClipboardValues.STATUS_IGNORED,
                reason = ClipboardValues.REASON_CONFLICT,
            )
        assertEquals(
            """{"clip_id":"0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e","status":"ignored","reason":"conflict"}""",
            ProtocolJson.encodeToString(ClipboardAckData.serializer(), ignored),
        )
        val rejected =
            """{"clip_id":"0192f3f1-2c3d-7e4f-8a5b-6c7d8e9f0a1b","status":"rejected",""" +
                """"transfer_id":"0192f3f1-2c3e-7a10-9b20-c30d40e50f60"}"""
        assertEquals(
            "0192f3f1-2c3e-7a10-9b20-c30d40e50f60",
            ProtocolJson.decodeFromString(ClipboardAckData.serializer(), rejected).transferId,
        )
    }

    private fun <T> roundTrip(
        json: String,
        serializer: KSerializer<T>,
    ): T {
        val decoded = PlaintextCodec.decodeOp(json.toByteArray(), serializer)
        val encoded = PlaintextCodec.encodeOp(decoded.op, serializer, decoded.data).toString(Charsets.UTF_8)
        assertEquals(Json.parseToJsonElement(json), Json.parseToJsonElement(encoded))
        return OpPayload(decoded.op, decoded.data).data
    }
}
