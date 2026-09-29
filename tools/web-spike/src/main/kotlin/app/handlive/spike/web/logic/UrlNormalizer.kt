package app.handlive.spike.web.logic

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * A page address read from a browser's URL bar, ready to send (W2: `http`/`https` only, at most 8 KiB).
 *
 * @property url the address with an explicit scheme and the host in ASCII (punycode for IDNs).
 * @property host the host alone, lower case ASCII: the only part of the address the spike ever logs.
 * @property hostOnly true when the bar showed no scheme and no path (Chrome's simplified bar), so [url] is the
 *   origin only and the real page path is unknown — one of the questions gate G6 answers per browser.
 * @property schemeShown true when the bar showed the scheme; false when `https://` was assumed (Chrome 91 hides
 *   `http://` too, so a plain http page would be sent as https).
 */
data class NormalizedUrl(
    val url: String,
    val host: String,
    val hostOnly: Boolean,
    val schemeShown: Boolean,
)

/**
 * Turns the text of a URL bar into an address (WEB-01): adds `https://` when the bar shows the host only, converts
 * internationalized host names to ASCII, and rejects everything that is not a valid http/https address (search
 * terms, `chrome://` pages, `about:blank`, `file:`, `javascript:`, `data:` and similar).
 */
object UrlNormalizer {
    /** W2: the `url` field holds at most 8 KiB of UTF-8. */
    const val MAX_URL_BYTES = 8 * 1024

    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val hostLabel = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
    private val ipv4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

    fun normalize(barText: CharSequence?): NormalizedUrl? {
        val text = barText?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val scheme =
            schemePrefix
                .find(text)
                ?.groupValues
                ?.get(1)
                ?.lowercase(Locale.ROOT)
        val hasScheme = scheme != null && text.startsWith("$scheme:", ignoreCase = true) && !looksLikeHostPort(text)
        if (hasScheme && scheme != "http" && scheme != "https") return null
        val withScheme = if (hasScheme) text else "https://$text"
        val parsed = parse(withScheme) ?: return null
        val url = parsed.first
        if (url.toByteArray(Charsets.UTF_8).size > MAX_URL_BYTES) return null
        val hostOnly = !hasScheme && parsed.second
        return NormalizedUrl(url = url, host = parsed.third, hostOnly = hostOnly, schemeShown = hasScheme)
    }

    /** `example.com:8080/x` and `localhost:3000` have a colon but no scheme: a host and a port follow. */
    private fun looksLikeHostPort(text: String): Boolean {
        val afterColon = text.substringAfter(':', "")
        val port = afterColon.takeWhile { it != '/' && it != '?' && it != '#' }
        return port.isNotEmpty() && port.all { it.isDigit() }
    }

    /** Returns (address, has no path/query/fragment, ASCII host) or null when the text is not a valid address. */
    private fun parse(candidate: String): Triple<String, Boolean, String>? {
        val schemeEnd = candidate.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = candidate.substring(0, schemeEnd).lowercase(Locale.ROOT)
        val rest = candidate.substring(schemeEnd + 3)
        val authorityEnd =
            rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let {
                if (it <
                    0
                ) {
                    rest.length
                } else {
                    it
                }
            }
        val authority = rest.substring(0, authorityEnd)
        val tail = rest.substring(authorityEnd)
        if (authority.isEmpty() || authority.contains('@')) return null // no user info: a phishing classic
        val (rawHost, port) = splitPort(authority) ?: return null
        val host = asciiHost(rawHost) ?: return null
        val rebuilt =
            buildString {
                append(scheme).append("://").append(host)
                if (port != null) append(':').append(port)
                append(tail)
            }
        return try {
            URI(rebuilt) // syntax check only: throws on illegal characters in the path or query
            Triple(rebuilt, tail.isEmpty() || tail == "/", host)
        } catch (_: URISyntaxException) {
            null
        }
    }

    private fun splitPort(authority: String): Pair<String, Int?>? {
        if (authority.startsWith("[")) return null // IPv6 literals are out of the spike's scope
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return authority to null
        val port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        return authority.substring(0, colon) to port
    }

    /** Lower-case ASCII host; IDNs become punycode (the receivers' homograph defense starts here). */
    private fun asciiHost(raw: String): String? {
        val trimmed = raw.trimEnd('.')
        if (trimmed.isEmpty()) return null
        val ascii =
            try {
                IDN.toASCII(trimmed, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            } catch (_: IllegalArgumentException) {
                return null
            }
        if (ipv4.matches(ascii)) return ascii.takeIf { it.split('.').all { part -> part.toInt() <= 255 } }
        val labels = ascii.split('.')
        if (labels.size < 2 && ascii != "localhost") return null // "news" alone is a search term, not a host
        if (labels.any { !hostLabel.matches(it) }) return null
        if (labels.last().all { it.isDigit() }) return null
        return ascii
    }
}
