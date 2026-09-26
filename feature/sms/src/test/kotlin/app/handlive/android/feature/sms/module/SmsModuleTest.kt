package app.handlive.android.feature.sms.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.sms.SmsHistoryResponse
import app.handlive.android.core.protocol.sms.SmsSyncResponse
import app.handlive.android.feature.sms.testing.BASE_TS
import app.handlive.android.feature.sms.testing.SmsHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `sms/sync` and `sms/history` through the router: the `ack` of every outcome (SMS-01, SMS-03, E1–E6). */
class SmsModuleTest {
    @Test
    fun aSyncRequestIsAnsweredWithItsPage() =
        runTest {
            val h = SmsHarness(this)
            h.provider.conversation(42, "+84900000123")
            h.provider.add(42, BASE_TS + 1, read = false)
            h.connect(h.mac)
            val id = h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":50}""")

            val ack = h.mac.acks().single()
            assertEquals(id, ack.re)
            val page = SmsHarness.decode(SmsSyncResponse.serializer(), SmsHarness.data(ack))
            assertEquals(listOf("sms:1"), page.messages.map { it.messageKey })
            assertFalse(page.hasMore)
            assertEquals(1, page.unread?.single()?.unreadCount)
        }

    @Test
    fun historyAnswersPagesAndAMissingConversation() =
        runTest {
            val h = SmsHarness(this)
            h.provider.conversation(42, "+84900000123")
            (1..3).forEach { h.provider.add(42, BASE_TS + it) }
            h.connect(h.mac)
            h.request(h.mac, "history", """{"thread_id":42,"before_ts":${BASE_TS + 3},"limit":50}""")
            h.request(h.mac, "history", """{"thread_id":57,"before_ts":${BASE_TS + 3},"limit":50}""")

            val (page, missing) = h.mac.acks()
            val messages = SmsHarness.decode(SmsHistoryResponse.serializer(), SmsHarness.data(page)).messages
            assertEquals(listOf("sms:2", "sms:1"), messages.map { it.messageKey })
            assertEquals(ErrorCode.SMS_THREAD_NOT_FOUND.name, missing.error?.code)
        }

    @Test
    fun aProviderThatCannotBeReadIsAnInternalErrorAndTheSessionGoesOn() =
        runTest {
            val h = SmsHarness(this)
            h.connect(h.mac)
            h.provider.failing = true
            h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":50}""")
            h.request(h.mac, "history", """{"thread_id":42,"before_ts":1,"limit":50}""")
            assertEquals(listOf(ErrorCode.INTERNAL.name, ErrorCode.INTERNAL.name), h.mac.acks().map { it.error?.code })
            h.provider.failing = false
            h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":50}""")
            assertTrue(
                h.mac
                    .acks()
                    .last()
                    .ok,
            )
        }

    @Test
    fun aMissingPermissionAsksThePhoneToSuggestItButSmsOffDoesNot() =
        runTest {
            val h = SmsHarness(this)
            h.connect(h.mac)
            h.access.missing += "READ_SMS"
            h.request(h.mac, "sync", """{"thread_limit":200,"per_thread_limit":50}""")
            h.access.enabled = false
            h.request(h.mac, "history", """{"thread_id":42,"before_ts":1,"limit":50}""")
            assertEquals(
                listOf(ErrorCode.PERMISSION_MISSING.name, ErrorCode.FEATURE_DISABLED.name),
                h.mac.acks().map { it.error?.code },
            )
            assertEquals(listOf("pair-mac" to "android.permission.READ_SMS"), h.permissionsAsked)
        }

    @Test
    fun eventsAndUnknownOpsFromAClientAreIgnored() =
        runTest {
            val h = SmsHarness(this)
            h.connect(h.mac)
            h.request(h.mac, "new", """{}""")
            h.request(h.mac, "archive", """{}""")
            assertTrue(h.mac.sent.isEmpty())
        }
}
