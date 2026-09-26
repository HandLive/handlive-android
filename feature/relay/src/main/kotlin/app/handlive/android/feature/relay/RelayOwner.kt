package app.handlive.android.feature.relay

import app.handlive.android.core.transport.relay.RelayPeerLink

/** Where the phone stands with the relay (for the UI and the other relay parts). */
enum class RelayLinkState { OFF, CONNECTING, CONNECTED, BACKOFF }

/** What the relay connection needs from the relay feature, and what it tells it about the pairs and this device. */
interface RelayOwner {
    /** The relay may be used now: built with a host, `relay.enabled`, the service running, the device not revoked. */
    fun allowed(): Boolean

    /** Runs the `/v1/ctl` session of a peer that reached the phone through the relay (CONN-03 step 9). */
    suspend fun serve(
        link: RelayPeerLink,
        peerDeviceId: String,
    )

    /** `pair_revoked` (PAIR-03 API 4): clean up the pair as its receiver; repeats are ignored. */
    suspend fun pairRevoked(
        pairId: String,
        by: String,
    )

    /** `error NOT_PAIRED` (CONN-03 E4): the relay does not see the pair; check `GET /v1/pairs`. */
    fun notPaired()

    /** 410 `DEVICE_REVOKED` (CONN-03 E3): the relay refuses this device. */
    fun deviceRevoked()

    /** CONN-03 E7: the relay's certificate matches none of the pins; the phone does not connect. */
    fun pinMismatch()

    /** The link is open: registrations and checks may run now. */
    fun connected()
}
