package app.handlive.android.feature.call

import android.Manifest
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.system.AndroidTelecom
import app.handlive.android.feature.call.system.ContentResolverCallLog
import app.handlive.android.feature.call.system.TelephonyWatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors

/**
 * On a real phone with a SIM and no call in progress: the call state listener reports the idle phone as soon as it is
 * registered (CALL-01 API 2 logic 2), the call log provider answers the CALL-04 queries with the `limit` parameter,
 * and Telecom says there is no call (CALL-02 API 3 logic 2).
 *
 * `./gradlew :feature:call:connectedDebugAndroidTest` with a phone.
 */
@RunWith(AndroidJUnit4::class)
class CallSystemDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun grant() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG).forEach {
            automation.grantRuntimePermission(context.packageName, it)
        }
    }

    @Test
    fun theListenerReportsTheIdlePhoneRightAway() =
        runBlocking {
            val first = CompletableDeferred<PhoneState>()
            val watcher =
                TelephonyWatcher(
                    context,
                    Executors.newSingleThreadExecutor(),
                    System::currentTimeMillis,
                    { state, _ -> first.complete(state) },
                    {},
                )
            try {
                assertTrue(watcher.start(emptyList()))
                assertEquals(PhoneState.IDLE, withTimeout(REPORT_BUDGET_MILLIS) { first.await() })
            } finally {
                watcher.stop()
            }
        }

    @Test
    fun theCallLogAnswersTheQueriesInTheirOrderAndLimit() {
        val callLog = ContentResolverCallLog(context.contentResolver)
        val max = callLog.maxId() ?: return
        val newest = callLog.newestIdsSince(System.currentTimeMillis() - NINETY_DAYS, LIMIT)
        assertTrue(newest.size <= LIMIT)
        assertEquals(newest.sortedDescending(), newest)
        assertTrue(newest.all { it <= max })
        val page = callLog.rows(0, inclusive = false, limit = LIMIT)
        assertTrue(page.size <= LIMIT)
        assertEquals(page.map { it.id }.sorted(), page.map { it.id })
    }

    @Test
    fun telecomSaysThereIsNoCall() {
        assertEquals(PhoneState.IDLE, AndroidTelecom(context).phoneState())
    }

    private companion object {
        const val REPORT_BUDGET_MILLIS = 2_000L
        const val LIMIT = 5
        const val NINETY_DAYS = 90L * 24 * 60 * 60 * 1000
    }
}
