package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.call.CallOp
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.protocol.testing.JsonSchemaValidation
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every `call_event/app_call` and every error `ack` of an app-call action passes the schemas of shared/schemas. */
class AppCallMessageSchemaTest {
    private val window = CallConstants.APP_CALL_LINK_WINDOW_MILLIS

    /** When the end of the link window is decided: the window and the grace for posts still on their way. */
    private val windowEnd = window + CallConstants.APP_CALL_LINK_GRACE_MILLIS

    @Test
    fun theEventsOfSeveralAppCallsAreValid() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())

            // Answered from the Mac in direct mode, then ended from the Mac.
            h.appPost(AppCallFixtures.telegramRinging())
            val first =
                h.mac
                    .appCalls()
                    .last()
                    .callId
            h.action(h.mac, first, "answer")
            h.appRemove(RINGING_KEY)
            h.advance(300)
            h.appPost(AppCallFixtures.telegramInCall())
            h.advance(20_000)
            h.action(h.mac, first, "end")
            h.appRemove(IN_CALL_KEY)
            // Declined from the Mac in tap mode (a different app, no caller name).
            h.exemption.held = false
            h.appEnvironmentChanged()
            val zalo =
                AppCallFixtures.notification(
                    "z1",
                    AppCallFixtures.ZALO,
                    AppCallFixtures.CALL_TYPE_INCOMING,
                    decline = FakeAppIntent("decline"),
                )
            h.appPost(zalo)
            h.action(
                h.mac,
                h.mac
                    .appCalls()
                    .last()
                    .callId,
                "reject",
            )
            h.appRemove("z1")
            // Missed, then a call that is lost with the listener, and one that is ongoing from the start.
            h.appPost(AppCallFixtures.telegramRinging(key = "r2"))
            h.appRemove("r2")
            h.advance(windowEnd)
            h.appPost(AppCallFixtures.telegramRinging(key = "r3"))
            h.appListenerLost()
            val ongoing =
                AppCallFixtures.notification(
                    "o1",
                    AppCallFixtures.TELEGRAM,
                    AppCallFixtures.CALL_TYPE_ONGOING,
                    ongoing = true,
                    hangUp = FakeAppIntent("hangup"),
                    caller = "x".repeat(300),
                )
            h.appPost(ongoing)

            val events = h.mac.sent.filter { it.type == MessageType.CALL_EVENT }
            assertTrue(events.size >= 12)
            for (event in events) {
                assertEquals(CallOp.APP_CALL, PlaintextCodec.decodePayload(event.plaintext).op)
                JsonSchemaValidation.assertValid("call_event-app_call.schema.json", String(event.plaintext))
            }
        }

    @Test
    fun theAcksOfAppCallActionsAreValid() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            val callId =
                h.mac
                    .appCalls()
                    .last()
                    .callId
            val unknown = "0192f3f0-6a1b-7c2d-8e3f-4a5b6c7d8e90"
            val bogus = """{"call_id":"$callId","action":"bogus"}"""

            val acks =
                mutableListOf(
                    h.action(h.mac, callId, "end"),
                    h.action(h.mac, callId, "hold"),
                    h.action(h.mac, callId, "answer", "mac"),
                    h.action(h.mac, unknown, "end"),
                )
            val badRequest = h.request(h.mac, "action", bogus)
            acks += h.mac.acks().last { it.re == badRequest }
            h.mac.effective.value = emptySet()
            acks += h.action(h.mac, callId, "reject")
            h.mac.withAppCalls()
            acks += h.action(h.mac, callId, "reject")

            assertEquals(listOf(false, false, false, false, false, false, true), acks.map { it.ok })
            acks.forEach(::assertAckValid)
        }

    private fun assertAckValid(ack: Ack) {
        val json = String(PlaintextCodec.encodeAck(ack))
        val def = if (ack.ok) "ack" else "ack-failure"
        JsonSchemaValidation.assertValid("call_event-action.schema.json#/\$defs/$def", json)
        JsonSchemaValidation.assertValid("ack.schema.json", json)
    }

    private companion object {
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
    }
}
