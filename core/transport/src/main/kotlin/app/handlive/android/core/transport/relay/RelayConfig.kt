package app.handlive.android.core.transport.relay

/**
 * Where the relay is (0.4.3: `{RELAY_HOST}` is configured at build time) and the SPKI pins its certificate chain
 * must match: ISRG Root X1 and X2 plus the project's backup key. An empty host means no relay in this build.
 */
class RelayConfig(
    val host: String,
    val pins: List<String> = DEFAULT_PINS,
    val baseUrl: String = "https://$host",
    val webSocketUrl: String = "wss://$host$RELAY_PATH",
) {
    val available: Boolean get() = host.isNotBlank()

    companion object {
        const val RELAY_PATH = "/v1/relay"

        /** SHA-256 of the SubjectPublicKeyInfo of ISRG Root X1 and ISRG Root X2 (Let's Encrypt roots). */
        val DEFAULT_PINS =
            listOf(
                "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=",
                "sha256/diGVwiVYbubAI3RW4hB9xU8e/CH2GnkuvVFZE8zmgzI=",
            )

        /** A relay reached without TLS (tests, a relay on this machine): no pins apply. */
        fun unpinned(
            baseUrl: String,
            webSocketUrl: String,
        ) = RelayConfig(host = baseUrl.substringAfter("://"), pins = emptyList(), baseUrl, webSocketUrl)
    }
}
