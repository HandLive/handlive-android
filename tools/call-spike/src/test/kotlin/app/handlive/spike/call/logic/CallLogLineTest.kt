package app.handlive.spike.call.logic

import org.junit.Assert.assertEquals
import org.junit.Test

class CallLogLineTest {
    @Test
    fun `fields are written in order as key=value`() {
        val line = CallLogLine.format(listOf("ev" to "posted", "pkg" to "org.telegram.messenger", "call_type" to 1))

        assertEquals("HLCALL ev=posted pkg=org.telegram.messenger call_type=1", line)
    }

    @Test
    fun `null fields are left out`() {
        assertEquals("HLCALL ev=removed", CallLogLine.format(listOf("ev" to "removed", "reason" to null)))
    }

    @Test
    fun `values with spaces or quotes are quoted and escaped`() {
        val line = CallLogLine.format(listOf("actions" to listOf("End call", "Say \"hi\"")))

        assertEquals("HLCALL actions=\"End call|Say \\\"hi\\\"\"", line)
    }

    @Test
    fun `booleans are written as 1 and 0`() {
        assertEquals("HLCALL answer=1 decline=0", CallLogLine.format(listOf("answer" to true, "decline" to false)))
    }
}
