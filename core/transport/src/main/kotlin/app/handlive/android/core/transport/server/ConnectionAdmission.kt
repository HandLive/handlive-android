package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.TransportConstants
import kotlin.time.Duration

/** A block rule: this many failures from one address within [window] block it (CONN-01 API 4). */
class FailureRule(
    val failures: Int,
    val window: Duration,
)

/** Connections allowed at once, in total and from one address. */
class ConnectionCaps(
    val total: Int,
    val perAddress: Int,
)

/** Resource limits of the `/v1/ctl` endpoint (CONN-01 API 3–4, CONN-02) and of `/v1/pair` (PAIR-01 API 2). */
class ControlServerLimits(
    /** Connections still waiting for their handshake (`CTL_PREAUTH_LIMIT`: 16, 4 per IP). */
    val pending: ConnectionCaps =
        ConnectionCaps(
            TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS,
            TransportConstants.MAX_UNAUTHENTICATED_PER_ADDRESS,
        ),
    /** Wrong `mac`: 5 per minute (unchanged rule). */
    val authFailures: FailureRule =
        FailureRule(TransportConstants.AUTH_FAILURES_BEFORE_BLOCK, TransportConstants.AUTH_FAILURE_WINDOW),
    /** 4408, `PAIR_UNKNOWN`, `BAD_REQUEST` before the handshake (`CTL_IP_BLOCK`): 10 per 5 minutes. */
    val preHandshakeFailures: FailureRule =
        FailureRule(
            TransportConstants.PRE_HANDSHAKE_FAILURES_BEFORE_BLOCK,
            TransportConstants.PRE_HANDSHAKE_FAILURE_WINDOW,
        ),
    val ipBlockDuration: Duration = TransportConstants.IP_BLOCK_DURATION,
    val idleTimeout: Duration = TransportConstants.IDLE_TIMEOUT,
    /** `/v1/pair` (`PAIR_CONN_LIMIT`): 4 at once, 2 per IP, for the whole connection. */
    val pairConnections: ConnectionCaps =
        ConnectionCaps(TransportConstants.PAIR_CONNECTIONS, TransportConstants.PAIR_CONNECTIONS_PER_ADDRESS),
) {
    /** The limits of `/v1/pair`'s own admission. */
    val pairing: ControlServerLimits get() = ControlServerLimits(pending = pairConnections)
}

/**
 * Admission control in front of the session handshake:
 * - at most [ControlServerLimits.pending] connections may be waiting for their handshake at once, in total and from
 *   one address (the next one is closed 4429, CONN-01 API 3 rule 2), so one silent host on the Wi-Fi cannot lock the
 *   paired clients out;
 * - an IP whose `session/hello` is rejected with `AUTH_FAILED` as often as [ControlServerLimits.authFailures] says is
 *   blocked for [ControlServerLimits.ipBlockDuration]; so is one with as many handshake timeouts, `PAIR_UNKNOWN` or
 *   `BAD_REQUEST` before the handshake as [ControlServerLimits.preHandshakeFailures] says: its connections are
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
        if (blocked || pending >= limits.pending.total || fromAddress >= limits.pending.perAddress) {
            return null
        }
        pending++
        pendingByAddress[remoteAddress] = fromAddress + 1
        return Ticket(remoteAddress)
    }

    /** Records a `session/hello` from [remoteAddress] rejected with `AUTH_FAILED`; may start a block. */
    @Synchronized
    fun recordAuthFailure(remoteAddress: String) = record(remoteAddress, limits.authFailures) { it.authFailures }

    /** Records a handshake timeout (4408), `PAIR_UNKNOWN` or `BAD_REQUEST` before the handshake; may start a block. */
    @Synchronized
    fun recordPreHandshakeFailure(remoteAddress: String) =
        record(remoteAddress, limits.preHandshakeFailures) { it.preHandshakeFailures }

    /** An established session from [remoteAddress]: its pre-handshake failures no longer count (`CTL_IP_BLOCK`). */
    @Synchronized
    fun clearPreHandshakeFailures(remoteAddress: String) {
        addresses[remoteAddress]?.preHandshakeFailures?.clear()
    }

    @Synchronized
    fun isBlocked(remoteAddress: String): Boolean = (addresses[remoteAddress]?.blockedUntil ?: 0L) > clock()

    /** Number of admitted connections still in their handshake. */
    @get:Synchronized
    val pendingHandshakes: Int get() = pending

    private fun record(
        remoteAddress: String,
        rule: FailureRule,
        failures: (AddressRecord) -> ArrayDeque<Long>,
    ) {
        val now = clock()
        val record = addresses.remove(remoteAddress) ?: AddressRecord()
        addresses[remoteAddress] = record // re-insert: most recently used last
        val times = failures(record)
        val windowStart = now - rule.window.inWholeMilliseconds
        while (times.isNotEmpty() && times.first() <= windowStart) times.removeFirst()
        times.addLast(now)
        if (times.size >= rule.failures) {
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
