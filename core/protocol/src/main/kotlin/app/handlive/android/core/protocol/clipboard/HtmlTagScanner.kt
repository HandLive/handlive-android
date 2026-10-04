package app.handlive.android.core.protocol.clipboard

/**
 * The hand-written scanner under [HtmlClipSanitizer]: comments, complete tags and close tags. A regular expression
 * over a 180 KiB attribute run would overflow the stack of the JVM regex engine, so the tag grammar of the reference
 * (`<`, optional `/`, a name, attributes where a quoted value may hold `>`, `>`) is walked by index. A `<` that does
 * not start a complete tag is text ([appendText] escapes the ones that look like a tag start).
 */
internal object HtmlTagScanner {
    /** One complete tag: [start] until [end] (exclusive), its lowercase [name] and the raw [attrs] text. */
    class Tag(
        val start: Int,
        val end: Int,
        val closing: Boolean,
        val name: String,
        val attrs: String,
    )

    /**
     * Appends the text segment `[from, to)` of [text]: copied as is, except that a `<` followed by `/` or an ASCII
     * letter (it did not complete a tag) becomes `&lt;`, so the receiving parser cannot close it at a later `>`.
     */
    fun appendText(
        out: StringBuilder,
        text: String,
        from: Int,
        to: Int,
    ) {
        var copied = from
        var at = text.indexOf('<', from)
        while (at in from until to) {
            val next = if (at + 1 < to) text[at + 1] else ' '
            if (next == '/' || isAsciiLetter(next)) {
                out.append(text, copied, at).append("&lt;")
                copied = at + 1
            }
            at = text.indexOf('<', at + 1)
        }
        out.append(text, copied, to)
    }

    /** The first complete tag at or after [from], or `null`. */
    fun nextTag(
        text: String,
        from: Int,
    ): Tag? {
        var start = text.indexOf('<', from)
        while (start >= 0) {
            parseTag(text, start)?.let { return it }
            start = text.indexOf('<', start + 1)
        }
        return null
    }

    /** End of the first `</name` + whitespace + `>` at or after [from], ASCII case-insensitive; `null` if unclosed. */
    fun closeTagEnd(
        text: String,
        name: String,
        from: Int,
    ): Int? {
        var at = text.indexOf("</", from)
        while (at >= 0) {
            closeEndAt(text, name, at)?.let { return it }
            at = text.indexOf("</", at + 1)
        }
        return null
    }

    /** Whitespace of the reference's ASCII `\s`: space, TAB, LF, VT, FF, CR; never NBSP or Unicode spaces. */
    fun isSpace(c: Char) = c == ' ' || c in '\u0009'..'\u000d'

    private fun closeEndAt(
        text: String,
        name: String,
        at: Int,
    ): Int? {
        if (!matchesAsciiIgnoreCase(text, at + 2, name)) return null
        var i = at + 2 + name.length
        while (i < text.length && isSpace(text[i])) i++
        return if (i < text.length && text[i] == '>') i + 1 else null
    }

    /** [name] (lowercase ASCII) at [at]; folds ASCII letters only, so U+017F and the Kelvin sign never match. */
    private fun matchesAsciiIgnoreCase(
        text: String,
        at: Int,
        name: String,
    ): Boolean =
        at + name.length <= text.length &&
            name.indices.all { k ->
                val c = text[at + k]
                (if (c in 'A'..'Z') c + ASCII_CASE_GAP else c) == name[k]
            }

    private fun parseTag(
        text: String,
        start: Int,
    ): Tag? {
        val closing = start + 1 < text.length && text[start + 1] == '/'
        val nameStart = if (closing) start + 2 else start + 1
        if (nameStart >= text.length || !isAsciiLetter(text[nameStart])) return null
        var nameEnd = nameStart
        while (nameEnd < text.length && (isAsciiLetter(text[nameEnd]) || text[nameEnd] in '0'..'9')) nameEnd++
        val end = tagEnd(text, nameEnd)
        return if (end < 0) {
            null
        } else {
            Tag(start, end + 1, closing, text.substring(nameStart, nameEnd).lowercase(), text.substring(nameEnd, end))
        }
    }

    /** Index of the `>` that ends the tag whose attributes begin at [from]; quoted values may hold `>`; -1 if none. */
    private fun tagEnd(
        text: String,
        from: Int,
    ): Int {
        var i = from
        while (i in text.indices &&
            text[i] != '>'
        ) {
            i = if (text[i] == '"' || text[i] == '\'') pastQuote(text, i) else i + 1
        }
        return if (i in text.indices) i else -1
    }

    /** Index after the quote that closes the one at [open]; -1 when it never closes (the tag is not a tag). */
    private fun pastQuote(
        text: String,
        open: Int,
    ): Int = text.indexOf(text[open], open + 1).let { if (it < 0) -1 else it + 1 }

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private const val ASCII_CASE_GAP = 'a' - 'A'
}

/**
 * The two pre-passes that run before tag scanning, comments first: `<!-- … -->` (an unclosed one runs to the end of
 * the input, as in HTML) and the HTML "bogus comments" `<!…>` (doctype, CDATA) and `<?…>`, dropped up to and
 * including the next `>`, or to the end when there is none.
 */
internal object HtmlPrePasses {
    fun stripComments(html: String): String =
        strip(html, "-->", COMMENT_OPEN) { text, from -> text.indexOf("<!--", from) }

    fun stripBogusComments(html: String): String = strip(html, ">", BOGUS_OPEN, ::nextBogusStart)

    private fun strip(
        html: String,
        closer: String,
        openerLength: Int,
        nextOpen: (String, Int) -> Int,
    ): String {
        var open = nextOpen(html, 0)
        if (open < 0) return html
        val out = StringBuilder(html.length)
        var from = 0
        while (open >= 0) {
            out.append(html, from, open)
            val close = html.indexOf(closer, open + openerLength)
            from = if (close < 0) html.length else close + closer.length
            open = if (close < 0) -1 else nextOpen(html, from)
        }
        return out.append(html, from, html.length).toString()
    }

    private fun nextBogusStart(
        html: String,
        from: Int,
    ): Int {
        var at = html.indexOf('<', from)
        while (at >= 0 && html.getOrNull(at + 1) !in BOGUS_MARKS) at = html.indexOf('<', at + 1)
        return at
    }

    private val BOGUS_MARKS = setOf('!', '?')
    private const val COMMENT_OPEN = 4
    private const val BOGUS_OPEN = 2
}
