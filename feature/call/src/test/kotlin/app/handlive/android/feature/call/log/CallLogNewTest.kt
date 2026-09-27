package app.handlive.android.feature.call.log

import app.handlive.android.core.protocol.call.CallLogType
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.testing.BASE_TS
import app.handlive.android.feature.call.testing.CallHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CALL-04 API 2 and API 3: `call_event/log_new` from the call log observer while it runs. */
class CallLogNewTest {
    @Test
    fun oldEntriesAreNotReplayedWhenTheObserverStarts() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.callLog.add(type = 3, date = BASE_TS - 60_000)
            h.module.watchLog(true)
            h.logChanged()
            assertTrue(h.mac.logNews().isEmpty())
        }

    @Test
    fun newEntriesGoOutInAscendingOrderWithTheCallTheyMatch() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac, h.iphone)
            h.module.watchLog(true)
            h.run()
            h.ring()
            h.advance(20_000)
            h.phone(PhoneState.IDLE)
            val missed =
                h.mac
                    .states()
                    .last()
                    .callId
            h.advance(10_000)
            h.phone(PhoneState.OFFHOOK)
            h.advance(22_000)
            h.phone(PhoneState.IDLE)
            val outgoing =
                h.mac
                    .states()
                    .last()
                    .callId

            // Both ended less than 60 s ago: they are still in the "recently ended" list.
            h.callLog.add(type = 3, date = BASE_TS + 40, id = 5120)
            h.callLog.add(
                type = 2,
                date = BASE_TS + 30_010,
                number = "0900000456",
                id = 5121,
            ) { it.copy(durationS = 22) }
            h.callLog.add(type = 4, date = BASE_TS + 31_000, id = 5122)
            h.logChanged()

            val news = h.mac.logNews()
            assertEquals(listOf(5120L, 5121L, 5122L), news.map { it.entry.entryId })
            assertEquals(listOf(missed, outgoing, null), news.map { it.callId })
            assertEquals(CallLogType.MISSED, news[0].entry.type)
            assertEquals(news, h.iphone.logNews())
        }

    @Test
    fun aContextEndedMoreThanSixtySecondsAgoIsNoLongerMatched() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.module.watchLog(true)
            h.ring()
            h.phone(PhoneState.IDLE)
            h.advance(60_001)
            h.callLog.add(type = 3, date = BASE_TS + 40)
            h.logChanged()
            assertNull(
                h.mac
                    .logNews()
                    .single()
                    .callId,
            )
        }

    @Test
    fun deletedEntriesLowerTheMarkSoAReusedIdIsNotMissed() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            (1..5).forEach { h.callLog.add(type = 1, date = BASE_TS - it, id = it.toLong()) }
            h.module.watchLog(true)
            h.callLog.delete(4)
            h.callLog.delete(5)
            h.logChanged()
            h.callLog.add(type = 3, date = BASE_TS, id = 4)
            h.logChanged()
            assertEquals(listOf(4L), h.mac.logNews().map { it.entry.entryId })
        }

    @Test
    fun onlySessionsWithCallsInEffectGetEntries() =
        runTest {
            val h = CallHarness(this)
            h.iphone.effective.value = emptySet()
            h.connect(h.mac, h.iphone)
            h.module.watchLog(true)
            h.run()
            h.callLog.add(type = 1, date = BASE_TS)
            h.logChanged()
            assertEquals(1, h.mac.logNews().size)
            assertTrue(h.iphone.logNews().isEmpty())
        }

    @Test
    fun aStoppedObserverSendsNothing() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.module.watchLog(true)
            h.run()
            h.module.watchLog(false)
            h.callLog.add(type = 1, date = BASE_TS)
            h.logChanged()
            assertTrue(h.mac.logNews().isEmpty())
        }

    @Test
    fun aProviderFailureInARoundIsDroppedAndTheNextRoundGoesOn() =
        runTest {
            val h = CallHarness(this)
            h.connect(h.mac)
            h.module.watchLog(true)
            h.run()
            h.callLog.add(type = 1, date = BASE_TS, id = 9_001)
            h.callLog.failing = true
            h.logChanged()
            h.callLog.failing = false
            h.logChanged()
            val entry =
                h.mac
                    .logNews()
                    .single()
                    .entry
            assertEquals(9_001L, entry.entryId)
            assertNull(
                h.mac
                    .logNews()
                    .single()
                    .callId,
            )
        }
}
