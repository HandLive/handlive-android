package app.handlive.spike.web.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {
    @Test
    fun hostOnlyBarTextBecomesHttpsOrigin() {
        val url = UrlNormalizer.normalize("en.wikipedia.org")!!
        assertEquals("https://en.wikipedia.org", url.url)
        assertEquals("en.wikipedia.org", url.host)
        assertTrue(url.hostOnly)
    }

    @Test
    fun bidiMarksAroundTheBarTextAreIgnored() {
        // Samsung Internet 30 puts U+200E (left-to-right mark) before the host it shows.
        val url = UrlNormalizer.normalize("\u200Eexample.com")!!
        assertEquals("https://example.com", url.url)
        assertEquals("example.com", url.host)
        assertTrue(url.hostOnly)
        assertEquals("https://example.com/a", UrlNormalizer.normalize("\u2066example.com/a\u2069\u200F")!!.url)
    }

    @Test
    fun barTextWithPathWithoutSchemeKeepsThePath() {
        val url = UrlNormalizer.normalize("example.com/docs/a?b=1#top")!!
        assertEquals("https://example.com/docs/a?b=1#top", url.url)
        assertFalse(url.hostOnly)
    }

    @Test
    fun fullAddressesKeepTheirSchemeAndFragment() {
        val url = UrlNormalizer.normalize("  http://Example.COM:8080/Path#frag ")!!
        assertEquals("http://example.com:8080/Path#frag", url.url)
        assertEquals("example.com", url.host)
        assertFalse(url.hostOnly)
        assertEquals("https://example.com/", UrlNormalizer.normalize("HTTPS://example.com/")!!.url)
    }

    @Test
    fun schemeShownTellsAnAssumedHttpsFromOneTheBarShowed() {
        // Chrome 91 hides both http:// and https:// in the idle bar: the https in the address is then a guess.
        assertFalse(UrlNormalizer.normalize("example.com/plain-http")!!.schemeShown)
        assertTrue(UrlNormalizer.normalize("http://example.com/plain-http")!!.schemeShown)
        assertTrue(UrlNormalizer.normalize("https://example.com/")!!.schemeShown)
    }

    @Test
    fun hostWithPortAndLocalhostAreNotMistakenForSchemes() {
        assertEquals("https://example.com:8443/x", UrlNormalizer.normalize("example.com:8443/x")!!.url)
        assertEquals("https://localhost:3000", UrlNormalizer.normalize("localhost:3000")!!.url)
        assertEquals("https://192.168.1.10/admin", UrlNormalizer.normalize("192.168.1.10/admin")!!.url)
    }

    @Test
    fun nonWebSchemesAreRejected() {
        listOf(
            "chrome://settings",
            "about:blank",
            "file:///sdcard/a.html",
            "javascript:alert(1)",
            "data:text/html,hi",
            "content://media/1",
            "intent://x#Intent;end",
            "ftp://example.com",
            "edge://newtab",
            "view-source:https://example.com",
        ).forEach { assertNull(it, UrlNormalizer.normalize(it)) }
    }

    @Test
    fun searchTermsAndJunkAreRejected() {
        listOf(
            "",
            "   ",
            null,
            "weather tomorrow",
            "news",
            "Search or type web address",
            "https://",
            "http://exa mple.com",
            "https://user:pw@example.com/",
            "https://example.com:99999/",
            "https://-bad-.com/",
            "https://999.1.1.1/",
            "https://[::1]/",
            "https://example.123/",
        ).forEach { assertNull(it.toString(), UrlNormalizer.normalize(it)) }
    }

    @Test
    fun internationalizedHostsBecomePunycode() {
        val url = UrlNormalizer.normalize("bücher.de/katalog")!!
        assertEquals("https://xn--bcher-kva.de/katalog", url.url)
        assertEquals("xn--bcher-kva.de", url.host)
        // Mixed-script look-alike (Cyrillic "а" in "аpple.com") is kept as punycode, never shown as "apple.com".
        assertEquals("xn--pple-43d.com", UrlNormalizer.normalize("https://аpple.com/")!!.host)
    }

    @Test
    fun addressesOverEightKibibytesAreRejected() {
        val base = "https://example.com/?q="
        val fits = base + "a".repeat(UrlNormalizer.MAX_URL_BYTES - base.length)
        assertEquals(fits, UrlNormalizer.normalize(fits)!!.url)
        assertNull(UrlNormalizer.normalize(fits + "a"))
    }
}
