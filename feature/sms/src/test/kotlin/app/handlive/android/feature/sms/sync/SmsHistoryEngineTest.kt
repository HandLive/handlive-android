package app.handlive.android.feature.sms.sync

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.sms.SmsHistoryResponse
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.FakeSmsProvider
import app.handlive.android.feature.sms.testing.objectsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SMS-03 API 1 logic 2–5: older messages newest first, the same-date group at the boundary, `has_more`. */
class SmsHistoryEngineTest {
    private val provider = FakeSmsProvider()

    private fun engine(pageMaxBytes: Int = 180 * 1024) = SmsHistoryEngine(provider, objectsOf(provider), pageMaxBytes)

    @Test
    fun olderMessagesComeNewestFirstBelowBeforeTs() {
        provider.conversation(42, "+84900000123")
        (1..10).forEach { provider.add(42, BASE_TS + it) }
        provider.add(57, BASE_TS + 5)
        provider.add(42, BASE_TS + 4, type = SmsType.DRAFT)

        val page = engine().page(42, beforeTs = BASE_TS + 8, limit = 3)
        assertEquals(listOf(BASE_TS + 7, BASE_TS + 6, BASE_TS + 5), page.messages.map { it.ts })
        assertTrue(page.hasMore)
        assertTrue(page.messages.all { it.localId == null })
        val next = engine().page(42, beforeTs = page.messages.minOf { it.ts }, limit = 50)
        assertEquals(listOf(BASE_TS + 4, BASE_TS + 3, BASE_TS + 2, BASE_TS + 1), next.messages.map { it.ts })
        assertFalse("nothing older: has_more from reading one more row", next.hasMore)
    }

    @Test
    fun theGroupSharingTheLastDateComesWholeSoTheNextRequestMissesNothing() {
        provider.conversation(42, "+84900000123")
        provider.add(42, BASE_TS + 10)
        repeat(3) { provider.add(42, BASE_TS + 9) }
        provider.add(42, BASE_TS + 8)

        val page = engine().page(42, beforeTs = BASE_TS + 100, limit = 2)
        assertEquals(listOf(BASE_TS + 10, BASE_TS + 9, BASE_TS + 9, BASE_TS + 9), page.messages.map { it.ts })
        assertTrue(page.hasMore)
        val rest = engine().page(42, beforeTs = BASE_TS + 9, limit = 2)
        assertEquals(listOf(BASE_TS + 8), rest.messages.map { it.ts })
        assertFalse(rest.hasMore)
    }

    @Test
    fun aFullPageEndsAtADateBoundaryAndSaysThereIsMore() {
        provider.conversation(42, "+84900000123")
        provider.add(42, BASE_TS + 3, body = "ă".repeat(800))
        provider.add(42, BASE_TS + 2, body = "ă".repeat(800))
        provider.add(42, BASE_TS + 2, body = "ă".repeat(800))
        provider.add(42, BASE_TS + 1, body = "ă".repeat(800))

        val page = engine(pageMaxBytes = 3_500).page(42, beforeTs = BASE_TS + 100, limit = 50)
        assertEquals("the pair of date +2 does not fit whole", listOf(BASE_TS + 3), page.messages.map { it.ts })
        assertTrue(page.hasMore)
        val size = ProtocolJson.encodeToString(SmsHistoryResponse.serializer(), page).toByteArray().size
        assertTrue(size <= 3_500)
        val next = engine(pageMaxBytes = 3_500).page(42, beforeTs = BASE_TS + 3, limit = 50)
        assertEquals(
            "a page of one date group keeps it whole",
            listOf(BASE_TS + 2, BASE_TS + 2),
            next.messages.map { it.ts },
        )
        assertTrue(next.hasMore)
        val last = engine(pageMaxBytes = 3_500).page(42, beforeTs = BASE_TS + 2, limit = 50)
        assertEquals(listOf(BASE_TS + 1), last.messages.map { it.ts })
        assertFalse(last.hasMore)
    }

    @Test
    fun aDeletedConversationIsReportedMissing() {
        provider.conversation(42, "+84900000123")
        assertTrue(engine().exists(42))
        assertFalse(engine().exists(57))
    }
}
