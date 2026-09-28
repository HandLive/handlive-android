package app.handlive.android.core.crypto.derivation

import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.id.UuidBytes
import java.nio.ByteBuffer

/**
 * The revocation statement `HLREVOKE1` (0.6.2, PAIR-03): `"HLREVOKE1"` ‖ `pair_id` (16) ‖ `by` (16) ‖ `revoked_at`
 * (uint64 BE, ms), 49 bytes, signed with `ik_sig` of the revoking device. Matches `shared/test-vectors/revoke.json`.
 */
object RevocationStatement {
    private val LABEL = "HLREVOKE1".toByteArray(Charsets.US_ASCII)
    private const val SIGNATURE_SIZE = 64

    fun message(
        pairId: String,
        by: String,
        revokedAt: Long,
    ): ByteArray =
        LABEL + UuidBytes.toBytes(pairId) + UuidBytes.toBytes(by) +
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(revokedAt).array()

    /**
     * PAIR-03 API 4 logic 1, PAIR-02 API 1 logic 4: a revocation from the relay counts only when [by] is the pair's
     * peer ([peerDeviceId]) and [sig] verifies strictly with the peer's stored `ik_sig` public key. A missing
     * statement (a row revoked before signed revocation), another signer or a bad signature → `false`.
     */
    fun isFromPeer(
        pairId: String,
        by: String?,
        revokedAt: Long?,
        sig: String?,
        peerDeviceId: String,
        peerSigningKey: ByteArray,
    ): Boolean {
        if (by == null || revokedAt == null || sig == null || by != peerDeviceId) return false
        return runCatching {
            Ed25519Keys.verify(
                peerSigningKey,
                message(pairId, by, revokedAt),
                Base64Codecs.decodeB64u(sig, SIGNATURE_SIZE),
            )
        }.getOrDefault(false)
    }
}
