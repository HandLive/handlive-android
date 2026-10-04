package app.handlive.android.core.protocol.clipboard

/**
 * The HTML sanitizer of a text clip's `html` (CLIP-01 API 5 `html`). Every platform runs the same algorithm; the
 * shared vectors `clipboard-html.json` pin its output byte for byte.
 *
 * Comments go. Tag and attribute names are case-insensitive and output tags are lowercase. [DROP_CONTENT] tags go
 * with everything up to their matching close tag (or the end when unclosed). [KEEP] tags stay with their allowed
 * attributes only, re-serialized ` name="value"` in a fixed order with `"`, `<` and `>` escaped; every other tag is
 * unwrapped (tag gone, content kept). `href` keeps `http`, `https` and `mailto`, `img src` keeps `http` and `https`
 * and an `img` without a kept `src` is dropped whole; `width`, `height`, `colspan` and `rowspan` keep ASCII digits
 * only. Void tags never close; text between tags is copied as is. A `<` that does not start a complete tag is text.
 *
 * The scanner is hand written rather than a regular expression: a tag match over a 180 KiB attribute run would
 * overflow the stack of the JVM regex engine. Pure Kotlin, no Android classes.
 */
object HtmlClipSanitizer {
    private val DROP_CONTENT =
        setOf(
            "script", "style", "iframe", "object", "embed", "svg", "math", "template", "noscript", "head", "title",
            "textarea", "select", "button", "form", "input", "video", "audio", "canvas", "link", "meta", "base",
            "applet", "frame", "frameset",
        )
    private val KEEP =
        setOf(
            "a", "abbr", "b", "blockquote", "br", "caption", "code", "div", "em", "figcaption", "figure", "h1", "h2",
            "h3", "h4", "h5", "h6", "hr", "i", "img", "li", "ol", "p", "pre", "s", "span", "strong", "sub", "sup",
            "table", "tbody", "td", "tfoot", "th", "thead", "tr", "u", "ul",
        )
    private val VOID = setOf("br", "hr", "img")
    private val ALLOWED_ATTRS =
        mapOf(
            "a" to listOf("href"),
            "img" to listOf("src", "alt", "width", "height"),
            "td" to listOf("colspan", "rowspan"),
            "th" to listOf("colspan", "rowspan"),
        )
    private val URL_SCHEMES =
        mapOf("href" to listOf("http:", "https:", "mailto:"), "src" to listOf("http:", "https:"))
    private val DIGITS = setOf("width", "height", "colspan", "rowspan")

    /** Whitespace as the reference reads it (`\s` and `str.strip`): ASCII, FS-US, NEL and the Unicode spaces. */
    private const val SPACE_CLASS =
        " \\t\\n\\r\\u000b\\u000c\\u001c-\\u001f\\u0085\\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000"
    private val ATTR =
        Regex(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)(?:[$SPACE_CLASS]*=[$SPACE_CLASS]*" +
                "(\"[^\"]*\"|'[^']*'|[^$SPACE_CLASS\"'=<>`]+))?",
        )

    private class Tag(
        val start: Int,
        val end: Int,
        val closing: Boolean,
        val name: String,
        val attrs: String,
    )

    fun sanitize(html: String): String {
        val text = stripComments(html)
        val out = StringBuilder(text.length)
        var copyFrom = 0
        while (true) {
            val tag = nextTag(text, copyFrom)
            if (tag == null) {
                out.append(text, copyFrom, text.length)
                return out.toString()
            }
            out.append(text, copyFrom, tag.start)
            copyFrom = tag.end
            if (tag.name in DROP_CONTENT) {
                if (!tag.closing) copyFrom = closeTagEnd(text, tag.name, tag.end) ?: text.length
            } else {
                out.append(render(tag))
            }
        }
    }

    /** Unwrapped tags (not kept) render as nothing: the tag goes, the text around it stays. */
    private fun render(tag: Tag): String =
        when {
            tag.name !in KEEP -> ""
            tag.closing -> if (tag.name in VOID) "" else "</${tag.name}>"
            else -> renderOpen(tag.name, tag.attrs).orEmpty()
        }

    private fun stripComments(html: String): String {
        var open = html.indexOf("<!--")
        if (open < 0) return html
        val out = StringBuilder(html.length)
        var from = 0
        while (open >= 0) {
            val close = html.indexOf("-->", open + COMMENT_OPEN)
            if (close < 0) break
            out.append(html, from, open)
            from = close + COMMENT_CLOSE
            open = html.indexOf("<!--", from)
        }
        return out.append(html, from, html.length).toString()
    }

    /** The first complete tag at or after [from]: `<`, optional `/`, a name, attributes (quotes may hold `>`), `>`. */
    private fun nextTag(
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

    private fun parseTag(
        text: String,
        start: Int,
    ): Tag? {
        var i = start + 1
        val closing = i < text.length && text[i] == '/'
        if (closing) i++
        if (i >= text.length || !isAsciiLetter(text[i])) return null
        val nameStart = i
        while (i < text.length && (isAsciiLetter(text[i]) || text[i] in '0'..'9')) i++
        val nameEnd = i
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '>' -> {
                    val name = text.substring(nameStart, nameEnd).lowercase()
                    return Tag(start, i + 1, closing, name, text.substring(nameEnd, i))
                }

                '"', '\'' -> {
                    val quoteEnd = text.indexOf(c, i + 1)
                    if (quoteEnd < 0) return null
                    i = quoteEnd + 1
                }

                else -> {
                    i++
                }
            }
        }
        return null
    }

    /** End of the first `</name` + whitespace + `>` at or after [from], case-insensitive; `null` when unclosed. */
    private fun closeTagEnd(
        text: String,
        name: String,
        from: Int,
    ): Int? {
        var at = text.indexOf("</", from)
        while (at >= 0) {
            val afterName = at + 2 + name.length
            if (text.regionMatches(at + 2, name, 0, name.length, ignoreCase = true)) {
                var i = afterName
                while (i < text.length && isSpace(text[i])) i++
                if (i < text.length && text[i] == '>') return i + 1
            }
            at = text.indexOf("</", at + 1)
        }
        return null
    }

    private fun renderOpen(
        name: String,
        attrs: String,
    ): String? {
        val allowed = ALLOWED_ATTRS[name].orEmpty()
        val found = HashMap<String, String>()
        if (allowed.isNotEmpty()) {
            for (match in ATTR.findAll(attrs)) {
                val key = match.groupValues[1].lowercase()
                if (key in found || key !in allowed) continue
                val value = keepValue(key, attributeValue(match.groups[2]?.value)) ?: continue
                found[key] = value
            }
        }
        if (name == "img" && "src" !in found) return null
        return buildString {
            append('<').append(name)
            for (key in allowed) {
                found[key]?.let { append(' ').append(key).append("=\"").append(escape(it)).append('"') }
            }
            append('>')
        }
    }

    /** The value to keep for [key], or `null` when the attribute goes. */
    private fun keepValue(
        key: String,
        value: String,
    ): String? {
        val schemes = URL_SCHEMES[key]
        return when {
            schemes != null -> value.trim(::isSpace).takeIf { v -> schemes.any { v.lowercase().startsWith(it) } }
            key in DIGITS -> value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            else -> value
        }
    }

    private fun attributeValue(raw: String?): String =
        when {
            raw == null -> ""
            raw.length >= 2 && raw[0] == raw.last() && raw[0] in "\"'" -> raw.substring(1, raw.length - 1)
            else -> raw
        }

    private fun escape(value: String): String = value.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")

    private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    private fun isSpace(c: Char) =
        c in '\u0009'..'\u000d' || c in '\u001c'..' ' || c == '\u0085' || c == ' ' || c == ' ' ||
            c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' ||
            c == '　'

    private const val COMMENT_OPEN = 4
    private const val COMMENT_CLOSE = 3
}
