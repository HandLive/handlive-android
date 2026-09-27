package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.crypto.message.PushEnvelopes
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.sms.SmsOp
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.hex
import app.handlive.android.core.protocol.testing.str
import app.handlive.android.feature.relay.push.PushEnvelopeBuilder.Companion.cut
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `POST /v1/push` body of a new SMS (SMS-02 API 2, CONN-04 step 5b) against push-envelope.json and the schema. */
class PushEnvelopeBuilderTest {
    private val vectors = SharedTestVectors.vectors("push-envelope.json")
    private val vector = vectors.first { it.str("name").contains("Vietnamese") }
    private val prk = vectors.first { it.str("kind") == "key" && it.str("pair_id") == vector.str("pair_id") }.hex("prk")
    private val kPush = vector.hex("k_push")
    private val new: SmsNewData =
        PlaintextCodec.decodeOp(vector.str("plaintext").toByteArray(), SmsNewData.serializer()).data
    private val builder = PushEnvelopeBuilder({ vector.str("ts").toLong() })

    @Test
    fun theSmsIsTheSmsNewEnvelopeSealedWithKPushWithoutLocalId() {
        val withLocalId = new.copy(message = new.message.copy(localId = "0192f3e2-4b5c-7d6e-9f70-8a9b0c1d2e3f"))
        val request = build(withLocalId)

        assertEquals(vector.str("pair_id"), request.pairId)
        assertEquals(vector.str("recipient_device_id"), request.to)
        assertEquals("alert", request.kind)
        assertEquals("sms_new", request.reason)
        // The collapse key is the message_key itself: repeated sends of one message collapse (SMS-02 API 2).
        assertEquals("sms:12847", request.collapseKey)
        assertEquals(86_400, request.ttlS)
        assertEquals(new, open(request))
        JsonSchemaValidation.assertValid(
            "relay-rest.schema.json#/\$defs/push-request",
            ProtocolJson.encodeToString(PushRequest.serializer(), request),
        )
    }

    @Test
    fun aLongTextIsCutToAThousandCodePointsEndingWithAnEllipsis() {
        val long = new.withBody("a".repeat(1_500))

        val body = open(build(long)).message.body

        assertEquals("a".repeat(999) + "…", body)
        assertEquals(1_000, body.codePointCount(0, body.length))
    }

    @Test
    fun aTextTooLongForApnsIsTheLongestCutThatFits() {
        // Vietnamese letters take 3 bytes and an emoji 4 (two UTF-16 units): 1,000 code points no longer fit.
        val text = "Nhớ mang theo tài liệu 📄 ".repeat(60)
        val request = build(new.withBody(text))

        assertTrue(request.envB64!!.length <= PushEnvelopeBuilder.ENV_B64_MAX)
        val body = open(request).message.body
        val kept = body.codePointCount(0, body.length)
        assertTrue(body.endsWith("…"))
        assertTrue(text.startsWith(body.removeSuffix("…")))
        assertTrue(kept < PushEnvelopeBuilder.BODY_MAX_CHARS)
        // One code point more would not fit: the cut is the longest one.
        assertTrue(envB64Length(new.withBody(text.cut(kept + 1))) > PushEnvelopeBuilder.ENV_B64_MAX)
    }

    @Test
    fun cutsKeepWholeCodePoints() {
        assertEquals("ab", "ab".cut(2))
        assertEquals("a…", "abc".cut(2))
        assertEquals("📄…", "📄📄📄".cut(2))
        assertEquals("…", "📄📄".cut(1))
    }

    @Test
    fun whenTheBodyCannotShrinkEnoughTheSnippetIsCutNext() {
        val crowded = new.copy(thread = new.thread.copy(snippet = "ệ".repeat(4_000)))

        val request = build(crowded)
        val opened = open(request)

        // The body goes first (down to "…"), then the snippet, each cut at a code point with "…" (SMS-02 API 2).
        assertEquals("…", opened.message.body)
        assertTrue(opened.thread.snippet.endsWith("…"))
        assertTrue(opened.thread.snippet.length < 4_000)
        assertTrue(request.envB64!!.length <= PushEnvelopeBuilder.ENV_B64_MAX)
    }

    @Test
    fun anEnvelopeThatCannotFitIsNotPushed() {
        val huge = new.copy(thread = new.thread.copy(addresses = List(400) { "+8490000%04d".format(it) }))

        assertNull(builder.smsNew("p", "d", prk, huge))
    }

    private fun build(data: SmsNewData): PushRequest =
        builder.smsNew(vector.str("pair_id"), vector.str("recipient_device_id"), prk, data)!!

    private fun open(request: PushRequest): SmsNewData {
        val json = Base64Codecs.decodeB64(request.envB64!!).toString(Charsets.UTF_8)
        val envelope = EnvelopeCodec.decode(json)
        assertEquals(MessageType.SMS.wire, envelope.type)
        val plaintext = EnvelopeCipher.open(kPush, envelope)
        val payload = PlaintextCodec.decodeOp(plaintext, SmsNewData.serializer())
        assertEquals(SmsOp.NEW, payload.op)
        assertTrue(!plaintext.toString(Charsets.UTF_8).contains("local_id"))
        return payload.data
    }

    private fun envB64Length(data: SmsNewData): Int {
        val header =
            EnvelopeHeader(MessageType.SMS.wire, "0192f3e4-7a10-7b20-8c30-9d40ae50bf60", vector.str("ts").toLong())
        val plaintext = PlaintextCodec.encodeOp(SmsOp.NEW, SmsNewData.serializer(), data)
        return PushEnvelopes.envB64(PushEnvelopes.seal(prk, header, plaintext)).length
    }

    private fun SmsNewData.withBody(body: String) = copy(message = message.copy(body = body))
}
