package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * push-envelope.json vectors with a `truncation` object (CONN-04 step 5b, SMS-02 API 2 logic 3): from the texts
 * before the cut, the phone's push carries exactly the vector's cut texts, with the same `env_b64` length.
 */
class PushTruncationVectorTest {
    private val vectors = SharedTestVectors.vectors("push-envelope.json")

    @Test
    fun theSmsTextIsCutLikeTheVectors() {
        val cuts = vectors.filter { it.containsKey("truncation") }
        cuts.forEach { v ->
            val name = v.str("name")
            val truncation = v.getValue("truncation").jsonObject
            val expected = PlaintextCodec.decodeOp(v.str("plaintext").toByteArray(), SmsNewData.serializer()).data
            val original = expected.restored(truncation)
            val prk = vectors.first { it.str("kind") == "key" && it.str("pair_id") == v.str("pair_id") }.hex("prk")

            val request =
                PushEnvelopeBuilder({
                    v.long("ts")
                }).smsNew(v.str("pair_id"), v.str("recipient_device_id"), prk, original)!!

            val envelope = EnvelopeCodec.decode(Base64Codecs.decodeB64(request.envB64!!).toString(Charsets.UTF_8))
            val opened =
                PlaintextCodec.decodeOp(
                    EnvelopeCipher.open(v.hex("k_push"), envelope),
                    SmsNewData.serializer(),
                )
            assertEquals(name, expected, opened.data)
            assertEquals(name, truncation.number("env_b64_length"), request.envB64!!.length)
            val vectorRequest = ProtocolJson.decodeFromString(PushRequest.serializer(), v.str("push_request"))
            assertEquals(name, vectorRequest.collapseKey, request.collapseKey)
            assertEquals(name, vectorRequest.ttlS, request.ttlS)
        }
        assertEquals(5, cuts.size)
    }

    private fun SmsNewData.restored(truncation: JsonObject): SmsNewData =
        copy(
            message = message.copy(body = truncation.getValue("original_body").jsonPrimitive.content),
            thread = thread.copy(snippet = truncation.getValue("original_snippet").jsonPrimitive.content),
        )

    private fun JsonObject.number(key: String): Int = getValue(key).jsonPrimitive.int
}
