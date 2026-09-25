package app.handlive.android.core.crypto

import app.handlive.android.core.crypto.derivation.PairingAuthDerivation
import app.handlive.android.core.crypto.primitives.Argon2id
import app.handlive.android.core.crypto.primitives.Blake2b
import app.handlive.android.core.protocol.testing.Hex
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * BLAKE2b (RFC 7693 appendix A) and Argon2id (RFC 9106 §5.3), plus `K_pin` with the production parameters of 0.6.2
 * against OpenSSL 3 (Python `cryptography` 50 `Argon2id`, lanes = 4) — an implementation independent of this one.
 */
class Argon2idTest {
    @Test
    fun blake2b512OfAbcMatchesRfc7693() {
        assertEquals(
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1" +
                "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
            Hex.encode(Blake2b.hash(64, "abc".toByteArray())),
        )
    }

    @Test
    fun argon2idMatchesRfc9106Vector() {
        val tag =
            Argon2id.hash(
                password = ByteArray(32) { 1 },
                salt = ByteArray(16) { 2 },
                parameters = Argon2id.Parameters(passes = 3, memoryKiB = 32, parallelism = 4, tagLength = 32),
                secret = ByteArray(8) { 3 },
                associatedData = ByteArray(12) { 4 },
            )
        assertEquals("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659", Hex.encode(tag))
    }

    @Test
    fun pinKeyWithTheSpecParametersMatchesOpenSsl() {
        val clientNonce = ByteArray(32) { it.toByte() }
        val serverNonce = ByteArray(32) { (it + 32).toByte() }
        assertEquals(
            "a3c171d44151caca5493ab545a4cd475e6492ba715500302369297e15178bc88",
            Hex.encode(PairingAuthDerivation.pinKey("042917", clientNonce, serverNonce)),
        )
    }
}
