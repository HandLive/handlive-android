package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettleGateTest {
    @Test
    fun reportsOnceAfterTheAddressIsStable() {
        val gate = SettleGate<String>(1_500)
        assertEquals(1_500L, gate.observe("a", 0))
        assertNull(gate.due(1_499))
        assertEquals(1_500L, gate.observe("a", 1_000)) // same page again: the clock does not restart
        assertEquals("a", gate.due(1_500))
        assertNull(gate.due(5_000))
        assertNull(gate.observe("a", 6_000)) // already reported
    }

    @Test
    fun aChangeRestartsTheClock() {
        val gate = SettleGate<String>(1_500)
        gate.observe("a", 0)
        assertEquals(2_500L, gate.observe("b", 1_000))
        assertNull(gate.due(1_600))
        assertEquals("b", gate.due(2_500))
    }

    @Test
    fun typingClearsTheCandidate() {
        val gate = SettleGate<String>(1_500)
        gate.observe("a", 0)
        assertNull(gate.observe(null, 1_000))
        assertNull(gate.due(2_000))
        gate.observe("a", 3_000)
        assertEquals("a", gate.due(4_500))
    }

    @Test
    fun resetLetsTheSamePageBeReportedAgain() {
        val gate = SettleGate<String>(1_500)
        gate.observe("a", 0)
        assertEquals("a", gate.due(1_500))
        gate.reset()
        assertEquals(3_500L, gate.observe("a", 2_000))
        assertEquals("a", gate.due(3_500))
    }
}
