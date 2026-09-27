package app.handlive.android.feature.relay.push

import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.sms.SmsBox
import app.handlive.android.core.protocol.sms.SmsMessageData
import app.handlive.android.core.protocol.sms.SmsNewData
import app.handlive.android.core.protocol.sms.SmsThreadData
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.relay.testing.Features
import app.handlive.android.feature.relay.testing.RelayFixture
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SMS-02 step 10 and CONN-04: who gets an SMS push, and what `push_outbox` does with a failed one (E1–E4). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PushSenderTest {
    private val fixture = RelayFixture()
    private val sent = mutableListOf<Pair<String, String>>()
    private val outbox = fixture.database.pushOutbox()
    private val sender =
        PushSender(fixture.api, fixture.relayPairs, outbox, fixture.clock, sent = { key, peer -> sent += key to peer })

    @After
    fun tearDown() = fixture.close()

    @Test
    fun anInboxMessageIsPushedToAnIphoneWithoutASession() =
        runTest {
            val pairId = fixture.addPair(PeerPlatform.IOS, peerDeviceId = PEER)
            fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""")

            sender.smsNew(NEW, connected = emptySet())

            val call = fixture.http.calls("POST", "/v1/push").single()
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/push-request", call.body!!)
            val request = ProtocolJson.decodeFromString(PushRequest.serializer(), call.body!!)
            assertEquals(pairId, request.pairId)
            assertEquals(PEER, request.to)
            assertEquals("sms:12847", request.collapseKey)
            assertEquals(listOf("sms:12847" to PEER), sent)
            assertNull(outbox.nextAttemptAt(fixture.now))
        }

    @Test
    fun noPushForSessionsMacsSentMessagesOrClientsThatDoNotWantOne() =
        runTest {
            val connected = fixture.addPair(PeerPlatform.IPADOS)
            fixture.addPair(PeerPlatform.MACOS)
            fixture.addPair(PeerPlatform.IOS, registered = false)
            fixture.addPair(PeerPlatform.IOS, features = Features.SMS_SILENT)
            fixture.addPair(PeerPlatform.IOS, features = Features.RELAY_OFF)
            fixture.addPair(PeerPlatform.IOS, features = "{}")

            sender.smsNew(NEW, connected = setOf(connected))
            sender.smsNew(NEW.copy(message = NEW.message.copy(box = SmsBox.SENT)), connected = emptySet())

            // The only candidate for the sent message is the connected iPad, and sent messages never push anyway.
            assertTrue(fixture.http.calls("POST", "/v1/push").isEmpty())
        }

    @Test
    fun aTemporaryFailureWaitsInTheOutboxAndIsRetried() =
        runTest {
            fixture.addPair(PeerPlatform.IOS, peerDeviceId = PEER)
            fixture.http.enqueue("POST", "/v1/push", 502, fixture.http.error("PUSH_PROVIDER_ERROR"))
            sender.smsNew(NEW, connected = emptySet())
            val first = fixture.http.pushes().single()
            assertEquals(fixture.now + 5_000, outbox.nextAttemptAt(fixture.now))

            // Not due yet: nothing is sent.
            fixture.now += 4_000
            sender.retryDue()
            assertEquals(1, fixture.http.pushes().size)

            fixture.now += 1_000
            fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""")
            sender.retryDue()
            val retried = fixture.http.pushes().last()
            assertEquals(first.envB64, retried.envB64)
            assertEquals(first.collapseKey, retried.collapseKey)
            // SMS-02 API 2: the phone always sends 86,400 with sms_new; the outbox itself stops after 24 hours.
            assertEquals(86_400, retried.ttlS)
            JsonSchemaValidation.assertValid(
                "relay-rest.schema.json#/\$defs/push-request",
                fixture.http
                    .calls("POST", "/v1/push")
                    .last()
                    .body!!,
            )
            assertNull(outbox.nextAttemptAt(fixture.now))
        }

    @Test
    fun aRateLimitWaitsForRetryAfterAndLaterFailuresBackOff() =
        runTest {
            fixture.addPair(PeerPlatform.IOS)
            fixture.http.enqueue("POST", "/v1/push", 429, fixture.http.error("RATE_LIMITED"), retryAfterMillis = 60_000)
            sender.smsNew(NEW, connected = emptySet())
            assertEquals(fixture.now + 60_000, outbox.nextAttemptAt(fixture.now))

            fixture.now += 60_000
            fixture.http.unreachable = true
            sender.retryDue()
            fixture.http.unreachable = false
            // 5 s after the first attempt, then 15 s after the second.
            assertEquals(fixture.now + 15_000, outbox.nextAttemptAt(fixture.now))
        }

    @Test
    fun refusalsAreDroppedAndExpiredPushesDeletedUnsent() =
        runTest {
            fixture.addPair(PeerPlatform.IOS)
            fixture.http.enqueue("POST", "/v1/push", 409, fixture.http.error("PUSH_TOKEN_MISSING"))
            sender.smsNew(NEW, connected = emptySet())
            assertNull(outbox.nextAttemptAt(fixture.now))

            fixture.http.enqueue("POST", "/v1/push", 503, fixture.http.error("INTERNAL"))
            sender.smsNew(NEW, connected = emptySet())
            fixture.now += 24 * 60 * 60 * 1000L
            sender.retryDue()
            assertEquals(2, fixture.http.pushes().size)
            assertNull(outbox.nextAttemptAt(fixture.now))
        }

    private companion object {
        const val PEER = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037"

        val NEW =
            SmsNewData(
                SmsMessageData(
                    messageKey = "sms:12847",
                    threadId = 42,
                    address = "+84900000123",
                    body = "Nhớ mang theo tài liệu",
                    box = SmsBox.INBOX,
                    ts = 1_727_150_060_456,
                    tsSent = 1_727_150_059_000,
                    read = false,
                    subId = 1,
                ),
                SmsThreadData(
                    threadId = 42,
                    addresses = listOf("+84900000123"),
                    displayName = "Nguyễn Văn A",
                    snippet = "Nhớ mang theo tài liệu",
                    lastTs = 1_727_150_060_456,
                    unreadCount = 2,
                ),
            )
    }
}
