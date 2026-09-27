package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.message.PushEnvelopes
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.XChaCha20Poly1305Aead
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.envelope.EnvelopeHeader
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.relay.RelayValues
import app.handlive.android.feature.call.module.MissedCall
import app.handlive.android.feature.relay.push.PushEnvelopeBuilder.Companion.cut

/**
 * The `POST /v1/push` bodies of calls for an iPhone or iPad without a session (CALL-01 API 4, CALL-04 API 5,
 * CONN-04 step 5b): the `call_event` envelope sealed with `K_push` of the pair, carried as `env_b64`.
 * `call_incoming` carries the ringing state with `collapse_key` `call:<call_id>` for 30 s. `call_missed` carries
 * `log_new` (with the call log) or the idle state (flow A) for a day, keyed `call:<call_id>` so that it replaces the
 * incoming-call notification, or `calllog:<entry_id>` when no call matched. A contact name that would push `env_b64`
 * over 3,000 characters is cut, then dropped; `null` when even that does not fit.
 */
class CallPushBuilder(
    private val clock: () -> Long,
    private val ids: () -> String,
    private val nonces: () -> ByteArray = { SecureRandomBytes.next(XChaCha20Poly1305Aead.NONCE_SIZE) },
) {
    fun incoming(
        target: Target,
        state: CallStateData,
    ): PushRequest? =
        fitState(target.prk, state)?.let { envB64 ->
            target.request(RelayValues.REASON_CALL_INCOMING, envB64, "$CALL_KEY${state.callId}", INCOMING_TTL_SECONDS)
        }

    fun missed(
        target: Target,
        missed: MissedCall,
    ): PushRequest? {
        val (envB64, key) =
            when (missed) {
                is MissedCall.Logged -> {
                    fitLogNew(target.prk, missed.new) to
                        (missed.new.callId?.let { CALL_KEY + it } ?: "$LOG_KEY${missed.new.entry.entryId}")
                }

                is MissedCall.Inferred -> {
                    fitState(target.prk, missed.state) to "$CALL_KEY${missed.state.callId}"
                }
            }
        return envB64?.let { target.request(RelayValues.REASON_CALL_MISSED, it, key, MISSED_TTL_SECONDS) }
    }

    /** Who a push goes to: the pair, its iPhone or iPad, and the `PRK` its `K_push` comes from. */
    class Target(
        val pairId: String,
        val to: String,
        val prk: ByteArray,
    ) {
        fun request(
            reason: String,
            envB64: String,
            collapseKey: String,
            ttlS: Int,
        ) = PushRequest(pairId, to, RelayValues.KIND_ALERT, reason, envB64, collapseKey, ttlS)
    }

    private fun fitState(
        prk: ByteArray,
        state: CallStateData,
    ): String? =
        fit { max ->
            val named =
                state.copy(
                    displayName = state.displayName.shortened(max),
                    waitingDisplayName = state.waitingDisplayName.shortened(max),
                )
            seal(prk, PlaintextCodec.encodeOp(CallOp.STATE, CallStateData.serializer(), named))
        }

    private fun fitLogNew(
        prk: ByteArray,
        new: CallLogNewData,
    ): String? =
        fit { max ->
            val named = new.copy(entry = new.entry.copy(displayName = new.entry.displayName.shortened(max)))
            seal(prk, PlaintextCodec.encodeOp(CallOp.LOG_NEW, CallLogNewData.serializer(), named))
        }

    /** The envelope with the names as they are, else cut to [NAME_CUT], else without them. */
    private fun fit(seal: (Int?) -> String): String? =
        sequenceOf(null, NAME_CUT, 0)
            .map(seal)
            .firstOrNull { it.length <= PushEnvelopeBuilder.ENV_B64_MAX }

    private fun seal(
        prk: ByteArray,
        plaintext: ByteArray,
    ): String {
        val header = EnvelopeHeader(MessageType.CALL_EVENT.wire, ids(), clock())
        return PushEnvelopes.envB64(PushEnvelopes.seal(prk, header, plaintext, nonces()))
    }

    companion object {
        /** CALL-01 API 4 and CONN-04 API 2: an incoming-call push lives 30 s; CALL-04 API 5: a missed call a day. */
        const val INCOMING_TTL_SECONDS = 30
        const val MISSED_TTL_SECONDS = 86_400
        const val CALL_KEY = "call:"
        const val LOG_KEY = "calllog:"

        /** A name longer than this is cut when the envelope would not fit in the APNs payload. */
        const val NAME_CUT = 64

        /** `null` = unchanged; 0 = no name. */
        private fun String?.shortened(max: Int?): String? =
            when {
                this == null || max == null -> this
                max == 0 -> null
                else -> cut(max)
            }
    }
}
