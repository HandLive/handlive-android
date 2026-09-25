package app.handlive.android.feature.connection.discovery

import app.handlive.android.core.protocol.session.PROTOCOL_VERSION

/**
 * TXT record of `_handlive._tcp` (0.4.1): `v` always; `h` with the pair hints when there is at least one pair; `pr`
 * (8 hex digits of SHA-256 of the QR `pk`) only during a QR pairing window; `pm = 1` only during a PIN window.
 */
data class MdnsTxtRecord(
    val hints: List<String> = emptyList(),
    val pairingRequest: String? = null,
    val pinMode: Boolean = false,
) {
    fun attributes(): Map<String, String> =
        buildMap {
            put(KEY_VERSION, PROTOCOL_VERSION.toString())
            if (hints.isNotEmpty()) put(KEY_HINTS, hints.joinToString(","))
            pairingRequest?.let { put(KEY_PAIRING_REQUEST, it) }
            if (pinMode) put(KEY_PIN_MODE, "1")
        }

    companion object {
        const val KEY_VERSION = "v"
        const val KEY_HINTS = "h"
        const val KEY_PAIRING_REQUEST = "pr"
        const val KEY_PIN_MODE = "pm"
    }
}
