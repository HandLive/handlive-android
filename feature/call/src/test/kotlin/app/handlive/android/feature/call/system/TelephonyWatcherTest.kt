package app.handlive.android.feature.call.system

import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.call.context.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * CALL-01 API 2 on the platform: the default listener reports the aggregate state, `TelephonyCallback` on API 31+
 * and `PhoneStateListener` on API 29–30, stamped with the clock of the callback; nothing once stopped.
 */
@RunWith(RobolectricTestRunner::class)
class TelephonyWatcherTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val telephony = context.getSystemService(TelephonyManager::class.java)
    private val states = mutableListOf<Pair<PhoneState, Long>>()
    private var now = 1_727_150_400_000L
    private val watcher =
        TelephonyWatcher(context, Executor { it.run() }, { now++ }, { state, at -> states += state to at }, {})

    @Test
    @Config(sdk = [35])
    fun theCallbackReportsTheStateChangesOnApi31AndLater() = reportsTheStateChanges()

    @Test
    @Config(sdk = [30])
    fun theListenerReportsTheStateChangesOnApi30() = reportsTheStateChanges()

    private fun reportsTheStateChanges() {
        assertTrue(watcher.start(emptyList()))
        val initial = states.size
        shadowOf(telephony).setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(telephony).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(telephony).setCallState(TelephonyManager.CALL_STATE_IDLE)
        val reported = states.drop(initial).map { it.first }
        assertEquals(listOf(PhoneState.RINGING, PhoneState.OFFHOOK, PhoneState.IDLE), reported)
        assertEquals(states.map { it.second }, states.map { it.second }.sorted())

        watcher.stop()
        val before = states.size
        shadowOf(telephony).setCallState(TelephonyManager.CALL_STATE_RINGING)
        assertEquals(before, states.size)
    }
}
