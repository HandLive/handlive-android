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

/** How `DELETE /v1/devices/me` went (SET-02 A2). */
enum class ServerDeletion {
    /** Deleted, or the relay no longer knew the device (204, or 404 `DEVICE_NOT_FOUND` — E6), or no relay at all. */
    DONE,

    /** No network, 429 or 5xx: nothing changed (E5); for "Delete All", the user may delete locally anyway (E7). */
    UNREACHABLE,
}
