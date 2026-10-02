package app.handlive.android.feature.call.appcall

import android.content.ComponentName
import android.content.Context
import android.content.IntentFilter
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The cellular dialers whose notifications are never app calls (CALL-05): the default and the system dialer, every
 * app with an `InCallService`, Telecom and the phone process — read again once the cache expires, since the user can
 * change the default dialer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidDialerPackagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val telecom = shadowOf(context.getSystemService(TelecomManager::class.java))
    private var now = 1_000L
    private val dialers = AndroidDialerPackages(context) { now }

    @Test
    fun theDialersAreTheDefaultAndSystemDialersTheInCallServicesTelecomAndThePhone() {
        telecom.setDefaultDialerPackage(GOOGLE_DIALER)
        telecom.setSystemDialerPackage(SAMSUNG_DIALER)
        val inCall = ComponentName(CAR_APP, "$CAR_APP.CarInCallService")
        val packages = shadowOf(context.packageManager)
        packages.addServiceIfNotPresent(inCall)
        packages.addIntentFilterForService(inCall, IntentFilter(InCallService.SERVICE_INTERFACE))

        assertEquals(
            setOf("com.android.server.telecom", "com.android.phone", GOOGLE_DIALER, SAMSUNG_DIALER, CAR_APP),
            dialers.current(),
        )
    }

    @Test
    fun aNewDefaultDialerCountsOnceTheCacheExpires() {
        telecom.setDefaultDialerPackage(GOOGLE_DIALER)
        assertEquals(true, GOOGLE_DIALER in dialers.current())

        telecom.setDefaultDialerPackage(SAMSUNG_DIALER)
        now += AndroidDialerPackages.CACHE_MILLIS - 1
        assertEquals("still cached", false, SAMSUNG_DIALER in dialers.current())

        now += 1
        assertEquals(true, SAMSUNG_DIALER in dialers.current())
        assertEquals(false, GOOGLE_DIALER in dialers.current())
    }

    private companion object {
        const val GOOGLE_DIALER = "com.google.android.dialer"
        const val SAMSUNG_DIALER = "com.samsung.android.dialer"
        const val CAR_APP = "com.example.car"
    }
}
