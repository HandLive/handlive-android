package app.handlive.android.feature.call

import app.handlive.android.feature.call.appcall.AppCallFixtures
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app call rows of the `HLBENCH/1` table (CALL-05, shared/tools/bench/README.md): `app_call_changed` with the
 * notification's post time — or the removal's callback time — as `os`, `app_call_sent` per session with its reason,
 * `app_call_intent_sent` with its mode, and the action rows of an app call with its `call_id`; never the caller or the
 * app.
 */
class AppCallBenchTraceTest {
    private val events = mutableListOf<Pair<String, List<Pair<String, String>>>>()
    private val trace =
        CallBenchTrace { event, fields -> events += event to fields.map { it.first to it.second.toString() } }

    @Test
    fun anAnsweredAndEndedAppCallWritesItsRowsInOrder() =
        runTest {
            answeredAndEnded()

            assertEquals(
                listOf(
                    CHANGED,
                    SENT,
                    CallBenchEvent.CALL_ACTION_RECEIVED,
                    INTENT,
                    CallBenchEvent.CALL_ACTION_ACK_SENT,
                    CHANGED,
                    SENT,
                    CHANGED,
                    SENT,
                    CallBenchEvent.CALL_ACTION_RECEIVED,
                    INTENT,
                    CallBenchEvent.CALL_ACTION_ACK_SENT,
                    CHANGED,
                    SENT,
                ),
                events.map { it.first },
            )
        }

    @Test
    fun theChangesCarryThePostTimeOrTheRemovalsCallbackTime() =
        runTest {
            val call = answeredAndEnded()

            val changes = rows(CHANGED)
            assertEquals(listOf("call" to call.id, "state" to "ringing", "os" to "${BASE_TS - 230}"), changes[0])
            assertEquals("the removal's callback", "${call.ringingRemoved}", changes[1].value("os"))
            assertEquals(listOf("state" to "ongoing", "os" to "${call.inCallPosted}"), changes[2].pick("state", "os"))
            assertEquals(
                listOf("call" to call.id, "state" to "ended", "os" to "${call.inCallRemoved}", "end" to "ended"),
                changes[3],
            )
        }

    @Test
    fun theSendsTheIntentsAndTheActionsCarryTheAppCallsId() =
        runTest {
            val call = answeredAndEnded()

            val sent = rows(SENT).first()
            assertEquals(listOf("call", "env", "peer", "via", "state", "reason"), sent.map { it.first })
            assertEquals(36, sent.value("env").length)
            assertEquals(
                listOf("peer" to "pair-mac", "via" to "lan", "state" to "ringing", "reason" to "change"),
                sent.pick("peer", "via", "state", "reason"),
            )
            assertEquals(
                listOf(
                    listOf("call" to call.id, "action" to "answer", "mode" to "direct"),
                    listOf("call" to call.id, "action" to "end", "mode" to "plain"),
                ),
                rows(INTENT),
            )
            assertEquals(
                listOf(call.id, call.id),
                rows(CallBenchEvent.CALL_ACTION_RECEIVED).map { it.value("call") },
            )
            assertEquals(listOf("true", "true"), rows(CallBenchEvent.CALL_ACTION_ACK_SENT).map { it.value("ok") })
        }

    @Test
    fun aTapAnswerADeclineAndANewSessionHaveTheirOwnModeAndReason() =
        runTest {
            val h = CallHarness(this, trace)
            h.connect(h.mac.withAppCalls())
            h.exemption.held = false
            h.appPost(AppCallFixtures.telegramRinging())
            val callId =
                h.mac
                    .appCalls()
                    .last()
                    .callId

            h.action(h.mac, callId, "answer")
            h.disconnect(h.mac)
            h.mac.reconnect()
            h.connect(h.mac)
            h.action(h.mac, callId, "reject")

            assertEquals(
                listOf(
                    listOf("call" to callId, "action" to "answer", "mode" to "tap"),
                    listOf("call" to callId, "action" to "reject", "mode" to "plain"),
                ),
                rows(INTENT),
            )
            assertEquals(listOf("change", "session"), rows(SENT).map { it.value("reason") })
        }

    @Test
    fun noCallerOrAppLeaks() =
        runTest {
            val h = CallHarness(this, trace)
            h.connect(h.mac.withAppCalls())
            h.appPost(AppCallFixtures.telegramRinging())
            h.appRemove(RINGING_KEY)
            h.advance(10_000)

            assertTrue(events.isNotEmpty())
            events.flatMap { it.second }.forEach { (key, value) ->
                assertFalse("$key=$value", value.contains("Nguy") || value.contains("Telegram"))
                assertFalse("$key=$value", value.contains("telegram") || value.contains(' '))
            }
        }

    private class AnsweredCall(
        val id: String,
        val ringingRemoved: Long,
        val inCallPosted: Long,
        val inCallRemoved: Long,
    )

    /**
     * A Telegram call delivered 230 ms after its post time (spike T3.2), answered from the Mac, its in-call
     * notification posted 200 ms before it is delivered, ended from the Mac.
     */
    private suspend fun TestScope.answeredAndEnded(): AnsweredCall {
        val h = CallHarness(this, trace)
        h.connect(h.mac.withAppCalls())
        h.appPost(AppCallFixtures.telegramRinging(), postTime = BASE_TS - 230)
        val callId =
            h.mac
                .appCalls()
                .last()
                .callId
        h.action(h.mac, callId, "answer")
        h.advance(160)
        val ringingRemoved = h.wall()
        h.appRemove(RINGING_KEY)
        h.advance(500)
        val inCallPosted = h.wall() - 200
        h.appPost(AppCallFixtures.telegramInCall(), postTime = inCallPosted)
        h.action(h.mac, callId, "end")
        h.advance(20)
        val inCallRemoved = h.wall()
        h.appRemove(IN_CALL_KEY)
        return AnsweredCall(callId, ringingRemoved, inCallPosted, inCallRemoved)
    }

    private fun rows(event: String) = events.filter { it.first == event }.map { it.second }

    private fun List<Pair<String, String>>.value(key: String) = first { it.first == key }.second

    private fun List<Pair<String, String>>.pick(vararg keys: String) = filter { it.first in keys }

    private companion object {
        const val CHANGED = CallBenchEvent.APP_CALL_CHANGED
        const val SENT = CallBenchEvent.APP_CALL_SENT
        const val INTENT = CallBenchEvent.APP_CALL_INTENT_SENT
        const val RINGING_KEY = "0|org.telegram.messenger|203|null|10148"
        const val IN_CALL_KEY = "0|org.telegram.messenger|202|null|10148"
    }
}
