package app.handlive.android.feature.call

import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import app.handlive.android.feature.connection.capability.AndroidPermissions
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's call rows of the `HLBENCH/1` table (shared/tools/bench/README.md): fields in the order of their row,
 * `os` taken when the OS delivered the event, `settled=true` on the one change that settled the caller's number (the
 * start of the incoming push time, CALL-01 API 4 logic 2), and never a number, a contact name or a SIM label.
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
                listOf("call", "state", "waiting", "trigger", "os", "number", "settled", "sub"),
                events[2].second.map { it.first },
            )
            assertEquals(
                listOf("trigger" to "broadcast", "os" to "${BASE_TS + 40}", "number" to "known", "settled" to "true"),
                events[2].pick("trigger", "os", "number", "settled"),
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

    @Test
    fun theBroadcastThatBringsTheNumberSettlesIt() =
        runTest {
            aCall()
            assertEquals(listOf(settled("broadcast", after = 40, number = "known")), settledLines())
        }

    @Test
    fun theSecondRingingCopyWithoutTheNumberSettlesAWithheldCaller() =
        runTest {
            val h = CallHarness(this, trace)
            h.phone(PhoneState.RINGING)
            h.advance(5)
            h.copy(PhoneState.RINGING, null)
            h.advance(3)
            h.copy(PhoneState.RINGING, null)
            h.advance(2_000)
            h.phone(PhoneState.IDLE)

            assertEquals(listOf(settled("broadcast", after = 8, number = "none")), settledLines())
            assertEquals("the first copy changes nothing", listOf("listener", "broadcast", "listener"), triggers())
        }

    @Test
    fun withoutTheCallLogPermissionRingingItselfSettlesTheNumber() =
        runTest {
            val h = CallHarness(this, trace)
            h.access.granted -= AndroidPermissions.READ_CALL_LOG
            h.phone(PhoneState.RINGING)
            h.advance(20)
            h.sim(1, PhoneState.RINGING)
            h.copy(PhoneState.RINGING, null)
            h.copy(PhoneState.RINGING, null)
            h.advance(2_000)
            h.phone(PhoneState.IDLE)

            assertEquals(listOf(settled("listener", after = 0, number = "none")), settledLines())
            assertEquals("the SIM, then the end", listOf("listener", "listener", "listener"), triggers())
        }

    @Test
    fun aCopyHeldForRingingSettlesTheNumberWithIt() =
        runTest {
            CallHarness(this, trace).ring()
            assertEquals(listOf(settled("listener", after = 0, number = "known")), settledLines())
        }

    @Test
    fun theEndOfTheWaitWritesNoLineAndALateNumberStillMarksItsOwn() =
        runTest {
            val h = CallHarness(this, trace)
            h.phone(PhoneState.RINGING)
            h.advance(300)
            assertEquals("the push left without a line", 1, h.offline.incoming.size)
            assertEquals(listOf("listener"), triggers())
            assertTrue(settledLines().isEmpty())

            h.advance(100)
            h.copy(PhoneState.RINGING, "0900000123")
            assertEquals(listOf(settled("broadcast", after = 400, number = "known")), settledLines())
        }

    @Test
    fun onlyTheNumberOfARingingIncomingCallIsSettled() =
        runTest {
            val h = CallHarness(this, trace)
            // An outgoing call with a call waiting behind it (E9), whose number comes too.
            h.phone(PhoneState.OFFHOOK)
            h.phone(PhoneState.RINGING)
            h.copy(PhoneState.RINGING, "0900000456")
            h.phone(PhoneState.OFFHOOK)
            h.phone(PhoneState.IDLE)
            // An incoming call answered before its number, which the OFFHOOK copy brings.
            h.phone(PhoneState.RINGING)
            h.advance(100)
            h.phone(PhoneState.OFFHOOK)
            h.copy(PhoneState.OFFHOOK, "0900000123")
            h.phone(PhoneState.IDLE)

            assertEquals(9, triggers().size)
            assertTrue(settledLines().isEmpty())
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

    /** The `trigger` of each `call_changed` line, in order. */
    private fun triggers() = events.filter { it.first == CallBenchEvent.CALL_CHANGED }.map { it.value("trigger") }

    /** `trigger`, `os` and `number` of each line that carries `settled`: always a `call_changed` with `true`. */
    private fun settledLines(): List<List<Pair<String, String>>> =
        events
            .filter { line -> line.second.any { it.first == "settled" } }
            .map { line ->
                assertEquals(CallBenchEvent.CALL_CHANGED, line.first)
                assertEquals("true", line.value("settled"))
                line.pick("trigger", "os", "number")
            }

    private fun settled(
        trigger: String,
        after: Long,
        number: String,
    ) = listOf("trigger" to trigger, "os" to "${BASE_TS + after}", "number" to number)

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
