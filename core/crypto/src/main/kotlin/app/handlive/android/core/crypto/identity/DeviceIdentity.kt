package app.handlive.android.core.crypto.identity

import app.handlive.android.core.crypto.derivation.DeviceIdDerivation
import app.handlive.android.core.crypto.keystore.SecretStore
import app.handlive.android.core.crypto.primitives.Ed25519Keys
import app.handlive.android.core.crypto.primitives.X25519Keys
import java.security.GeneralSecurityException

/**
 * Identity keys of this phone (0.6.1): `ik_sig` (Ed25519 seed) and `ik_dh` (X25519 private key), plus the
 * self-certifying `device_id` derived from `ik_sig_pub` (0.2). Never log the private parts.
 */
class DeviceIdentity(
    private val signingSeed: ByteArray,
    private val dhPrivateKey: ByteArray,
) {
    val signingPublicKey: ByteArray = Ed25519Keys.publicFromSeed(signingSeed)
    val dhPublicKey: ByteArray = X25519Keys.publicFromPrivate(dhPrivateKey)
    val deviceId: String = DeviceIdDerivation.deviceId(signingPublicKey)

    fun sign(message: ByteArray): ByteArray = Ed25519Keys.sign(signingSeed, message)

    /** X25519(`ik_dh`, [peerDhPublicKey]); throws for low-order points. */
    fun dhSharedSecret(peerDhPublicKey: ByteArray): ByteArray = X25519Keys.sharedSecret(dhPrivateKey, peerDhPublicKey)

    fun dhPrivateKeyCopy(): ByteArray = dhPrivateKey.copyOf()
}

/**
 * Loads or creates the identity keys in a [SecretStore] (SET-01 API 1). Idempotent: existing keys are never
 * replaced, because a new `ik_sig` means a new `device_id` and breaks every pair. Keys that can no longer be
 * decrypted (the Keystore master key was lost) are replaced — the pairs were unusable anyway, since their `PRK`
 * is sealed under the same key.
 */
object DeviceIdentityStore {
    const val SIGNING_SEED = "ik_sig"
    const val DH_PRIVATE_KEY = "ik_dh"

    fun loadOrCreate(secrets: SecretStore): DeviceIdentity {
        val seed = readOrNull(secrets, SIGNING_SEED)
        val dh = readOrNull(secrets, DH_PRIVATE_KEY)
        val signingSeed = seed ?: Ed25519Keys.generateSeed().also { secrets.put(SIGNING_SEED, it) }
        val dhPrivateKey = dh ?: X25519Keys.generatePrivateKey().also { secrets.put(DH_PRIVATE_KEY, it) }
        return DeviceIdentity(signingSeed, dhPrivateKey)
    }

    private fun readOrNull(
        secrets: SecretStore,
        name: String,
    ): ByteArray? =
        try {
            secrets.get(name)
        } catch (_: GeneralSecurityException) {
            null
        }
}
