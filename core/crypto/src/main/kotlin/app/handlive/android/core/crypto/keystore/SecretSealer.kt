package app.handlive.android.core.crypto.keystore

import com.google.crypto.tink.Aead

/**
 * Seals small secrets that live inside other records, such as a pair's `PRK` in the `prk_enc` column (0.6.1,
 * 0.9.1). [context] is bound as associated data, so a sealed value cannot be moved to another record.
 */
interface SecretSealer {
    fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray

    /** Throws `GeneralSecurityException` when [sealed] was altered, moved or sealed under another key. */
    fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray
}

/** [SecretSealer] over a Tink [Aead]; on Android the AEAD is the keyset wrapped by `hl_master`. */
class AeadSecretSealer(
    private val aead: Aead,
) : SecretSealer {
    override fun seal(
        plaintext: ByteArray,
        context: String,
    ): ByteArray = aead.encrypt(plaintext, associatedData(context))

    override fun open(
        sealed: ByteArray,
        context: String,
    ): ByteArray = aead.decrypt(sealed, associatedData(context))

    private fun associatedData(context: String): ByteArray = "$AD_PREFIX$context".toByteArray(Charsets.UTF_8)

    private companion object {
        const val AD_PREFIX = "handlive/v1/sealed/"
    }
}
