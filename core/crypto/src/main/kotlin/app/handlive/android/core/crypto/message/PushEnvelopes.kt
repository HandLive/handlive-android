package app.handlive.android.core.crypto.message

import app.handlive.android.core.crypto.derivation.PushKeyDerivation
import app.handlive.android.core.crypto.primitives.SecureRandomBytes
import app.handlive.android.core.crypto.primitives.XChaCha20Poly1305Aead
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.Envelope
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.envelope.EnvelopeHeader

/**
 * The envelope an alert push carries to an iPhone or iPad without a session (0.4.4, CONN-04 step 5b): built as over a
 * session but sealed with `K_push` of the pair, then carried as `env_b64` = standard base64 (with padding) of the
 * UTF-8 envelope JSON — the same string APNs delivers as `hl` for the Notification Service Extension to decrypt
 * (`shared/test-vectors/push-envelope.json`).
 */
object PushEnvelopes {
    fun seal(
        prk: ByteArray,
        header: EnvelopeHeader,
        plaintext: ByteArray,
        nonce: ByteArray = SecureRandomBytes.next(XChaCha20Poly1305Aead.NONCE_SIZE),
    ): Envelope = EnvelopeCipher.seal(PushKeyDerivation.kPush(prk), header, plaintext, nonce)

    /** `env_b64` of `POST /v1/push` (CONN-04 API 2) and `hl` of the APNs payload (API 4). */
    fun envB64(envelope: Envelope): String =
        Base64Codecs.encodeB64(EnvelopeCodec.encode(envelope).toByteArray(Charsets.UTF_8))
}
