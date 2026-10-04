package app.handlive.android.core.protocol.clipboard

import app.handlive.android.core.protocol.testing.SharedTestVectors
import app.handlive.android.core.protocol.testing.objects
import app.handlive.android.core.protocol.testing.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `HtmlClipSanitizer` reproduces every case of `clipboard-html.json` byte for byte (CLIP-01 API 5 `html`). */
class HtmlClipSanitizerVectorTest {
    @Test
    fun everyVectorCaseMatchesItsOutput() {
        val cases = SharedTestVectors.file("clipboard-html.json").objects("cases")
        assertTrue("the file lists the cases", cases.size >= MIN_CASES)
        for (case in cases) {
            assertEquals(case.str("name"), case.str("output"), HtmlClipSanitizer.sanitize(case.str("input")))
        }
    }

    @Test
    fun sanitizingTwiceChangesNothing() {
        for (case in SharedTestVectors.file("clipboard-html.json").objects("cases")) {
            val once = HtmlClipSanitizer.sanitize(case.str("input"))
            assertEquals(case.str("name"), once, HtmlClipSanitizer.sanitize(once))
        }
    }

    @Test
    fun outputNeverHoldsScriptJavascriptOrEventAttributes() {
        val hostile =
            "<SCRIPT>x</SCRIPT><a HREF=' JavaScript:alert(1)' onclick=x>l</a><img src=x onerror=y>" +
                "<p onmouseover=\"z\">t</p>"
        val output = HtmlClipSanitizer.sanitize(hostile).lowercase()
        assertFalse(output.contains("<script"))
        assertFalse(output.contains("javascript:"))
        assertFalse(Regex("\\son[a-z]+=").containsMatchIn(output))
    }

    @Test
    fun unclosedQuoteAndStyleMutationInputsLeaveNoLiveImgOrScript() {
        val inputs =
            listOf(
                "<img src=x onerror=alert(1) \"<p>a</p>",
                "<p><style><img src=\"</style><img src=x onerror=alert(1)//\"></p>",
            )
        for (input in inputs) {
            val output = HtmlClipSanitizer.sanitize(input).lowercase()
            assertFalse(output, output.contains("<img"))
            assertFalse(output, output.contains("<script"))
            // `onerror=` may survive only as escaped text, never inside a tag.
            assertFalse(output, Regex("<[^>]*onerror=").containsMatchIn(output))
        }
    }

    @Test
    fun longAttributeRunsDoNotOverflowTheStack() {
        val long = "<a title=\"" + "x".repeat(LONG_RUN) + "\" href=\"https://e.com\">t</a>"
        assertEquals("<a href=\"https://e.com\">t</a>", HtmlClipSanitizer.sanitize(long))
        val unclosed = "<a " + "\"x ".repeat(LONG_RUN / 3)
        assertEquals("&lt;" + unclosed.substring(1), HtmlClipSanitizer.sanitize(unclosed))
    }

    private companion object {
        const val MIN_CASES = 41
        const val LONG_RUN = 200_000
    }
}
