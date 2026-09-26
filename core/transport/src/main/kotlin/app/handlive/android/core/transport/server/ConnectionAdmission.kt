package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.TransportConstants
import kotlin.time.Duration

/** Resource limits of the `/v1/ctl` endpoint (CONN-01 API 3–4, CONN-02); tests shrink them. */
class ControlServerLimits(
    val maxUnauthenticatedConnections: Int = TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS,
    val authFailuresBeforeBlock: Int = TransportConstants.AUTH_FAILURES_BEFORE_BLOCK,
    val authFailureWindow: Duration = TransportConstants.AUTH_FAILURE_WINDOW,
    val ipBlockDuration: Duration = TransportConstants.IP_BLOCK_DURATION,
    val idleTimeout: Duration = TransportConstants.IDLE_TIMEOUT,
)

/**
 * Admission control in front of the session handshake:
 * - at most [ControlServerLimits.maxUnauthenticatedConnections] connections may be waiting for their handshake at
 *   once (the next one is closed 4429, CONN-01 API 3 rule 2);
 * - an IP that sends a `session/hello` rejected with `AUTH_FAILED` [ControlServerLimits.authFailuresBeforeBlock]
 *   times within [ControlServerLimits.authFailureWindow] is blocked for [ControlServerLimits.ipBlockDuration]:
 *   its connections are closed 4429 right after TLS (CONN-01 API 4 rule 2).
 *
 * Thread-safe; state lives in memory only and is bounded by [MAX_TRACKED_ADDRESSES].
 */
class ConnectionAdmission(
    private val limits: ControlServerLimits = ControlServerLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One admitted connection; [release] once its handshake ends (success or not). Idempotent. */
    inner class Ticket internal constructor() {
        private var released = false

        fun release() =
            synchronized(this@ConnectionAdmission) {
                if (!released) {
                    released = true
                    pending--
                }
            }
    }

    private class AddressRecord {
        val failures = ArrayDeque<Long>()
        var blockedUntil = 0L
    }

    private var pending = 0
    private val addresses = LinkedHashMap<String, AddressRecord>()

    /** A ticket, or `null` when the connection must be closed 4429 (too many pending handshakes or IP blocked). */
    @Synchronized
    fun admit(remoteAddress: String): Ticket? {
        val now = clock()
        val blocked = (addresses[remoteAddress]?.blockedUntil ?: 0L) > now
        if (blocked || pending >= limits.maxUnauthenticatedConnections) return null
        pending++
        return Ticket()
    }

    /** Records a `session/hello` from [remoteAddress] rejected with `AUTH_FAILED`; may start a block. */
    @Synchronized
    fun recordAuthFailure(remoteAddress: String) {
        val now = clock()
        val record = addresses.remove(remoteAddress) ?: AddressRecord()
        addresses[remoteAddress] = record // re-insert: most recently used last
        val windowStart = now - limits.authFailureWindow.inWholeMilliseconds
        while (record.failures.isNotEmpty() && record.failures.first() <= windowStart) record.failures.removeFirst()
        record.failures.addLast(now)
        if (record.failures.size >= limits.authFailuresBeforeBlock) {
            record.blockedUntil = now + limits.ipBlockDuration.inWholeMilliseconds
            record.failures.clear()
        }
        evictOldest(now)
    }

    @Synchronized
    fun isBlocked(remoteAddress: String): Boolean = (addresses[remoteAddress]?.blockedUntil ?: 0L) > clock()

    /** Number of admitted connections still in their handshake. */
    @get:Synchronized
    val pendingHandshakes: Int get() = pending

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
