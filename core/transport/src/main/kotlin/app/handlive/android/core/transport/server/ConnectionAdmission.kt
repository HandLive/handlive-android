package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.TransportConstants
import kotlin.time.Duration

/** Resource limits of the `/v1/ctl` endpoint (CONN-01 API 3–4, CONN-02) and of `/v1/pair` (PAIR-01 API 2). */
class ControlServerLimits(
    val maxUnauthenticatedConnections: Int = TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS,
    val maxUnauthenticatedPerAddress: Int = TransportConstants.MAX_UNAUTHENTICATED_PER_ADDRESS,
    val authFailuresBeforeBlock: Int = TransportConstants.AUTH_FAILURES_BEFORE_BLOCK,
    val authFailureWindow: Duration = TransportConstants.AUTH_FAILURE_WINDOW,
    val preHandshakeFailuresBeforeBlock: Int = TransportConstants.PRE_HANDSHAKE_FAILURES_BEFORE_BLOCK,
    val preHandshakeFailureWindow: Duration = TransportConstants.PRE_HANDSHAKE_FAILURE_WINDOW,
    val ipBlockDuration: Duration = TransportConstants.IP_BLOCK_DURATION,
    val idleTimeout: Duration = TransportConstants.IDLE_TIMEOUT,
    val pairConnections: Int = TransportConstants.PAIR_CONNECTIONS,
    val pairConnectionsPerAddress: Int = TransportConstants.PAIR_CONNECTIONS_PER_ADDRESS,
) {
    /** The limits of `/v1/pair`'s own admission: [pairConnections] at once, [pairConnectionsPerAddress] per IP. */
    val pairing: ControlServerLimits
        get() =
            ControlServerLimits(
                maxUnauthenticatedConnections = pairConnections,
                maxUnauthenticatedPerAddress = pairConnectionsPerAddress,
            )
}

/**
 * Admission control in front of the session handshake:
 * - at most [ControlServerLimits.maxUnauthenticatedConnections] connections may be waiting for their handshake at
 *   once, and at most [ControlServerLimits.maxUnauthenticatedPerAddress] of them from one address (the next one is
 *   closed 4429, CONN-01 API 3 rule 2), so one silent host on the Wi-Fi cannot lock the paired clients out;
 * - an IP that sends a `session/hello` rejected with `AUTH_FAILED` [ControlServerLimits.authFailuresBeforeBlock]
 *   times within [ControlServerLimits.authFailureWindow] is blocked for [ControlServerLimits.ipBlockDuration];
 *   so is one with [ControlServerLimits.preHandshakeFailuresBeforeBlock] handshake timeouts, `PAIR_UNKNOWN` or
 *   `BAD_REQUEST` before the handshake within [ControlServerLimits.preHandshakeFailureWindow]: its connections are
 *   closed 4429 right after TLS (CONN-01 API 4 rule 2).
 *
 * `/v1/pair` uses a second instance with [ControlServerLimits.pairing]; its tickets live as long as the connection.
 * Thread-safe; state lives in memory only and is bounded by [MAX_TRACKED_ADDRESSES].
 */
class ConnectionAdmission(
    private val limits: ControlServerLimits = ControlServerLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One admitted connection; [release] once its handshake ends (success or not). Idempotent. */
    inner class Ticket internal constructor(
        private val address: String,
    ) {
        private var released = false

        fun release() =
            synchronized(this@ConnectionAdmission) {
                if (!released) {
                    released = true
                    pending--
                    val left = (pendingByAddress[address] ?: 1) - 1
                    if (left > 0) pendingByAddress[address] = left else pendingByAddress.remove(address)
                }
            }
    }

    private class AddressRecord {
        val authFailures = ArrayDeque<Long>()
        val preHandshakeFailures = ArrayDeque<Long>()
        var blockedUntil = 0L
    }

    private var pending = 0

    /** Pending connections per address; an entry exists only while its count is above zero (≤ the global cap). */
    private val pendingByAddress = HashMap<String, Int>()
    private val addresses = LinkedHashMap<String, AddressRecord>()

    /** A ticket, or `null` when the connection must be closed 4429 (a cap is reached or the IP is blocked). */
    @Synchronized
    fun admit(remoteAddress: String): Ticket? {
        val now = clock()
        val blocked = (addresses[remoteAddress]?.blockedUntil ?: 0L) > now
        val fromAddress = pendingByAddress[remoteAddress] ?: 0
        if (blocked ||
            pending >= limits.maxUnauthenticatedConnections ||
            fromAddress >= limits.maxUnauthenticatedPerAddress
        ) {
            return null
        }
        pending++
        pendingByAddress[remoteAddress] = fromAddress + 1
        return Ticket(remoteAddress)
    }

    /** Records a `session/hello` from [remoteAddress] rejected with `AUTH_FAILED`; may start a block. */
    @Synchronized
    fun recordAuthFailure(remoteAddress: String) =
        record(remoteAddress, limits.authFailuresBeforeBlock, limits.authFailureWindow) { it.authFailures }

    /** Records a handshake timeout (4408), `PAIR_UNKNOWN` or `BAD_REQUEST` before the handshake; may start a block. */
    @Synchronized
    fun recordPreHandshakeFailure(remoteAddress: String) =
        record(remoteAddress, limits.preHandshakeFailuresBeforeBlock, limits.preHandshakeFailureWindow) {
            it.preHandshakeFailures
        }

    @Synchronized
    fun isBlocked(remoteAddress: String): Boolean = (addresses[remoteAddress]?.blockedUntil ?: 0L) > clock()

    /** Number of admitted connections still in their handshake. */
    @get:Synchronized
    val pendingHandshakes: Int get() = pending

    private fun record(
        remoteAddress: String,
        threshold: Int,
        window: Duration,
        failures: (AddressRecord) -> ArrayDeque<Long>,
    ) {
        val now = clock()
        val record = addresses.remove(remoteAddress) ?: AddressRecord()
        addresses[remoteAddress] = record // re-insert: most recently used last
        val times = failures(record)
        val windowStart = now - window.inWholeMilliseconds
        while (times.isNotEmpty() && times.first() <= windowStart) times.removeFirst()
        times.addLast(now)
        if (times.size >= threshold) {
            record.blockedUntil = now + limits.ipBlockDuration.inWholeMilliseconds
            times.clear()
        }
        evictOldest(now)
    }

    private fun evictOldest(now: Long) {
        if (addresses.size <= MAX_TRACKED_ADDRESSES) return
        val iterator = addresses.entries.iterator()
        while (addresses.size > MAX_TRACKED_ADDRESSES && iterator.hasNext()) {
            // Drop idle, unblocked records first; they carry no state worth keeping.
            val entry = iterator.next()
            if (entry.value.blockedUntil <= now) iterator.remove()
        }
    }

    private companion object {
        /** A LAN never has this many hosts talking to one phone; the cap only bounds memory. */
        const val MAX_TRACKED_ADDRESSES = 256
    }
}
