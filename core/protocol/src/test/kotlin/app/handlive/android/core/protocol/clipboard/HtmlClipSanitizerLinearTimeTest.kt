package app.handlive.android.core.protocol.clipboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The sanitizer runs in linear time: a `<` + letter with no `>` after it, or attributes whose quote never closes, used
 * to be searched to the end of the input again from every `<` (160 KiB took seconds, 1 MiB minutes, while the
 * clipboard read held the screen). The table of tag ends must give, at every index, what the former search found.
 */
class HtmlClipSanitizerLinearTimeTest {
    @Test(timeout = HANG_MILLIS)
    fun aMebibyteOfTagStartsWithoutAnEndIsTextInTime() {
        val count = MEBIBYTE / 2
        val input = "<a".repeat(count)

        val output = timed("<a x ${input.length}") { HtmlClipSanitizer.sanitize(input) }

        assertEquals("&lt;a".repeat(count), output)
    }

    @Test(timeout = HANG_MILLIS)
    fun aMebibyteOfQuotedTagStartsBeforeAnUnclosedQuoteIsTextInTime() {
        // A `>` is still there at the end, but the `'` before it never closes: no tag start completes.
        val count = MEBIBYTE / 5
        val input = "<a\"x\"".repeat(count) + "'>"

        val output = timed("<a\"x\" x ${input.length} + '>") { HtmlClipSanitizer.sanitize(input) }

        assertEquals("&lt;a\"x\"".repeat(count) + "'>", output)
    }

    @Test
    fun smallInputsOfBothShapesComeOutAsText() {
        assertEquals("&lt;a&lt;a&lt;a", HtmlClipSanitizer.sanitize("<a<a<a"))
        assertEquals("&lt;a\"x\"&lt;a\"x\"'>", HtmlClipSanitizer.sanitize("<a\"x\"<a\"x\"'>"))
    }

    @Test
    fun theTableOfTagEndsMatchesTheFormerSearchAtEveryIndex() {
        val random = Random(SEED)
        repeat(CASES) {
            val text = String(CharArray(random.nextInt(0, MAX_LENGTH)) { ALPHABET[random.nextInt(ALPHABET.length)] })
            val ends = HtmlTagScanner.tagEnds(text)
            for (i in 0..text.length) assertEquals("at $i of <$text>", searchedTagEnd(text, i), ends[i])
        }
    }

    /** The former per-index search: a `>` ends the attributes, a quote skips past its closing quote (none: -1). */
    private fun searchedTagEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i in text.indices && text[i] != '>') i = pastChar(text, i)
        return if (i in text.indices) i else -1
    }

    private fun pastChar(
        text: String,
        at: Int,
    ): Int {
        val c = text[at]
        if (c != '"' && c != '\'') return at + 1
        val close = text.indexOf(c, at + 1)
        return if (close < 0) -1 else close + 1
    }

    /** Runs [block], prints how long it took (also when it passes) and fails past [LIMIT_MILLIS]. */
    private fun <T> timed(
        what: String,
        block: () -> T,
    ): T {
        val start = System.nanoTime()
        val result = block()
        val millis = (System.nanoTime() - start) / 1_000_000
        println("sanitize $what chars: $millis ms (limit $LIMIT_MILLIS ms)")
        assertTrue("$what took $millis ms", millis < LIMIT_MILLIS)
        return result
    }

    private companion object {
        const val MEBIBYTE = 1_048_576

        /** The acceptance limit for ~1 MiB; the linear scanner takes about a tenth of a second. */
        const val LIMIT_MILLIS = 5_000L

        /** A quadratic scanner fails here instead of holding the run for minutes. */
        const val HANG_MILLIS = 10_000L
        const val SEED = 20261007
        const val CASES = 5_000
        const val MAX_LENGTH = 40
        const val ALPHABET = "<>\"'a/ =b"
    }
}
