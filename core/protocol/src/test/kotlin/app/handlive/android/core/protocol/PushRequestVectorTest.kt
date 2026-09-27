package app.handlive.android.core.protocol

import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** push-envelope.json `push_request`: the `POST /v1/push` body as this phone writes it, byte for byte (CONN-04). */
class PushRequestVectorTest {
    @Test
    fun pushRequestsRoundTripByteForByte() {
        val requests =
            SharedTestVectors
                .vectors("push-envelope.json")
                .filter { it.containsKey("push_request") }
                .map { it.str("push_request") }
        requests.forEach { text ->
            val request = ProtocolJson.decodeFromString(PushRequest.serializer(), text)
            assertEquals(text, ProtocolJson.encodeToString(PushRequest.serializer(), request))
        }
        assertEquals(12, requests.size)
    }

    @Test
    fun callPushesUseTheirReasonsCollapseKeysAndLifetimes() {
        val calls =
            SharedTestVectors
                .vectors("push-envelope.json")
                .filter { it.containsKey("push_request") && it.str("type") == "call_event" }
                .map { ProtocolJson.decodeFromString(PushRequest.serializer(), it.str("push_request")) }
        // CALL-01 API 4: `call_incoming` lives 30 s; CALL-04 API 5: `call_missed` a day, keyed by call or entry.
        val incoming = calls.filter { it.reason == RelayValues.REASON_CALL_INCOMING }
        val missed = calls.filter { it.reason == RelayValues.REASON_CALL_MISSED }
        assertEquals(2, incoming.size)
        assertEquals(3, missed.size)
        incoming.forEach {
            assertEquals(30, it.ttlS)
            assertTrue(it.collapseKey!!.startsWith("call:"))
        }
        missed.forEach { assertEquals(86_400, it.ttlS) }
        assertEquals(listOf("call:", "calllog:", "call:"), missed.map { it.collapseKey!!.substringBefore(':') + ":" })
        assertTrue(calls.all { it.kind == RelayValues.KIND_ALERT })
    }

    @Test
    fun theSmsPushCollapsesPerMessageAndLivesADay() {
        val sms =
            SharedTestVectors
                .vectors("push-envelope.json")
                .first { it.str("name").contains("Vietnamese") }
        val request = ProtocolJson.decodeFromString(PushRequest.serializer(), sms.str("push_request"))
        // SMS-02 API 2: one notification per message — the collapse key is the message_key itself.
        assertEquals("sms:12847", request.collapseKey)
        assertEquals(86_400, request.ttlS)
        assertEquals("alert", request.kind)
        assertEquals("sms_new", request.reason)
    }
}
