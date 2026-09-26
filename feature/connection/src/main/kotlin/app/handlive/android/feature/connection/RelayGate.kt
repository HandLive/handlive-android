package app.handlive.android.feature.connection

import app.handlive.android.core.transport.relay.RelayPeerLink
import app.handlive.android.core.transport.server.ControlServer
import app.handlive.android.core.transport.server.SessionTransport

/** Where the relay client meets the running control server (CONN-03 step 9, SET-02 field 21). */
class RelayGate internal constructor(
    private val server: () -> ControlServer?,
) {
    /**
     * Runs a `/v1/ctl` session of [peerDeviceId] that reaches the phone through the relay; returns when it ends, or
     * at once while the service is stopped.
     */
    suspend fun serve(
        link: RelayPeerLink,
        peerDeviceId: String,
    ) {
        server()?.serveRelayPeer(link, peerDeviceId)
    }

    /**
     * `relay.enabled` turned off (SET-02 API 1 step 6): the sessions through the relay get the new capability, then
     * `session/bye {shutdown}`; LAN sessions stay.
     */
    suspend fun closeSessions() {
        server()
            ?.sessions
            ?.all()
            ?.filter { it.transport == SessionTransport.RELAY }
            ?.forEach { session ->
                runCatching { session.sendCapabilityUpdate() }
                runCatching { session.bye(BYE_SHUTDOWN) }
            }
    }

    /** The number of sessions that currently run through the relay. */
    fun sessionCount(): Int = server()?.sessions?.all()?.count { it.transport == SessionTransport.RELAY } ?: 0

    private companion object {
        const val BYE_SHUTDOWN = "shutdown"
    }
}
