package app.handlive.android.core.crypto.derivation

import app.handlive.android.core.protocol.id.UuidBytes
import java.nio.ByteBuffer

/**
 * Byte strings the phone signs with `ik_sig` for the relay (0.6.4; CONN-03 API 1, API 3), matching
 * `shared/test-vectors/relay-auth.json`. The relay client arrives in Phase 2; the builders live here so every
 * platform proves the same bytes from Phase 1 on.
 */
object RelaySignatureMessages {
    private val REGISTER_LABEL = "HLREG1".toByteArray(Charsets.US_ASCII)
    private val AUTH_LABEL = "HLAUTH1".toByteArray(Charsets.US_ASCII)
    const val CHALLENGE_SIZE = 32

    /** `POST /v1/devices`: "HLREG1" ‖ device_id (16) ‖ ik_sig_pub (32) ‖ UTF-8(platform) ‖ ts (int64 BE). */
    fun registration(
        deviceId: String,
        signingPublicKey: ByteArray,
        platform: String,
        timestamp: Long,
    ): ByteArray =
        REGISTER_LABEL + UuidBytes.toBytes(deviceId) + signingPublicKey + platform.toByteArray(Charsets.UTF_8) +
            ByteBuffer.allocate(Long.SIZE_BYTES).putLong(timestamp).array()

    /** `POST /v1/auth/token`: "HLAUTH1" ‖ challenge (32 raw bytes) ‖ device_id (16). */
    fun token(
        challenge: ByteArray,
        deviceId: String,
    ): ByteArray {
        require(challenge.size == CHALLENGE_SIZE) { "challenge must be 32 bytes" }
        return AUTH_LABEL + challenge + UuidBytes.toBytes(deviceId)
    }
}
