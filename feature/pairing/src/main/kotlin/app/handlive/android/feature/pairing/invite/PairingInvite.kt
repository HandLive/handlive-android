package app.handlive.android.feature.pairing.invite

import android.net.Uri
import app.handlive.android.core.protocol.encoding.Base64Codecs
import java.security.MessageDigest

/**
 * The pairing QR code shown by the Mac or iPhone (PAIR-01 API 1):
 * `handlive://pair?v=1&pk=<b64u>&ps=<b64u>&d=<name>[&rv=<b64u>]`. Unknown parameters are ignored (forward
 * compatibility); a missing required parameter, a wrong length or another `v` makes the code invalid (E1).
 */
class PairingInvite(
    /** `ik_dh_pub` of the client; `pair/hello` must carry the same key. */
    val clientDhPublicKey: ByteArray,
    /** `pairing_secret`: lives only in memory, for the 120 s of the window. */
    val pairingSecret: ByteArray,
    val clientName: String,
    /** Relay rendezvous (Phase 2); parsed so a Phase 2 code still pairs on the LAN. */
    val rendezvous: ByteArray?,
) {
    /** TXT `pr` during the window: the first 8 hex digits of SHA-256 over the 32 bytes of `pk` (0.4.1). */
    val pairingRequestHint: String
        get() =
            MessageDigest
                .getInstance("SHA-256")
                .digest(clientDhPublicKey)
                .copyOf(HINT_BYTES)
                .joinToString("") { "%02x".format(it) }

    companion object {
        private const val SCHEME = "handlive"
        private const val HOST = "pair"
        private const val VERSION = "1"
        private const val KEY_SIZE = 32
        private const val RENDEZVOUS_SIZE = 16
        private const val MAX_NAME = 64
        private const val MAX_URI = 300
        private const val HINT_BYTES = 4

        /** `null` when [text] is not a valid HandLive pairing code (`QR_INVALID`, E1). */
        fun parse(text: String): PairingInvite? =
            runCatching {
                require(text.length <= MAX_URI * 2)
                val uri = Uri.parse(text.trim())
                require(uri.scheme.equals(SCHEME, ignoreCase = true) && uri.host.equals(HOST, ignoreCase = true))
                require(uri.getQueryParameter("v") == VERSION)
                val name = requireNotNull(uri.getQueryParameter("d"))
                require(name.isNotBlank() && name.codePointCount(0, name.length) <= MAX_NAME)
                PairingInvite(
                    clientDhPublicKey = Base64Codecs.decodeB64u(requireNotNull(uri.getQueryParameter("pk")), KEY_SIZE),
                    pairingSecret = Base64Codecs.decodeB64u(requireNotNull(uri.getQueryParameter("ps")), KEY_SIZE),
                    clientName = name,
                    rendezvous = uri.getQueryParameter("rv")?.let { Base64Codecs.decodeB64u(it, RENDEZVOUS_SIZE) },
                )
            }.getOrNull()
    }
}
