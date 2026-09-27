package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.sms.SmsBox
import app.handlive.android.core.protocol.sms.SmsSyncResponse
import app.handlive.android.core.protocol.sms.SmsUnreadEntry
import app.handlive.android.feature.sms.provider.SmsRow
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.FakeContacts
import app.handlive.android.feature.sms.testing.FakeSmsProvider
import app.handlive.android.feature.sms.testing.objectsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMS-01 on the phone: first sync and catch-up pages, snapshot mark, cursor, `page_token`, `unread` (API 1). */
class SmsSyncEngineTest {
    private val provider = FakeSmsProvider()

    private fun engine(
        pageMax: Int = 500,
        pageMaxBytes: Int = 180 * 1024,
        contacts: FakeContacts? = null,
    ) = SmsSyncEngine(provider, objectsOf(provider, contacts), pageMax, pageMaxBytes)

    private fun first(
        engine: SmsSyncEngine,
        token: String? = null,
        threadLimit: Int = 200,
        perThread: Int = 50,
    ) = engine.page(
        SyncPageRequest(threadLimit, perThread, null, token?.let { SyncTokens.decodePageToken(it, catchUp = false) }),
    )

    private fun catchUp(
        engine: SmsSyncEngine,
        cursor: String,
        token: String? = null,
    ) = engine.page(
        SyncPageRequest(
            200,
            50,
            SyncTokens.decodeCursor(cursor),
            token?.let { SyncTokens.decodePageToken(it, catchUp = true) },
        ),
    )

    /** Three conversations, newest first: 42 (4 SMS), 57 (3), 63 (2). */
    private fun threeConversations() {
        provider.conversation(63, "+84900000063")
        provider.conversation(57, "+84900000057")
        provider.conversation(42, "+84900000042")
        (1..2).forEach { provider.add(63, BASE_TS + it) }
        (1..3).forEach { provider.add(57, BASE_TS + 100 + it) }
        (1..4).forEach { provider.add(42, BASE_TS + 200 + it) }
    }

    @Test
    fun aFirstSyncSpansPagesAndEachThreadTravelsWithItsFirstMessage() {
        threeConversations()
        val engine = engine(pageMax = 4)

        val page1 = first(engine, perThread = 3)
        assertEquals(listOf(42L, 57L), page1.threads.map { it.threadId })
        assertEquals(listOf("sms:9", "sms:8", "sms:7", "sms:5"), page1.messages.map { it.messageKey })
        assertTrue(page1.hasMore)
        assertNull("unread only on the last page", page1.unread)
        assertEquals("""{"v":1,"m":9,"th":[57,63],"o":1}""", decode(page1.pageToken!!))

        val page2 = first(engine, page1.pageToken, perThread = 3)
        assertEquals("57 was already announced", listOf(63L), page2.threads.map { it.threadId })
        assertEquals(listOf("sms:4", "sms:3", "sms:2", "sms:1"), page2.messages.map { it.messageKey })
        assertFalse(page2.hasMore)
        assertNull(page2.pageToken)
        assertEquals(page1.cursor, page2.cursor)
        assertEquals("""{"v":1,"id":9,"t":${BASE_TS + 204}}""", decode(page2.cursor))
    }

    @Test
    fun messagesArrivingDuringASyncStayAboveTheSnapshotMark() {
        threeConversations()
        val engine = engine(pageMax = 4)
        val page1 = first(engine, perThread = 3)
        provider.add(42, BASE_TS + 999, body = "new")

        val page2 = first(engine, page1.pageToken, perThread = 3)
        assertTrue(page2.messages.none { it.messageKey == "sms:10" })
        assertEquals("""{"v":1,"id":9,"t":${BASE_TS + 204}}""", decode(page2.cursor))
        // The next catch-up covers it (SMS-01 API 1 logic 3).
        val next = catchUp(engine(), page2.cursor)
        assertEquals(listOf("sms:10"), next.messages.map { it.messageKey })
    }

    @Test
    fun threadAndPerThreadLimitsKeepTheNewestAndMmsOnlyConversationsAreSkipped() {
        threeConversations()
        provider.conversation(77, "+84900000077", date = BASE_TS + 500)
        val page = first(engine(), threadLimit = 3, perThread = 2)
        // 77 (MMS only) is one of the three newest conversations: skipped, so only two remain (logic 5).
        assertEquals(listOf(42L, 57L), page.threads.map { it.threadId })
        assertEquals(listOf("sms:9", "sms:8", "sms:5", "sms:4"), page.messages.map { it.messageKey })
        assertFalse(page.hasMore)
    }

    @Test
    fun theThreadObjectCarriesNormalizedAddressesNamesSnippetAndUnreadCount() {
        provider.conversation(42, "0900000123")
        provider.conversation(57, "VIETTEL")
        provider.conversation(63, "+84900000123", "0911111111")
        provider.add(42, BASE_TS + 1, read = false)
        provider.add(42, BASE_TS + 2, body = "x".repeat(170) + "😀", read = false)
        provider.add(57, BASE_TS + 3)
        provider.add(63, BASE_TS + 4)
        val contacts = FakeContacts(mapOf("+84900000123" to "Nguyễn Văn A"))
        val threads = first(engine(contacts = contacts)).threads.associateBy { it.threadId }

        val a = threads.getValue(42)
        assertEquals(listOf("+84900000123"), a.addresses)
        assertEquals("Nguyễn Văn A", a.displayName)
        assertEquals(160, a.snippet.codePointCount(0, a.snippet.length))
        assertEquals(BASE_TS + 2, a.lastTs)
        assertEquals(2, a.unreadCount)
        assertEquals(listOf("VIETTEL"), threads.getValue(57).addresses)
        assertNull(threads.getValue(57).displayName)
        assertEquals("Nguyễn Văn A, +84911111111", threads.getValue(63).displayName)
        assertEquals("names are cached per address for the page", 3, contacts.lookups)

        val withoutContacts = first(engine()).threads.associateBy { it.threadId }
        assertNull("SMS-01 E3", withoutContacts.getValue(42).displayName)
    }

    @Test
    fun theMessageObjectMapsTheProviderColumns() {
        provider.conversation(42, "+84900000123")
        provider.put(SmsRow(1, 42, "+84900000123", "tin", SmsType.SENT, BASE_TS + 1, 0, true, -1))
        provider.put(SmsRow(2, 42, "+84900000123", "tin", SmsType.INBOX, BASE_TS + 2, BASE_TS, false, 2))
        provider.add(42, BASE_TS + 3, type = SmsType.DRAFT)
        provider.add(42, BASE_TS + 4, type = SmsType.OUTBOX)
        provider.add(42, BASE_TS + 5, type = SmsType.FAILED)
        provider.add(42, BASE_TS + 6, type = SmsType.QUEUED)
        provider.update(1) { it.copy(body = null) }
        val messages = first(engine()).messages.associateBy { it.messageKey }

        assertEquals(setOf("sms:1", "sms:2", "sms:4", "sms:5", "sms:6"), messages.keys)
        val sent = messages.getValue("sms:1")
        assertEquals(SmsBox.SENT, sent.box)
        assertEquals("", sent.body)
        assertNull(sent.tsSent)
        assertNull(sent.subId)
        val inbox = messages.getValue("sms:2")
        assertEquals(SmsBox.INBOX, inbox.box)
        assertEquals(BASE_TS, inbox.tsSent)
        assertEquals(2, inbox.subId)
        assertFalse(inbox.read)
        assertEquals(
            listOf(SmsBox.OUTBOX, SmsBox.FAILED, SmsBox.QUEUED),
            listOf(4, 5, 6).map {
                messages.getValue("sms:$it").box
            },
        )
    }

    @Test
    fun aCatchUpSendsEveryMessageAfterTheCursorInIdOrderWithSummaries() {
        threeConversations()
        val cursor = first(engine()).cursor
        provider.add(57, BASE_TS + 300, read = false)
        provider.add(42, BASE_TS + 301)
        provider.add(57, BASE_TS + 302, read = false)

        val page = catchUp(engine(), cursor)
        assertEquals(listOf("sms:10", "sms:11", "sms:12"), page.messages.map { it.messageKey })
        assertEquals(setOf(42L, 57L), page.threads.map { it.threadId }.toSet())
        assertEquals(2, page.threads.single { it.threadId == 57L }.unreadCount)
        assertFalse(page.hasMore)
        assertEquals(listOf(SmsUnreadEntry(57, 2, BASE_TS + 299)), page.unread)
        assertEquals("""{"v":1,"id":12,"t":${BASE_TS + 302}}""", decode(page.cursor))
        assertEquals(emptyList<String>(), catchUp(engine(), page.cursor).messages.map { it.messageKey })
    }

    @Test
    fun aCatchUpFindsANewMessageThatReusedTheIdOfADeletedOne() {
        threeConversations()
        val cursor = first(engine()).cursor
        // The newest message (9) is deleted, a new one gets _id 9 again with a later date (logic 2).
        provider.delete(9)
        provider.put(SmsRow(9, 42, "+84900000042", "again", SmsType.INBOX, BASE_TS + 400, 0, true, 1))
        val page = catchUp(engine(), cursor)
        assertEquals(listOf("again"), page.messages.map { it.body })
    }

    @Test
    fun aCatchUpPagesWithTheLastIdSent() {
        threeConversations()
        val cursor = first(engine()).cursor
        (1..5).forEach { provider.add(63, BASE_TS + 500 + it) }
        val engine = engine(pageMax = 2)

        val page1 = catchUp(engine, cursor)
        assertEquals(listOf("sms:10", "sms:11"), page1.messages.map { it.messageKey })
        assertEquals("""{"v":1,"m":14,"a":11}""", decode(page1.pageToken!!))
        val page2 = catchUp(engine, cursor, page1.pageToken)
        assertEquals(listOf("sms:12", "sms:13"), page2.messages.map { it.messageKey })
        val page3 = catchUp(engine, cursor, page2.pageToken)
        assertEquals(listOf("sms:14"), page3.messages.map { it.messageKey })
        assertFalse(page3.hasMore)
        assertEquals(listOf(page1.cursor, page2.cursor), listOf(page2.cursor, page3.cursor))
    }

    @Test
    fun aPageStopsAtThePlaintextBudgetButAlwaysHoldsOneMessage() {
        provider.conversation(42, "+84900000123")
        (1..6).forEach { provider.add(42, BASE_TS + it, body = "ă".repeat(900)) }
        val budget = 4_000
        val engine = engine(pageMaxBytes = budget)
        var token: String? = null
        val pages = mutableListOf<SmsSyncResponse>()
        do {
            val page = first(engine, token)
            pages += page
            token = page.pageToken
        } while (page.hasMore)

        assertEquals(6, pages.sumOf { it.messages.size })
        assertTrue(pages.size >= 3)
        pages.forEach { page ->
            val size = ProtocolJson.encodeToString(SmsSyncResponse.serializer(), page).toByteArray().size
            assertTrue("page of $size bytes", size + PageBudget.ACK_WRAPPER_BYTES <= budget)
        }
        val tiny = first(engine(pageMaxBytes = 10))
        assertEquals("a single message always fits in one page (logic 7)", 1, tiny.messages.size)
    }

    @Test
    fun theLastPageListsEveryConversationWithUnreadMessages() {
        threeConversations()
        provider.update(3) { it.copy(read = false) }
        provider.update(4) { it.copy(read = false) }
        provider.update(9) { it.copy(read = false) }
        provider.add(57, BASE_TS + 110, type = SmsType.SENT, read = false)
        val page = first(engine())
        assertEquals(
            listOf(SmsUnreadEntry(42, 1, BASE_TS + 203), SmsUnreadEntry(57, 2, BASE_TS + 100)),
            page.unread,
        )
    }

    @Test
    fun anEmptyPhoneSyncsToAnEmptyLastPage() {
        val page = first(engine())
        assertEquals(emptyList<Any>(), page.messages)
        assertFalse(page.hasMore)
        assertEquals(emptyList<SmsUnreadEntry>(), page.unread)
        assertEquals("""{"v":1,"id":0,"t":0}""", decode(page.cursor))
    }

    private fun decode(token: String) = Base64Codecs.decodeB64u(token).toString(Charsets.UTF_8)
}
