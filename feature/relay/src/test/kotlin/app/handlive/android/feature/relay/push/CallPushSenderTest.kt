package app.handlive.android.feature.relay.push

import app.handlive.android.core.crypto.derivation.PushKeyDerivation
import app.handlive.android.core.crypto.message.EnvelopeCipher
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.call.CallControls
import app.handlive.android.core.protocol.call.CallDirection
import app.handlive.android.core.protocol.call.CallEndReason
import app.handlive.android.core.protocol.call.CallLogEntryData
import app.handlive.android.core.protocol.call.CallLogNewData
import app.handlive.android.core.protocol.call.CallLogType
import app.handlive.android.core.protocol.call.CallPhase
import app.handlive.android.core.protocol.call.CallPresentation
import app.handlive.android.core.protocol.call.CallStateData
import app.handlive.android.core.protocol.call.HfpControl
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.EnvelopeCodec
import app.handlive.android.core.protocol.relay.PushRequest
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.call.module.MissedCall
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

/** CALL-01 step 5 / API 4 and CALL-04 API 5 through the relay: who gets a call push, and the outbox (CONN-04 E2). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CallPushSenderTest {
    private val fixture = RelayFixture()
    private val answered = mutableListOf<Pair<String, Int>>()
    private val outbox = fixture.database.pushOutbox()
    private val sender =
        PushSender(fixture.api, fixture.relayPairs, outbox, fixture.clock, { request, status ->
            answered += request.reason to status
        })

    @After
    fun tearDown() = fixture.close()

    @Test
    fun aRingingCallIsPushedToAnIphoneThatWantsCallNotifications() =
        runTest {
            val pairId = fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_NOTIFY, peerDeviceId = PEER)
            fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""")

            sender.callIncoming(RINGING, connected = emptySet())

            val body =
                fixture.http
                    .calls("POST", "/v1/push")
                    .single()
                    .body!!
            JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/push-request", body)
            val request = ProtocolJson.decodeFromString(PushRequest.serializer(), body)
            assertEquals(pairId, request.pairId)
            assertEquals(PEER, request.to)
            assertEquals("alert", request.kind)
            assertEquals("call_incoming", request.reason)
            assertEquals("call:$CALL_ID", request.collapseKey)
            assertEquals(30, request.ttlS)
            assertEquals("""{"op":"state","data":${json(RINGING)}}""", open(request))
            assertEquals(listOf("call_incoming" to 202), answered)
        }

    @Test
    fun noCallPushForSessionsMacsOrClientsThatDoNotWantOne() =
        runTest {
            val connected = fixture.addPair(PeerPlatform.IPADOS, features = Features.CALLS_NOTIFY)
            fixture.addPair(PeerPlatform.MACOS, features = Features.CALLS_NOTIFY)
            fixture.addPair(PeerPlatform.IOS, registered = false, features = Features.CALLS_NOTIFY)
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_SILENT)
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_OFF)
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_RELAY_OFF)
            fixture.addPair(PeerPlatform.IOS, features = Features.SMS_NOTIFY)

            sender.callIncoming(RINGING, connected = setOf(connected))
            sender.callMissed(MissedCall.Inferred(MISSED), connected = setOf(connected))

            assertTrue(fixture.http.calls("POST", "/v1/push").isEmpty())
        }

    @Test
    fun aFailedIncomingPushWaitsThirtySecondsAtMost() =
        runTest {
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_NOTIFY, peerDeviceId = PEER)
            fixture.http.enqueue("POST", "/v1/push", 502, fixture.http.error("PUSH_PROVIDER_ERROR"))
            sender.callIncoming(RINGING, connected = emptySet())
            assertEquals(fixture.now + 5_000, outbox.nextAttemptAt(fixture.now))
            assertEquals(listOf("call_incoming" to 502), answered)

            fixture.now += 5_000
            fixture.http.enqueue("POST", "/v1/push", 503, fixture.http.error("INTERNAL"))
            sender.retryDue()
            val retried = fixture.http.pushes().last()
            assertEquals("call_incoming", retried.reason)
            assertEquals("call:$CALL_ID", retried.collapseKey)
            assertEquals(30, retried.ttlS)
            JsonSchemaValidation.assertValid(
                "relay-rest.schema.json#/\$defs/push-request",
                fixture.http
                    .calls("POST", "/v1/push")
                    .last()
                    .body!!,
            )

            // The next try would be 15 s later, after the 30 s: it is dropped unsent.
            fixture.now += 25_000
            sender.retryDue()
            assertEquals(2, fixture.http.pushes().size)
            assertNull(outbox.nextAttemptAt(fixture.now))
        }

    @Test
    fun theEndOfTheRingingDropsAQueuedIncomingPushButNotTheMissedOne() =
        runTest {
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_NOTIFY)
            fixture.http.enqueue("POST", "/v1/push", 429, fixture.http.error("RATE_LIMITED"), retryAfterMillis = 10_000)
            sender.callIncoming(RINGING, connected = emptySet())
            fixture.http.enqueue("POST", "/v1/push", 502, fixture.http.error("PUSH_PROVIDER_ERROR"))
            sender.callMissed(MissedCall.Inferred(MISSED), connected = emptySet())

            sender.dropIncoming(CALL_ID)
            fixture.now += 10_000
            fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""")
            sender.retryDue()
            val retried = fixture.http.pushes().last()
            assertEquals(3, fixture.http.pushes().size)
            assertEquals("call_missed", retried.reason)
            assertEquals(86_400, retried.ttlS)
            assertNull(outbox.nextAttemptAt(fixture.now))
        }

    @Test
    fun aMissedCallOfTheCallLogCarriesLogNewKeyedByItsCallOrEntry() =
        runTest {
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_NOTIFY, peerDeviceId = PEER)
            repeat(2) { fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""") }
            val matched = CallLogNewData(ENTRY, CALL_ID)

            sender.callMissed(MissedCall.Logged(matched), connected = emptySet())
            sender.callMissed(MissedCall.Logged(matched.copy(callId = null)), connected = emptySet())

            val (first, second) = fixture.http.pushes()
            assertEquals("call_missed", first.reason)
            assertEquals("call:$CALL_ID", first.collapseKey)
            assertEquals(86_400, first.ttlS)
            assertEquals("""{"op":"log_new","data":${json(matched)}}""", open(first))
            assertEquals("calllog:5120", second.collapseKey)
            fixture.http.calls("POST", "/v1/push").forEach {
                JsonSchemaValidation.assertValid("relay-rest.schema.json#/\$defs/push-request", it.body!!)
            }
        }

    @Test
    fun withoutTheCallLogTheMissedCallCarriesTheIdleState() =
        runTest {
            fixture.addPair(PeerPlatform.IOS, features = Features.CALLS_NOTIFY)
            fixture.http.enqueue("POST", "/v1/push", 503, fixture.http.error("INTERNAL"))
            sender.callMissed(MissedCall.Inferred(MISSED), connected = emptySet())
            val push = fixture.http.pushes().single()
            assertEquals("call:$CALL_ID", push.collapseKey)
            assertEquals("""{"op":"state","data":${json(MISSED)}}""", open(push))

            // A missed call waits a day in the outbox, like an SMS.
            fixture.now += 24 * 60 * 60 * 1000L - 1
            fixture.http.enqueue("POST", "/v1/push", 202, """{"accepted":true}""")
            sender.retryDue()
            assertEquals(
                86_400,
                fixture.http
                    .pushes()
                    .last()
                    .ttlS,
            )
        }

    private fun open(request: PushRequest): String {
        val json = Base64Codecs.decodeB64(request.envB64!!).toString(Charsets.UTF_8)
        val envelope = EnvelopeCodec.decode(json)
        assertEquals("call_event", envelope.type)
        return EnvelopeCipher.open(PushKeyDerivation.kPush(ByteArray(32) { 9 }), envelope).toString(Charsets.UTF_8)
    }

    private companion object {
        const val PEER = "dac073e0-123b-8ea5-9dd9-b3bda9cf6037"
        const val CALL_ID = "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90"
        const val UNAVAILABLE = HfpControl.UNAVAILABLE

        val RINGING =
            CallStateData(
                callId = CALL_ID,
                direction = CallDirection.INCOMING,
                state = CallPhase.RINGING,
                waiting = false,
                number = "+84900000123",
                displayName = "Nguyễn Văn A",
                presentation = CallPresentation.ALLOWED,
                subId = 1,
                simLabel = "SIM 1",
                waitingNumber = null,
                waitingDisplayName = null,
                startedAt = 1_727_150_400_123,
                answeredAt = null,
                endedAt = null,
                endReason = null,
                controls = CallControls(false, true, false, UNAVAILABLE, UNAVAILABLE, UNAVAILABLE),
                hfpConnected = false,
                audioOn = "phone",
            )

        val MISSED =
            RINGING.copy(
                state = CallPhase.IDLE,
                endedAt = 1_727_150_425_456,
                endReason = CallEndReason.MISSED,
                controls = CallControls.NONE,
            )

        val ENTRY = CallLogEntryData(5120, "+84900000123", "Nguyễn Văn A", CallLogType.MISSED, 1_727_150_400_123, 0, 1)

        fun json(state: CallStateData) = ProtocolJson.encodeToString(CallStateData.serializer(), state)

        fun json(new: CallLogNewData) = ProtocolJson.encodeToString(CallLogNewData.serializer(), new)
    }
}
