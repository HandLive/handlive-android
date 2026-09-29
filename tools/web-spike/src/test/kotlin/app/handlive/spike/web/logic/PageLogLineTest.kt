package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageLogLineTest {
    @Test
    fun formatSkipsNullsAndCleansSpaces() {
        val line =
            PageLogLine.format(
                listOf("ev" to "active", "host" to "example.com", "reason" to "a b=c", "x" to null),
            )
        assertEquals("HLWEB ev=active host=example.com reason=a_b_c", line)
    }

    @Test
    fun parseReadsLogcatPrefixedLinesAndCsvKeepsColumnOrder() {
        val fields = PageLogLine.parse("09-28 10:00:00.000 I HLWEB: HLWEB ts=t ev=active browser=chrome host=a.com")!!
        assertEquals("chrome", fields["browser"])
        val row = PageLogLine.csvRow(fields)
        assertEquals(PageLogLine.columns.size, row.split(',').size)
        assertEquals("t,active,chrome,,,a.com", row.split(',').take(6).joinToString(","))
        assertNull(PageLogLine.parse("something else"))
    }

    @Test
    fun hashIsShortSaltedAndNeverTheAddress() {
        val url = "https://example.com/secret-path"
        val hash = PageLogLine.hash(url, "salt-1")
        assertEquals(12, hash.length)
        assertEquals(hash, PageLogLine.hash(url, "salt-1"))
        assertNotEquals(hash, PageLogLine.hash(url, "salt-2"))
        assertFalse(hash.contains("example"))
    }
}
