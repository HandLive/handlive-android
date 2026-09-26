package app.handlive.android.feature.relay

import app.handlive.android.core.protocol.ProtocolException
import app.handlive.android.core.protocol.relay.RelayErrorCode
import app.handlive.android.core.protocol.relay.RelayIncoming
import app.handlive.android.core.protocol.relay.RelayWire
import app.handlive.android.core.transport.relay.RelayAuth
import app.handlive.android.core.transport.relay.RelayLink
import app.handlive.android.core.transport.relay.RelayLinkEvent
import app.handlive.android.core.transport.relay.RelayLinkFactory
import app.handlive.android.core.transport.relay.RelayPeerMux
import app.handlive.android.core.transport.relay.RelayRequestException
import app.handlive.android.core.transport.relay.RelayUnreachableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How one connection to `/v1/relay` ended. */
internal enum class RelayOutcome {
    /** The link was open, then the relay or the network closed it: reconnect from the first backoff step. */
    CLOSED,

    /** It did not open (network, 5xx, 429): the next backoff step. */
    FAILED,

    /** Nothing used it for `RELAY_IDLE_DISCONNECT`: no reconnect until a new demand. */
    IDLE,

    /** Refused for good — the device revoked (E3) or a certificate outside the pins (E7): no reconnect. */
    STOP,
}

/**
 * One `/v1/relay` connection (CONN-03 steps 3–4 and 9): opens the link with the device token — renewed once after
 * 401 `TOKEN_EXPIRED`, the device registered once after 404 `DEVICE_NOT_FOUND` (E2) — and routes what the relay
 * sends: peer envelopes to their `/v1/ctl` sessions, `presence` and `error` to end them, `rv_msg` to the rendezvous,
 * `pair_revoked` to the owner. It leaves by itself after `RELAY_IDLE_DISCONNECT` without sessions, rendezvous or
 * traffic. Used on the connector's serial scope only.
 */
internal class RelayConnection(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val auth: RelayAuth,
    private val links: RelayLinkFactory,
    private val owner: RelayOwner,
    private val rendezvous: RelayRendezvousTable,
) {
    private var link: RelayLink? = null
    private var mux: RelayPeerMux? = null
    private var lastActivity = 0L
    private var idleClosed = false

    /** The relay accepted the link (`Opened` seen) and it has not closed yet. */
    var isOpen = false
        private set

    val peerCount: Int get() = mux?.peerCount ?: 0

    /** Counts as traffic for the idle rule (a new demand while connected). */
    fun touch() {
        lastActivity = clock()
    }

    /** Connects and reads the link until it ends; [onOpened] runs once the relay accepted it. */
    suspend fun connect(onOpened: () -> Unit): RelayOutcome =
        try {
            val first = serve(auth.token(), onOpened)
            when {
                first.isTokenExpired() -> {
                    auth.forget()
                    outcomeOf(serve(auth.token(), onOpened))
                }

                first.isUnknownDevice() -> {
                    auth.register()
                    auth.forget()
                    outcomeOf(serve(auth.token(), onOpened))
                }

                else -> {
                    outcomeOf(first)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: RelayRequestException) {
            if (e.code == RelayErrorCode.DEVICE_REVOKED) {
                owner.deviceRevoked()
                RelayOutcome.STOP
            } else {
                RelayOutcome.FAILED
            }
        } catch (_: RelayUnreachableException) {
            RelayOutcome.FAILED
        }

    fun send(text: String): Boolean {
        lastActivity = clock()
        return link?.sendText(text) == true
    }

    /** Leaves the relay now (`relay.enabled` off, the service stopping, the device revoked). */
    fun close(reason: String) {
        link?.close(NORMAL_CLOSURE, reason)
        link = null
        isOpen = false
        mux?.endAll()
        mux = null
    }

    private fun outcomeOf(closed: RelayLinkEvent.Closed): RelayOutcome =
        when {
            idleClosed -> {
                RelayOutcome.IDLE
            }

            closed.errorCode == RelayErrorCode.DEVICE_REVOKED -> {
                owner.deviceRevoked()
                RelayOutcome.STOP
            }

            // CONN-03 E7: never connect to a relay whose certificate matches none of the pins.
            closed.pinMismatch -> {
                RelayOutcome.STOP
            }

            closed.code != null -> {
                RelayOutcome.CLOSED
            }

            else -> {
                RelayOutcome.FAILED
            }
        }

    /** Opens the link with [token] and reads it until it closes; returns how it closed. */
    private suspend fun serve(
        token: String,
        onOpened: () -> Unit,
    ): RelayLinkEvent.Closed {
        val opened = links.open(token)
        link = opened
        idleClosed = false
        val idle = scope.launch { closeWhenIdle(opened) }
        try {
            for (event in opened.events) {
                when (event) {
                    RelayLinkEvent.Opened -> {
                        isOpen = true
                        lastActivity = clock()
                        mux = RelayPeerMux(scope, ::send, owner::serve)
                        // A rendezvous joined while offline, or on the previous connection, is joined again.
                        rendezvous.rejoinAll()
                        onOpened()
                    }

                    is RelayLinkEvent.Text -> {
                        onText(event.text)
                    }

                    is RelayLinkEvent.Binary -> {
                        lastActivity = clock()
                    }

                    is RelayLinkEvent.Closed -> {
                        return event
                    }
                }
            }
            return RelayLinkEvent.Closed(null)
        } finally {
            idle.cancel()
            mux?.endAll()
            mux = null
            if (link === opened) {
                link = null
                isOpen = false
            }
        }
    }

    private suspend fun onText(text: String) {
        lastActivity = clock()
        when (val message = runCatching { RelayWire.decode(text) }.getOrElse { decodeFailure(it) }) {
            is RelayIncoming.Envelope -> {
                mux?.onEnvelope(message.message.from, message.message.env)
            }

            is RelayIncoming.Presence -> {
                if (!message.message.online) mux?.onPeerGone(message.message.peerDeviceId)
            }

            is RelayIncoming.Error -> {
                onError(message.message.code, message.message.to)
            }

            is RelayIncoming.RvMsg -> {
                rendezvous.deliver(message.message)
            }

            is RelayIncoming.PairRevoked -> {
                owner.pairRevoked(message.message.pairId, message.message.by)
            }

            // rv_joined, unknown ops (0.4.3: ignored) and frames that do not parse.
            else -> {
                Unit
            }
        }
    }

    /** CONN-03 E4, E8: the peer is gone or not paired any more; its session ends. */
    private fun onError(
        code: String,
        to: String?,
    ) {
        if (code == RelayErrorCode.NOT_CONNECTED || code == RelayErrorCode.NOT_PAIRED) to?.let { mux?.onPeerGone(it) }
        if (code == RelayErrorCode.NOT_PAIRED) owner.notPaired()
    }

    /** `RELAY_IDLE_DISCONNECT`: no relayed session, no rendezvous and no traffic for 5 minutes → leave. */
    private suspend fun closeWhenIdle(opened: RelayLink) {
        while (true) {
            delay(RelayConstants.IDLE_CHECK_MILLIS)
            if (peerCount > 0 || !rendezvous.isEmpty) lastActivity = clock()
            if (!owner.allowed() || clock() - lastActivity >= RelayConstants.IDLE_DISCONNECT_MILLIS) break
        }
        idleClosed = true
        opened.close(NORMAL_CLOSURE, IDLE_REASON)
    }

    private companion object {
        const val NORMAL_CLOSURE = 1000
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_NOT_FOUND = 404
        const val IDLE_REASON = "idle"

        fun RelayLinkEvent.Closed.isTokenExpired() =
            httpStatus == HTTP_UNAUTHORIZED && errorCode == RelayErrorCode.TOKEN_EXPIRED

        fun RelayLinkEvent.Closed.isUnknownDevice() =
            httpStatus == HTTP_NOT_FOUND && errorCode == RelayErrorCode.DEVICE_NOT_FOUND

        /** A frame the phone cannot read is ignored, like an unknown op (0.4.3). */
        fun decodeFailure(error: Throwable): RelayIncoming? = if (error is ProtocolException) null else throw error
    }
}
