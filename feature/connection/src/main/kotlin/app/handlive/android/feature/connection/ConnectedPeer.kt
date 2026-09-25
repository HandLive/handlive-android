package app.handlive.android.feature.connection

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.feature.connection.session.PeerSession

/** A client with an open `/v1/ctl` session, as the notification, the tile and the Devices tab show it. */
data class ConnectedPeer(
    val pairId: String,
    val peerName: String,
    val platform: PeerPlatform,
    val channel: PeerSession.Channel,
)

/** SET-01 field 4: state of the connection service. */
enum class ServiceState {
    STOPPED,
    STARTING,
    RUNNING,

    /** The foreground service or the TLS server could not start (SET-01 E2). */
    FAILED,
}

/** What the pairing window adds to the TXT record (0.4.1): `pr` during a QR window, `pm = 1` during a PIN window. */
data class PairingAdvert(
    val pairingRequest: String? = null,
    val pinMode: Boolean = false,
) {
    companion object {
        val NONE = PairingAdvert()
    }
}
