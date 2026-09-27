package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.encoding.Base64Codecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** `cursor` and `page_token` of SMS-01 API 1 logic 2 and 4: b64u JSON, version 1, readable or refused. */
class SyncTokensTest {
    @Test
    fun theCursorOfTheSpecExampleDecodes() {
        // eyJ2IjoxLCJpZCI6MTI4NDYsInQiOjE3MjcxNTAwMDAxMjN9 = {"v":1,"id":12846,"t":1727150000123}
        val cursor = SyncTokens.decodeCursor("eyJ2IjoxLCJpZCI6MTI4NDYsInQiOjE3MjcxNTAwMDAxMjN9")
        assertEquals(SyncCursor(12846, 1_727_150_000_123), cursor)
        assertEquals(
            "eyJ2IjoxLCJpZCI6MTI4NDYsInQiOjE3MjcxNTAwMDAxMjN9",
            SyncTokens.encode(SyncCursor(12846, 1_727_150_000_123)),
        )
    }

    @Test
    fun firstSyncAndCatchUpTokensRoundTripInTheirOwnKindOnly() {
        val first = PageToken.firstSync(snap = 12846, threads = listOf(42, 57, 63), offset = 20)
        val text = SyncTokens.encode(first)
        assertEquals("""{"v":1,"m":12846,"th":[42,57,63],"o":20}""", decoded(text))
        assertEquals(first, SyncTokens.decodePageToken(text, catchUp = false))
        assertNull("a first-sync token in a catch-up", SyncTokens.decodePageToken(text, catchUp = true))

        val catchUp = PageToken.catchUp(snap = 12900, after = 12850)
        val catchUpText = SyncTokens.encode(catchUp)
        assertEquals("""{"v":1,"m":12900,"a":12850}""", decoded(catchUpText))
        assertEquals(catchUp, SyncTokens.decodePageToken(catchUpText, catchUp = true))
        assertNull(SyncTokens.decodePageToken(catchUpText, catchUp = false))
    }

    @Test
    fun malformedOrOtherVersionsAreUnreadable() {
        listOf(
            "not base64!",
            b64u("not json"),
            b64u("""{"v":2,"id":1,"t":1}"""),
            b64u("""{"v":1,"id":1}"""),
            b64u("""{"v":1,"id":-1,"t":1}"""),
            b64u("""{"v":1,"id":"x","t":1}"""),
            // Padding is not canonical b64u.
            "eyJ2IjoxLCJpZCI6MSwidCI6MX0=",
        ).forEach { assertNull(it, SyncTokens.decodeCursor(it)) }
        listOf(
            b64u("""{"v":1,"m":5}"""),
            b64u("""{"v":1,"m":5,"th":[1],"o":-1}"""),
            b64u("""{"v":2,"m":5,"th":[1],"o":0}"""),
            b64u("""{"v":1,"m":5,"th":[1],"o":0,"a":3}"""),
        ).forEach { assertNull(it, SyncTokens.decodePageToken(it, catchUp = false)) }
    }

    private fun b64u(json: String) = Base64Codecs.encodeB64u(json.toByteArray())

    private fun decoded(text: String) = Base64Codecs.decodeB64u(text).toString(Charsets.UTF_8)
}
