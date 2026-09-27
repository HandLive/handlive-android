package app.handlive.android.feature.relay

import app.handlive.android.core.protocol.relay.RelayOp
import app.handlive.android.core.protocol.relay.RelayRvJoin
import app.handlive.android.core.protocol.relay.RelayRvMsg
import app.handlive.android.core.protocol.relay.RelayWire
import app.handlive.android.core.transport.relay.RelayRendezvous
import app.handlive.android.core.transport.server.PairingEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The pairing rendezvous this phone joined (PAIR-01 step 6 over the relay): each is joined again on every new
 * connection, and the first `pair` envelope of the client starts the pairing endpoint on it. Used on the connector's
 * serial scope only.
 */
internal class RelayRendezvousTable(
    private val scope: CoroutineScope,
    private val send: (String) -> Boolean,
) {
    private val entries = LinkedHashMap<String, Entry>()

    val isEmpty: Boolean get() = entries.isEmpty()

    /** Adds [rvId]; `rv_join` goes out now when the link is [connected], else when it opens. */
    fun join(
        rvId: String,
        endpoint: PairingEndpoint,
        connected: Boolean,
    ) {
        entries.remove(rvId)?.socket?.end()
        entries[rvId] = Entry(RelayRendezvous(rvId, send), endpoint)
        if (connected) sendJoin(rvId)
    }

    /** The pairing window closed: the exchange reading the rendezvous sees it end. */
    fun leave(rvId: String) {
        entries.remove(rvId)?.socket?.end()
    }

    /** A new connection: the relay forgot the rendezvous of the old one. */
    fun rejoinAll() = entries.keys.forEach(::sendJoin)

    fun deliver(message: RelayRvMsg) {
        val entry = entries[message.rvId] ?: return
        entry.socket.deliver(message.env)
        if (!entry.started) {
            entry.started = true
            scope.launch { entry.endpoint.handle(entry.socket) }
        }
    }

    fun endAll() {
        entries.values.forEach { it.socket.end() }
        entries.clear()
    }

    private fun sendJoin(rvId: String) {
        send(RelayWire.control(RelayRvJoin.serializer(), RelayRvJoin(RelayOp.RV_JOIN, rvId)))
    }

    private class Entry(
        val socket: RelayRendezvous,
        val endpoint: PairingEndpoint,
        var started: Boolean = false,
    )
}
