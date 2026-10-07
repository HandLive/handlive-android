package app.handlive.android.core.protocol.clipboard

/**
 * The HTML sanitizer of a text clip's `html` (CLIP-01 API 5 `html`). Every platform runs the same algorithm; the
 * shared vectors `clipboard-html.json` pin its output byte for byte.
 *
 * Comments go (an unclosed `<!--` runs to the end), and so do `<!…>` and `<?…>` up to the next `>`. Tag and
 * attribute names are case-insensitive and output tags are lowercase. [DROP_CONTENT] tags go with everything up to
 * their matching close tag (or the end when unclosed). [KEEP] tags stay with their allowed attributes only, written
 * as ` name="value"` in a fixed order with `"`, `<` and `>` escaped; every other tag is unwrapped (tag gone,
 * content kept). `href` keeps `http`, `https` and `mailto`, `img src` keeps `http` and `https` and an `img` without
 * a kept `src` is dropped whole; `width`, `height`, `colspan` and `rowspan` keep ASCII digits only. Void tags never
 * close; text between tags is copied as is, except that a `<` followed by `/` or an ASCII letter that did not
 * complete a tag becomes `&lt;` (the receiving parser would close it at the next `>`).
 *
 * The scanner is hand written rather than a regular expression: a tag match over a 180 KiB attribute run would
 * overflow the stack of the JVM regex engine. Pure Kotlin, no Android classes.
 */
object HtmlClipSanitizer {
    private val DROP_CONTENT =
        setOf(
            "script",
            "style",
            "iframe",
            "object",
            "embed",
            "svg",
            "math",
            "template",
            "noscript",
            "head",
            "title",
            "textarea",
            "select",
            "button",
            "form",
            "input",
            "video",
            "audio",
            "canvas",
            "link",
            "meta",
            "base",
            "applet",
            "frame",
            "frameset",
        )
    private val KEEP =
        setOf(
            "a",
            "abbr",
            "b",
            "blockquote",
            "br",
            "caption",
            "code",
            "div",
            "em",
            "figcaption",
            "figure",
            "h1",
            "h2",
            "h3",
            "h4",
            "h5",
            "h6",
            "hr",
            "i",
            "img",
            "li",
            "ol",
            "p",
            "pre",
            "s",
            "span",
            "strong",
            "sub",
            "sup",
            "table",
            "tbody",
            "td",
            "tfoot",
            "th",
            "thead",
            "tr",
            "u",
            "ul",
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

    /** Whitespace as the reference reads it (ASCII `\\s`): space, TAB, LF, VT, FF and CR. */
    private const val SPACE_CLASS = " \\t\\n\\r\\u000b\\u000c"
    private val ATTR =
        Regex(
            "([A-Za-z_:][-A-Za-z0-9_:.]*)(?:[$SPACE_CLASS]*=[$SPACE_CLASS]*" +
                "(\"[^\"]*\"|'[^']*'|[^$SPACE_CLASS\"'=<>`]+))?",
        )

    /** [sanitize] for an optional `html`: `null` stays `null` and so does a result with nothing left to write. */
    fun sanitizeOrNull(html: String?): String? = html?.let(::sanitize)?.takeIf { it.isNotEmpty() }

    fun sanitize(html: String): String {
        val text = HtmlPrePasses.stripBogusComments(HtmlPrePasses.stripComments(html))
        val out = StringBuilder(text.length)
        // Only a text with a `<` can hold a tag: plain text needs no table of tag ends.
        val ends = if ('<' in text) HtmlTagScanner.tagEnds(text) else IntArray(0)
        var copyFrom = 0
        while (true) {
            val tag = if (ends.isEmpty()) null else HtmlTagScanner.nextTag(text, copyFrom, ends)
            if (tag == null) {
                HtmlTagScanner.appendText(out, text, copyFrom, text.length)
                return out.toString()
            }
            HtmlTagScanner.appendText(out, text, copyFrom, tag.start)
            copyFrom = tag.end
            if (tag.name in DROP_CONTENT) {
                if (!tag.closing) copyFrom = HtmlTagScanner.closeTagEnd(text, tag.name, tag.end) ?: text.length
            } else {
                out.append(render(tag))
            }
        }
    }

    /** Unwrapped tags (not kept) render as nothing: the tag goes, the text around it stays. */
    private fun render(tag: HtmlTagScanner.Tag): String =
        when {
            tag.name !in KEEP -> ""
            tag.closing -> if (tag.name in VOID) "" else "</${tag.name}>"
            else -> renderOpen(tag.name, tag.attrs).orEmpty()
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
                val value =
                    if (key in found ||
                        key !in allowed
                    ) {
                        null
                    } else {
                        keepValue(key, attributeValue(match.groups[2]?.value))
                    }
                if (value != null) found[key] = value
            }
        }
        if (name == "img" && "src" !in found) return null
        return buildString {
            append('<').append(name)
            for (key in allowed) {
                found[key]?.let {
                    append(' ')
                        .append(key)
                        .append("=\"")
                        .append(escape(it))
                        .append('"')
                }
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
            schemes != null -> {
                value.trim(HtmlTagScanner::isSpace).takeIf { v -> schemes.any { v.lowercase().startsWith(it) } }
            }

            key in DIGITS -> {
                value.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }
            }

            else -> {
                value
            }
        }
    }

    private fun attributeValue(raw: String?): String =
        when {
            raw == null -> ""
            raw.length >= 2 && raw[0] == raw.last() && raw[0] in "\"'" -> raw.substring(1, raw.length - 1)
            else -> raw
        }

    private fun escape(value: String): String = value.replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
}
