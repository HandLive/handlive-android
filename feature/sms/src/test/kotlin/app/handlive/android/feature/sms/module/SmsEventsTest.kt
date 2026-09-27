package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.sms.SmsBox
import app.handlive.android.core.protocol.sms.SmsReadChangedData
import app.handlive.android.core.protocol.sms.SmsSendStatus
import app.handlive.android.feature.sms.provider.SmsType
import app.handlive.android.feature.sms.send.DeliveryReport
import app.handlive.android.feature.sms.send.SentResult
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.SmsHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One observer round: `sms/new` (SMS-02), the `local_id` of a message sent for a client (SMS-04 API 4), SMS-05. */
class SmsEventsTest {
    private suspend fun TestScope.started(): SmsHarness {
        val h = SmsHarness(this)
        h.provider.conversation(42, "+84900000123")
        h.provider.add(42, BASE_TS + 1)
        h.connect(h.mac, h.iphone)
        h.events.start()
        h.run()
        return h
    }

    @Test
    fun aNewInboxMessageGoesToEveryClientWithSmsActiveWithItsThread() =
        runTest {
            val h = started()
            h.iphone.effective.value = emptySet()
            h.provider.add(42, BASE_TS + 2, body = "Nhớ mang theo tài liệu", read = false)
            h.round()

            val new = h.mac.news().single()
            assertEquals("sms:2", new.message.messageKey)
            assertEquals(SmsBox.INBOX, new.message.box)
            assertNull(new.message.localId)
            assertEquals(42L, new.thread.threadId)
            assertEquals(1, new.thread.unreadCount)
            assertEquals("Nhớ mang theo tài liệu", new.thread.snippet)
            assertTrue("SMS is not active for the iPhone", h.iphone.news().isEmpty())
            assertEquals(
                listOf("sms:2" to setOf("pair-mac")),
                h.offline.map { it.first.message.messageKey to it.second },
            )
            assertTrue("same unread count as announced", h.mac.readChanges().isEmpty())
        }

    @Test
    fun theSentRowOfAMessageSentForAClientCarriesItsLocalIdToThatClientOnly() =
        runTest {
            val h = started()
            val localId = h.newLocalId()
            h.request(
                h.mac,
                "send",
                """{"local_id":"$localId","thread_id":42,"addresses":["0900000123"],"body":"Ok, 3h mình có mặt"}""",
            )
            h.module.onSent(localId, 0, SentResult.OK)
            h.run()
            // The system writes the message into the Sent box with the normalized recipient (0.9.2).
            h.provider.add(42, BASE_TS + 5, type = SmsType.SENT, body = "Ok, 3h mình có mặt")
            h.round()

            assertEquals(
                localId,
                h.mac
                    .news()
                    .single()
                    .message.localId,
            )
            assertNull(
                h.iphone
                    .news()
                    .single()
                    .message.localId,
            )
            // Once matched, later statuses carry the message_key (API 4 logic 2).
            h.module.onDelivered(localId, 0, DeliveryReport.DELIVERED)
            h.run()
            val delivered = h.mac.statuses().last()
            assertEquals(SmsSendStatus.DELIVERED, delivered.status)
            assertEquals("sms:2", delivered.messageKey)
        }

    @Test
    fun theOldestOfTwoIdenticalMessagesMatchesFirstAndEachEntryOnlyOnce() =
        runTest {
            val h = started()
            val first = h.newLocalId()
            advanceTimeBy(10)
            val second = h.newLocalId()
            listOf(first, second).forEach { localId ->
                h.request(h.mac, "send", """{"local_id":"$localId","addresses":["0900000123"],"body":"ok"}""")
                h.run()
                advanceTimeBy(10)
            }
            h.provider.add(42, BASE_TS + 5, type = SmsType.SENT, body = "ok")
            h.provider.add(42, BASE_TS + 6, type = SmsType.SENT, body = "ok")
            h.provider.add(42, BASE_TS + 7, type = SmsType.SENT, body = "ok")
            h.round()
            assertEquals(listOf(first, second, null), h.mac.news().map { it.message.localId })
        }

    @Test
    fun aRowOutsideTheMatchWindowAfterTheFinalResultIsTheUsersOwn() =
        runTest {
            val h = started()
            val localId = h.newLocalId()
            h.request(h.mac, "send", """{"local_id":"$localId","addresses":["0900000123"],"body":"ok"}""")
            h.module.onSent(localId, 0, SentResult.OK)
            h.run()
            advanceTimeBy(60_001)
            h.provider.add(42, BASE_TS + 5, type = SmsType.SENT, body = "ok")
            h.round()
            assertNull(
                h.mac
                    .news()
                    .single()
                    .message.localId,
            )
        }

    @Test
    fun readingOnThePhoneSendsReadChangedAfterTheNewMessages() =
        runTest {
            val h = started()
            h.provider.add(42, BASE_TS + 2, read = false)
            h.round()
            h.provider.update(2) { it.copy(read = true) }
            h.round()
            val change = h.mac.readChanges().single()
            assertEquals(SmsReadChangedData(42, 0, h.wall()), change)
            assertEquals(1, h.iphone.readChanges().size)
        }
}
