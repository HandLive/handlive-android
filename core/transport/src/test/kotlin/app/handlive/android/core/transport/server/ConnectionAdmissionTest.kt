package app.handlive.android.core.transport.server

import app.handlive.android.core.transport.TransportConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Limits of CONN-01 API 3–4 with the spec values: 16 pending handshakes, 5 wrong `mac`/minute → 5-minute block. */
class ConnectionAdmissionTest {
    private var now = 1_000_000L
    private val admission = ConnectionAdmission(ControlServerLimits(), clock = { now })

    @Test
    fun sixteenHandshakesMayBePendingAndTheSeventeenthIsRefused() {
        val tickets = (1..TransportConstants.MAX_UNAUTHENTICATED_CONNECTIONS).map { admission.admit(IP_A) }
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

    private companion object {
        const val IP_A = "192.168.1.50"
        const val IP_B = "192.168.1.51"
    }
}
