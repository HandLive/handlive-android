package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.TransportConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Limits of CONN-01 API 3–4 and PAIR-01 API 2 with the spec values: 16 pending handshakes and 4 per IP, 5 wrong
 * `mac`/minute or 10 pre-handshake failures/5 minutes → 5-minute block; `/v1/pair` 4 at once and 2 per IP.
 */
class ConnectionAdmissionTest {
    private var now = 1_000_000L
    private val admission = ConnectionAdmission(ControlServerLimits(), clock = { now })

    @Test
    fun sixteenHandshakesMayBePendingAndTheSeventeenthIsRefused() {
        val tickets = (1..TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS).map { admission.admit(ip(it % 8)) }
        assertTrue(tickets.all { it != null })
        assertEquals(16, admission.pendingHandshakes)
        assertNull(admission.admit(IP_B))

        tickets.first()!!.release()
        tickets.first()!!.release() // idempotent: frees one slot only
        assertEquals(15, admission.pendingHandshakes)
        assertNotNull(admission.admit(IP_B))
        assertNull(admission.admit(IP_B))
    }

    @Test
    fun oneAddressMayHoldAtMostFourPendingHandshakes() {
        val tickets = (1..TransportConstants.MAX_UNAUTHENTICATED_PER_ADDRESS).map { admission.admit(IP_A) }
        assertTrue(tickets.all { it != null })
        assertNull(admission.admit(IP_A))
        // Other clients still get in: one silent host cannot lock the paired devices out.
        assertNotNull(admission.admit(IP_B))
        tickets.first()!!.release()
        assertNotNull(admission.admit(IP_A))
        assertNull(admission.admit(IP_A))
    }

    @Test
    fun tenPreHandshakeFailuresWithinFiveMinutesBlockTheAddressForFiveMinutes() {
        repeat(TransportConstants.PRE_HANDSHAKE_FAILURES_BEFORE_BLOCK - 1) {
            admission.recordPreHandshakeFailure(IP_A)
            now += 25_000
        }
        assertFalse(admission.isBlocked(IP_A))
        admission.recordPreHandshakeFailure(IP_A)
        assertTrue(admission.isBlocked(IP_A))
        assertNull(admission.admit(IP_A))
        assertNotNull(admission.admit(IP_B))
        now += TransportConstants.IP_BLOCK_DURATION.inWholeMilliseconds
        assertNotNull(admission.admit(IP_A))
    }

    @Test
    fun preHandshakeFailuresOlderThanFiveMinutesDoNotCount() {
        repeat(TransportConstants.PRE_HANDSHAKE_FAILURES_BEFORE_BLOCK - 1) { admission.recordPreHandshakeFailure(IP_A) }
        now += TransportConstants.PRE_HANDSHAKE_FAILURE_WINDOW.inWholeMilliseconds + 1
        admission.recordPreHandshakeFailure(IP_A)
        assertFalse(admission.isBlocked(IP_A))
        // Pre-handshake failures and wrong macs are counted apart.
        repeat(TransportConstants.AUTH_FAILURES_BEFORE_BLOCK - 1) { admission.recordAuthFailure(IP_A) }
        assertFalse(admission.isBlocked(IP_A))
    }

    @Test
    fun thePairingEndpointAdmitsFourAtOnceAndTwoPerAddress() {
        val pairing = ConnectionAdmission(ControlServerLimits().pairing, clock = { now })
        assertNotNull(pairing.admit(IP_A))
        val second = pairing.admit(IP_A)
        assertNotNull(second)
        assertNull(pairing.admit(IP_A))
        assertNotNull(pairing.admit(IP_B))
        assertNotNull(pairing.admit(ip(3)))
        assertNull(pairing.admit(ip(4)))
        second!!.release()
        assertNotNull(pairing.admit(IP_A))
    }

    @Test
    fun fiveWrongMacsWithinAMinuteBlockTheAddressForFiveMinutes() {
        repeat(4) {
            admission.recordAuthFailure(IP_A)
            now += 10_000
        }
        assertFalse(admission.isBlocked(IP_A))
        admission.recordAuthFailure(IP_A)
        assertTrue(admission.isBlocked(IP_A))
        assertNull(admission.admit(IP_A))
        // Other addresses are not affected.
        assertNotNull(admission.admit(IP_B))

        now += TransportConstants.IP_BLOCK_DURATION.inWholeMilliseconds - 1
        assertNull(admission.admit(IP_A))
        now += 1
        assertNotNull(admission.admit(IP_A))
    }

    @Test
    fun failuresOlderThanTheWindowDoNotCount() {
        repeat(4) { admission.recordAuthFailure(IP_A) }
        now += TransportConstants.AUTH_FAILURE_WINDOW.inWholeMilliseconds + 1
        admission.recordAuthFailure(IP_A)
        assertFalse(admission.isBlocked(IP_A))
        repeat(3) { admission.recordAuthFailure(IP_A) }
        assertFalse(admission.isBlocked(IP_A))
        admission.recordAuthFailure(IP_A)
        assertTrue(admission.isBlocked(IP_A))
    }

    @Test
    fun trackingManyAddressesKeepsBlockedOnes() {
        repeat(TransportConstants.AUTH_FAILURES_BEFORE_BLOCK) { admission.recordAuthFailure(IP_A) }
        repeat(1_000) { admission.recordAuthFailure("10.0.${it / 250}.${it % 250}") }
        assertTrue(admission.isBlocked(IP_A))
    }

    private fun ip(n: Int) = "192.168.1.${100 + n}"

    private companion object {
        const val IP_A = "192.168.1.50"
        const val IP_B = "192.168.1.51"
    }
}
