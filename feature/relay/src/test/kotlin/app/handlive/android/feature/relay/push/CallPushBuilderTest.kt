package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.long
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.feature.call.module.MissedCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The call pushes (CALL-01 API 4, CALL-04 API 5) rebuilt byte for byte from the `plaintext`, `id`, `ts` and `nonce`
 * of the call vectors of push-envelope.json, and a name that would not fit in the APNs payload.
 */
class CallPushBuilderTest {
    private val vectors = SharedTestVectors.vectors("push-envelope.json")
    private val prks = vectors.filter { it.str("kind") == "key" }.associate { it.str("pair_id") to it.hex("prk") }
    private val calls = vectors.filter { it.str("kind") == "envelope" && it.str("type") == "call_event" }

    @Test
    fun everyCallVectorIsRebuiltByteForByte() {
        assertEquals(5, calls.size)
        calls.forEach { vector ->
            val name = vector.str("name")
            val builder =
                CallPushBuilder({ vector.long("ts") }, { vector.str("id") }, { vector.hex("nonce") })
            val target = CallPushBuilder.Target(vector.str("pair_id"), vector.str("recipient_device_id"), prk(vector))
            val payload = PlaintextCodec.decodePayload(vector.str("plaintext").toByteArray())
            val request =
                when {
                    vector.str("reason") == "call_incoming" -> builder.incoming(target, state(vector))
                    payload.op == CallOp.LOG_NEW -> builder.missed(target, MissedCall.Logged(logNew(vector)))
                    else -> builder.missed(target, MissedCall.Inferred(state(vector)))
                }
            val json = ProtocolJson.encodeToString(PushRequest.serializer(), checkNotNull(request))
            assertEquals(name, vector.str("push_request"), json)
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/push-request", json)
        }
    }

    @Test
    fun aNameTooLongForApnsIsCutThenDropped() {
        val vector = calls.first { it.str("reason") == "call_incoming" }
        val builder = CallPushBuilder({ vector.long("ts") }, { vector.str("id") })
        val target = CallPushBuilder.Target(vector.str("pair_id"), vector.str("recipient_device_id"), prk(vector))
        val ringing = state(vector)

        val cut = open(builder.incoming(target, ringing.copy(displayName = "Nguyễn Văn A ".repeat(100)))!!, vector)
        assertEquals(CallPushBuilder.NAME_CUT, cut.displayName!!.codePointCount(0, cut.displayName!!.length))
        assertTrue(cut.displayName!!.endsWith("…"))

        // A (made-up) number so long that even the cut name no longer fits: the name goes.
        val crowded = ringing.copy(number = "9".repeat(950), displayName = "ệ".repeat(5_000))
        val dropped = open(builder.incoming(target, crowded)!!, vector)
        assertNull(dropped.displayName)
        assertEquals(crowded.number, dropped.number)

        val waiting = ringing.copy(waitingNumber = "+84900000456", waitingDisplayName = "ệ".repeat(5_000))
        val request = builder.incoming(target, waiting)!!
        assertTrue(request.envB64!!.length <= PushEnvelopeBuilder.ENV_B64_MAX)
    }

    @Test
    fun anEnvelopeThatCannotFitIsNotPushed() {
        val vector = calls.first { it.str("reason") == "call_incoming" }
        val builder = CallPushBuilder({ vector.long("ts") }, { vector.str("id") })
        val target = CallPushBuilder.Target(vector.str("pair_id"), vector.str("recipient_device_id"), prk(vector))
        assertNull(builder.incoming(target, state(vector).copy(number = "9".repeat(3_000))))
    }

    private fun prk(vector: JsonObject): ByteArray = prks.getValue(vector.str("pair_id"))

    private fun state(vector: JsonObject): CallStateData =
        PlaintextCodec.decodeOp(vector.str("plaintext").toByteArray(), CallStateData.serializer()).data

    private fun logNew(vector: JsonObject): CallLogNewData =
        PlaintextCodec.decodeOp(vector.str("plaintext").toByteArray(), CallLogNewData.serializer()).data

    private fun open(
        request: PushRequest,
        vector: JsonObject,
    ): CallStateData {
        val json = Base64Codecs.decodeB64(request.envB64!!).toString(Charsets.UTF_8)
        val plaintext = EnvelopeCipher.open(vector.hex("k_push"), EnvelopeCodec.decode(json))
        return PlaintextCodec.decodeOp(plaintext, CallStateData.serializer()).data
    }
}
