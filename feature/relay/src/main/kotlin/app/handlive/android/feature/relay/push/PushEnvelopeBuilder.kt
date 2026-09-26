package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.message.PushEnvelopes
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.sms.SmsOp

/**
 * The `POST /v1/push` body of a new SMS for an iPhone or iPad without a session (SMS-02 API 2, CONN-04 step 5b): the
 * `sms/new` envelope without `local_id`, sealed with `K_push` of the pair and carried as `env_b64` (standard base64
 * with padding). The text is cut to 1,000 characters; while `env_b64` is still over 3,000 characters, the body, then
 * the snippet, is shortened further at a code point boundary with "…" appended. `collapse_key` is the `message_key`,
 * so repeated sends of one message collapse (one notification per message).
 */
class PushEnvelopeBuilder(
    private val clock: () -> Long,
    private val ids: UuidV7Generator = UuidV7Generator(clock),
) {
    /** `null` when not even an empty text fits (a pathological name list); the message then waits for SMS-01. */
    fun smsNew(
        pairId: String,
        to: String,
        prk: ByteArray,
        new: SmsNewData,
    ): PushRequest? {
        val header = EnvelopeHeader(MessageType.SMS.wire, ids.next(), clock())
        val plain = new.copy(message = new.message.copy(localId = null))
        val first = plain.withBody(plain.message.body.cut(BODY_MAX_CHARS))
        val envB64 = fit(first, prk, header) ?: return null
        return PushRequest(
            pairId = pairId,
            to = to,
            kind = RelayValues.KIND_ALERT,
            reason = RelayValues.REASON_SMS_NEW,
            envB64 = envB64,
            collapseKey = new.message.messageKey,
            ttlS = SMS_TTL_SECONDS,
        )
    }

    /** The envelope as it is, else the longest cut of the body, else an empty body and the longest cut snippet. */
    private fun fit(
        new: SmsNewData,
        prk: ByteArray,
        header: EnvelopeHeader,
    ): String? =
        seal(new, prk, header).takeIf { it.length <= ENV_B64_MAX }
            ?: fitBody(new, prk, header)
            ?: fitSnippet(new.withBody(""), prk, header)

    private fun fitBody(
        new: SmsNewData,
        prk: ByteArray,
        header: EnvelopeHeader,
    ): String? {
        val body = new.message.body
        return longestFitting(body.codePointCount(0, body.length)) { keep ->
            seal(new.withBody(body.cut(keep)), prk, header)
        }
    }

    private fun fitSnippet(
        new: SmsNewData,
        prk: ByteArray,
        header: EnvelopeHeader,
    ): String? {
        val snippet = new.thread.snippet
        return longestFitting(snippet.codePointCount(0, snippet.length)) { keep ->
            seal(new.copy(thread = new.thread.copy(snippet = snippet.cut(keep))), prk, header)
        }
    }

    /** The largest `keep` in `0 until total` whose [seal] fits; its `env_b64`. */
    private fun longestFitting(
        total: Int,
        seal: (Int) -> String,
    ): String? {
        var low = 0
        var high = total - 1
        var best: String? = null
        while (low <= high) {
            val middle = (low + high) / 2
            val envB64 = seal(middle)
            if (envB64.length <= ENV_B64_MAX) {
                best = envB64
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        return best
    }

    private fun seal(
        new: SmsNewData,
        prk: ByteArray,
        header: EnvelopeHeader,
    ): String {
        val plaintext = PlaintextCodec.encodeOp(SmsOp.NEW, SmsNewData.serializer(), new)
        return PushEnvelopes.envB64(PushEnvelopes.seal(prk, header, plaintext))
    }

    companion object {
        /** CONN-04 step 5b: the SMS text in a push is cut at 1,000 characters. */
        const val BODY_MAX_CHARS = 1_000

        /** CONN-04 API 2: `env_b64` ≤ 3,000 characters, so the APNs payload stays under 4 KB. */
        const val ENV_B64_MAX = 3_000

        /** SMS-02 API 2: `ttl_s` 86,400. */
        const val SMS_TTL_SECONDS = 86_400
        const val ELLIPSIS = "…"

        /** The first [keep] code points, with "…" when anything was cut. */
        fun String.cut(keep: Int): String {
            val total = codePointCount(0, length)
            return if (total <= keep) this else substring(0, offsetByCodePoints(0, keep.coerceAtLeast(0))) + ELLIPSIS
        }

        private fun SmsNewData.withBody(body: String) = copy(message = message.copy(body = body))
    }
}
