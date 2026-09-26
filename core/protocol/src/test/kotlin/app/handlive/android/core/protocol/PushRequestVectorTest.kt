package app.handlive.android.core.protocol

import app.handlive.android.core.protocol.relay.PushRequest
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
        assertEquals(3, requests.size)
    }

    @Test
    fun theSmsPushCollapsesPerMessageAndLivesADay() {
        val sms =
            SharedTestVectors
                .vectors("push-envelope.json")
                .first { it.str("name").contains("Vietnamese") }
        val request = ProtocolJson.decodeFromString(PushRequest.serializer(), sms.str("push_request"))
        // SMS-02 API 2: one notification per message — the collapse key is built from its message_key ("sms:12847";
        // vectors written before that rule was pinned prefix it once more).
        assertTrue(request.collapseKey!!.endsWith("sms:12847"))
        assertEquals(86_400, request.ttlS)
        assertEquals("alert", request.kind)
        assertEquals("sms_new", request.reason)
    }
}
