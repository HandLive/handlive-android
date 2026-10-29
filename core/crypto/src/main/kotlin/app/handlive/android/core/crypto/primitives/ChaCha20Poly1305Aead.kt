package app.handlive.android.core.crypto.primitives

import com.google.crypto.tink.aead.internal.InsecureNonceChaCha20Poly1305

/**
 * ChaCha20-Poly1305 (RFC 8439), nonce 12 byte do bên gọi chỉ định — dùng cho Noise (0.4.5): nonce không bao giờ
 * sinh ngẫu nhiên ở đây, luôn là 4 byte 0 ‖ bộ đếm 64-bit LE theo đúng cấu trúc CipherState của Noise.
 */
object ChaCha20Poly1305Aead {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16

    /** Trả `ciphertext ‖ tag`; [plaintext] rỗng → trả đúng [TAG_SIZE] byte (EncryptAndHash("") của Noise). */
    fun seal(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        return InsecureNonceChaCha20Poly1305(key).encrypt(nonce, plaintext, aad)
    }

    /** Mở `ciphertext ‖ tag`; sai khóa, tag, AAD hoặc nonce → [java.security.GeneralSecurityException]. */
    fun open(
        key: ByteArray,
        nonce: ByteArray,
        ciphertextAndTag: ByteArray,
        aad: ByteArray,
    ): ByteArray {
        require(key.size == KEY_SIZE) { "key must be $KEY_SIZE bytes" }
        require(nonce.size == NONCE_SIZE) { "nonce must be $NONCE_SIZE bytes" }
        return InsecureNonceChaCha20Poly1305(key).decrypt(nonce, ciphertextAndTag, aad)
    }
}
