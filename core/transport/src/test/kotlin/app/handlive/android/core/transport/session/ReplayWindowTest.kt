package app.handlive.android.core.transport.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

/** The ids and earlier acks of the key epoch (0.5.1 rule 2) and the byte bound of the stored acks. */
class ReplayWindowTest {
    private val window = ReplayWindow(maxIds = 100, maxAckBytes = 10)

    private fun accepted(withPrevious: Boolean = false) = UUID.randomUUID().also { window.record(it, withPrevious) }

    @Test
    fun theOldestAcksGoFirstWhenTheBytesRunOut() {
        val a = accepted()
        val b = accepted()
        val c = accepted()
        window.recordAck(a, ByteArray(4) { 1 })
        window.recordAck(b, ByteArray(4) { 2 })
        window.recordAck(c, ByteArray(4) { 3 })
        assertNull(window.record(a, false).earlierAck)
        assertArrayEquals(ByteArray(4) { 2 }, window.record(b, false).earlierAck)
        assertArrayEquals(ByteArray(4) { 3 }, window.record(c, false).earlierAck)
        assertEquals(8L, window.storedAckBytes)
        assertEquals(2, window.storedAcks)
    }

    @Test
    fun rotatingAndDroppingThePreviousEpochReleaseItsAcks() {
        repeat(3) { round ->
            val id = accepted()
            window.recordAck(id, ByteArray(3))
            window.rotate()
            // The acks of the epoch that just became the previous one are still kept.
            assertEquals(3L, window.storedAckBytes)
            window.dropPrevious()
            assertEquals("round $round", 0L, window.storedAckBytes)
            assertEquals("round $round", 0, window.storedAcks)
        }
        // A rotation that drops an older epoch releases its acks too.
        window.recordAck(accepted(), ByteArray(2))
        window.rotate()
        window.recordAck(accepted(), ByteArray(2))
        window.rotate()
        assertEquals(2L, window.storedAckBytes)
        assertEquals(1, window.storedAcks)
    }

    @Test
    fun anIdInBothEpochsKeepsTheCurrentAckWhenThePreviousGoes() {
        val id = accepted()
        window.recordAck(id, ByteArray(2) { 1 })
        window.rotate()
        // A retry of the same id under the new key is recorded in the new epoch as well.
        window.record(id, withPrevious = false)
        window.dropPrevious()
        assertEquals(0L, window.storedAckBytes)
        window.recordAck(id, ByteArray(2) { 2 })
        window.rotate()
        window.dropPrevious()
        assertEquals(0, window.storedAcks)
    }
}
