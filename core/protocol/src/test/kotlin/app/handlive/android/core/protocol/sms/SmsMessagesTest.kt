package app.handlive.android.core.protocol.sms

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `sms` payloads round-trip the JSON examples of 05-sms (SMS-01…05). */
class SmsMessagesTest {
    @Test
    fun syncRequestsMatchTheSpecExamples() {
        val first = roundTrip("""{"op":"sync","data":{"thread_limit":200,"per_thread_limit":50}}""", SYNC)
        assertNull(first.cursor)
        val catchUp =
            roundTrip(
                """{"op":"sync","data":{"cursor":"eyJ2IjoxLCJpZCI6MTI4NDYsInQiOjE3MjcxNTAwMDAxMjN9",""" +
                    """"thread_limit":200,"per_thread_limit":50}}""",
                SYNC,
            )
        assertEquals("eyJ2IjoxLCJpZCI6MTI4NDYsInQiOjE3MjcxNTAwMDAxMjN9", catchUp.cursor)
    }

    @Test
    fun syncResponseOfTheLastPageCarriesUnread() {
        val json =
            """{"threads":[{"thread_id":42,"addresses":["+84900000123"],"display_name":"Nguyễn Văn A",""" +
                """"snippet":"Nhớ mang theo tài liệu","last_ts":1727150060456,"unread_count":2}],""" +
                """"messages":[{"message_key":"sms:12847","thread_id":42,"address":"+84900000123",""" +
                """"body":"Nhớ mang theo tài liệu","box":"inbox","ts":1727150060456,"ts_sent":1727150059000,""" +
                """"read":false,"sub_id":1}],"cursor":"eyJ2IjoxLCJpZCI6MTI4NDcsInQiOjE3MjcxNTAwNjA0NTZ9",""" +
                """"has_more":false,"unread":[{"thread_id":42,"unread_count":2,"read_up_to_ts":1727150000122}]}"""
        val response = ProtocolJson.decodeFromString(SmsSyncResponse.serializer(), json)
        assertEquals(1727150000122, response.unread?.single()?.readUpToTs)
        assertJsonEquals(json, ProtocolJson.encodeToString(SmsSyncResponse.serializer(), response))
    }

    @Test
    fun aMessageWithoutDateSentOrSimWritesExplicitNulls() {
        val message =
            SmsMessageData("sms:12790", 42, "+84900000123", "Ok anh", SmsBox.SENT, 1727140000000, null, true, null)
        assertEquals(
            """{"message_key":"sms:12790","thread_id":42,"address":"+84900000123","body":"Ok anh","box":"sent",""" +
                """"ts":1727140000000,"ts_sent":null,"read":true,"sub_id":null}""",
            ProtocolJson.encodeToString(SmsMessageData.serializer(), message),
        )
        val thread = SmsThreadData(42, listOf("VIETTEL"), null, "", 1, 0)
        assertTrue(ProtocolJson.encodeToString(SmsThreadData.serializer(), thread).contains("\"display_name\":null"))
    }

    @Test
    fun newWithLocalIdMatchesTheSpecExample() {
        val new =
            roundTrip(
                """{"op":"new","data":{"message":{"message_key":"sms:12848","thread_id":42,""" +
                    """"address":"+84900000123","body":"Ok, 3h mình có mặt","box":"sent","ts":1727150125000,""" +
                    """"ts_sent":null,"read":true,"sub_id":1,"local_id":"0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f"},""" +
                    """"thread":{"thread_id":42,"addresses":["+84900000123"],"display_name":"Nguyễn Văn A",""" +
                    """"snippet":"Ok, 3h mình có mặt","last_ts":1727150125000,"unread_count":2}}}""",
                SmsNewData.serializer(),
            )
        assertEquals("0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f", new.message.localId)
    }

    @Test
    fun historySendStatusAndReadChangedMatchTheSpecExamples() {
        roundTrip("""{"op":"history","data":{"thread_id":42,"before_ts":1727140000000,"limit":50}}""", HISTORY)
        val send =
            roundTrip(
                """{"op":"send","data":{"local_id":"0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f","thread_id":42,""" +
                    """"addresses":["+84900000123"],"body":"Ok, 3h mình có mặt","sub_id":1}}""",
                SmsSendRequest.serializer(),
            )
        assertEquals(1, send.subId)
        roundTrip(
            """{"op":"status","data":{"local_id":"0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f","message_key":"sms:12848",""" +
                """"status":"sent"}}""",
            SmsStatusData.serializer(),
        )
        roundTrip(
            """{"op":"status","data":{"local_id":"0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f","status":"failed",""" +
                """"error_code":"SMS_NO_SERVICE"}}""",
            SmsStatusData.serializer(),
        )
        roundTrip(
            """{"op":"read_changed","data":{"thread_id":57,"unread_count":1,"read_up_to_ts":1727149500122}}""",
            SmsReadChangedData.serializer(),
        )
        assertJsonEquals(
            """{"accepted":true,"parts":1}""",
            ProtocolJson.encodeToString(SmsSendAckData.serializer(), SmsSendAckData(true, 1)),
        )
    }

    @Test
    fun aRequestWithoutItsLimitsIsABadRequest() {
        val error =
            runCatching { PlaintextCodec.decodeOp("""{"op":"sync","data":{}}""".toByteArray(), SYNC) }
                .exceptionOrNull() as ProtocolException
        assertEquals(ErrorCode.BAD_REQUEST, error.code)
    }

    private fun <T> roundTrip(
        json: String,
        serializer: KSerializer<T>,
    ): T {
        val decoded = PlaintextCodec.decodeOp(json.toByteArray(), serializer)
        val encoded = PlaintextCodec.encodeOp(decoded.op, serializer, decoded.data).toString(Charsets.UTF_8)
        assertJsonEquals(json, encoded)
        return decoded.data
    }

    private fun assertJsonEquals(
        expected: String,
        actual: String,
    ) = assertEquals(Json.parseToJsonElement(expected), Json.parseToJsonElement(actual))

    private companion object {
        val SYNC = SmsSyncRequest.serializer()
        val HISTORY = SmsHistoryRequest.serializer()
    }
}
