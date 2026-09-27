package app.handlive.android.feature.call

import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's call rows of the `HLBENCH/1` table (shared/tools/bench/README.md): fields in the order of their row,
 * `os` taken when the OS delivered the event, and never a number, a contact name or a SIM label.
 */
class CallBenchTraceTest {
    private val events = mutableListOf<Pair<String, List<Pair<String, String>>>>()
    private val trace =
        CallBenchTrace { event, fields -> events += event to fields.map { it.first to it.second.toString() } }

    @Test
    fun changesAndStatesCarryTheFieldsOfTheirRows() =
        runTest {
            val callId = aCall()

            assertEquals(EXPECTED, events.map { it.first })
            assertEquals(
                listOf(
                    "call" to callId,
                    "state" to "ringing",
                    "waiting" to "false",
                    "trigger" to "listener",
                    "os" to "$BASE_TS",
                    "number" to "none",
                    "sub" to "2",
                ),
                events[0].second,
            )
            assertEquals(
                listOf("trigger" to "broadcast", "os" to "${BASE_TS + 40}", "number" to "known"),
                events[2].pick("trigger", "os", "number"),
            )
            assertEquals(listOf("call", "env", "peer", "via", "state", "reason"), events[1].second.map { it.first })
            assertEquals(
                listOf("peer" to "pair-mac", "via" to "lan", "reason" to "change"),
                events[1].pick("peer", "via", "reason"),
            )
            assertEquals(36, events[1].value("env").length)
            assertEquals(listOf("state" to "offhook", "os" to "${BASE_TS + 200}"), events[8].pick("state", "os"))
            assertEquals("a new session gets the call (E8)", "session", events[10].value("reason"))
            assertEquals(listOf("state" to "idle", "end" to "ended"), events[11].pick("state", "end"))
        }

    @Test
    fun actionsCarryTheirEnvelopeAndTheErrorCode() =
        runTest {
            val callId = aCall()
            val (answer, hold) = actions
            assertEquals(
                listOf("call" to callId, "env" to answer, "peer" to "pair-mac", "action" to "answer"),
                events[4].second,
            )
            assertEquals(
                listOf("call" to callId, "env" to answer, "peer" to "pair-mac", "ok" to "true"),
                events[5].second,
            )
            assertEquals(
                listOf(
                    "call" to callId,
                    "env" to hold,
                    "peer" to "pair-mac",
                    "ok" to "false",
                    "code" to "CALL_HFP_REQUIRED",
                ),
                events[7].second,
            )
        }

    @Test
    fun noNumberNameOrSimLabelLeaks() =
        runTest {
            aCall()
            events.flatMap { it.second }.forEach { (key, value) ->
                assertFalse("$key=$value", value.contains("0900000123") || value.contains("+849"))
                assertFalse("$key=$value", value.contains("Nguyễn") || value.contains("SIM"))
                assertFalse("$key=$value", value.contains(' '))
            }
            assertTrue(events.isNotEmpty())
        }

    private val actions = mutableListOf<String>()

    /** Rings on SIM 2, the number comes 40 ms later, answered and held from the Mac, reconnected, ended. */
    private suspend fun TestScope.aCall(): String {
        val h = CallHarness(this, trace)
        h.connect(h.mac)
        h.sim(2, PhoneState.RINGING)
        h.phone(PhoneState.RINGING)
        h.advance(40)
        h.copy(PhoneState.RINGING, "0900000123")
        val callId =
            h.mac
                .states()
                .last()
                .callId
        actions += h.action(h.mac, callId, "answer").re
        actions += h.action(h.mac, callId, "hold").re
        h.advance(160)
        h.phone(PhoneState.OFFHOOK)
        h.mac.reconnect()
        h.connect(h.mac)
        h.advance(5_000)
        h.phone(PhoneState.IDLE)
        return callId
    }

    private fun Pair<String, List<Pair<String, String>>>.value(key: String): String =
        second.single { it.first == key }.second

    private fun Pair<String, List<Pair<String, String>>>.pick(vararg keys: String) = keys.map { it to value(it) }

    private companion object {
        val EXPECTED =
            listOf(
                "call_changed",
                "call_state_sent",
                "call_changed",
                "call_state_sent",
                "call_action_received",
                "call_action_ack_sent",
                "call_action_received",
                "call_action_ack_sent",
                "call_changed",
                "call_state_sent",
                "call_state_sent",
                "call_changed",
                "call_state_sent",
            )
    }
}
