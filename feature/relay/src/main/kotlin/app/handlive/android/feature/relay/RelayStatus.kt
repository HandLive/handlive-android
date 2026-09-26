package app.handlive.android.feature.relay

/**
 * The relay as Settings shows it (SET-02 field 21, CONN-03 E3): the link, and whether the relay refused this device
 * (410 `DEVICE_REVOKED` → "This device was removed from the internet service" until the user turns it back on).
 */
data class RelayStatus(
    val link: RelayLinkState = RelayLinkState.OFF,
    val deviceRevoked: Boolean = false,
    /** Whether this build has a relay at all (`RELAY_HOST` configured). */
    val available: Boolean = false,
)
