package app.handlive.android.core.crypto.derivation

import app.handlive.android.core.crypto.primitives.HkdfSha256

/** `K_push` = HKDF-SHA256(`PRK`, empty salt, info "handlive/v1/push", L = 32) (0.6.1, CONN-04 step 5b). */
object PushKeyDerivation {
    const val INFO = "handlive/v1/push"

    fun kPush(prk: ByteArray): ByteArray = HkdfSha256.derive(ikm = prk, info = INFO)
}
