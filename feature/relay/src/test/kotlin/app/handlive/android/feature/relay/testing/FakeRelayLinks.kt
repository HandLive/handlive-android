package app.handlive.android.feature.relay.testing

import app.handlive.android.core.transport.relay.RelayLink
import app.handlive.android.core.transport.relay.RelayLinkEvent
import app.handlive.android.core.transport.relay.RelayLinkFactory
import app.handlive.android.core.transport.relay.RelayPeerLink
import app.handlive.android.feature.relay.RelayOwner
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel

/** `/v1/relay` sockets that open at once, unless the next one is scripted to be refused at the upgrade. */
class FakeRelayLinks : RelayLinkFactory {
    val opened = mutableListOf<FakeLink>()
    private val refusals = ArrayDeque<RelayLinkEvent.Closed>()

    fun refuseNext(closed: RelayLinkEvent.Closed) {
        refusals.addLast(closed)
    }

    override fun open(bearer: String): RelayLink {
        val link = FakeLink(bearer)
        opened += link
        val refusal = refusals.removeFirstOrNull()
        if (refusal == null) {
            link.events.trySend(RelayLinkEvent.Opened)
        } else {
            link.end(refusal)
        }
        return link
    }
}

class FakeLink(
    val bearer: String,
) : RelayLink {
    override val events = Channel<RelayLinkEvent>(Channel.UNLIMITED)
    val sent = mutableListOf<String>()
    var closedWith: Pair<Int, String>? = null
        private set

    val isClosed: Boolean get() = events.isClosedForSend

    fun receive(text: String) {
        events.trySend(RelayLinkEvent.Text(text))
    }

    /** The relay or the network ends the link. */
    fun end(closed: RelayLinkEvent.Closed = RelayLinkEvent.Closed(ABNORMAL_CLOSURE)) {
        events.trySend(closed)
        events.close()
    }

    override fun sendText(text: String): Boolean = !isClosed && sent.add(text)

    override fun sendBinary(bytes: ByteArray): Boolean = !isClosed

    override fun close(
        code: Int,
        reason: String,
    ) {
        closedWith = code to reason
        end(RelayLinkEvent.Closed(code))
    }

    private companion object {
        const val ABNORMAL_CLOSURE = 1006
    }
}

/** The relay feature as the connector sees it: records what the relay said; relayed sessions stay open. */
class FakeOwner : RelayOwner {
    var allowed = true
    val served = mutableListOf<String>()
    val revokedPairs = mutableListOf<Pair<String, String>>()
    var notPaired = 0
    var deviceRevoked = 0
    var connected = 0

    override fun allowed(): Boolean = allowed

    override suspend fun serve(
        link: RelayPeerLink,
        peerDeviceId: String,
    ) {
        served += peerDeviceId
        awaitCancellation()
    }

    override suspend fun pairRevoked(
        pairId: String,
        by: String,
    ) {
        revokedPairs += pairId to by
    }

    override fun notPaired() {
        notPaired++
    }

    override fun deviceRevoked() {
        deviceRevoked++
    }

    override fun connected() {
        connected++
    }
}
