package app.handlive.android.feature.call.appcall

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.settings.HandLiveSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether app calls may run (CALL-05): not before the settings are loaded, then the two switches and Notification
 * access — asked of Android again only once a grant has aged, never for every notification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAppCallAccessTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var settings: HandLiveSettings? = null
    private var now = 0L
    private var granted = true
    private var asked = 0
    private val access =
        AndroidAppCallAccess(context, { settings }, { now }) {
            asked++
            granted
        }

    @Test
    fun nothingIsEnabledBeforeTheSettingsAreLoaded() {
        assertFalse(access.enabled())
        assertEquals("not even Android is asked", 0, asked)

        settings = HandLiveSettings()
        assertTrue(access.enabled())
        settings = HandLiveSettings(callAppCalls = false)
        assertFalse(access.enabled())
        settings = HandLiveSettings(callEnabled = false)
        assertFalse(access.enabled())
    }

    @Test
    fun aGrantIsAskedAgainOnlyOnceItHasAged() {
        settings = HandLiveSettings()
        repeat(5) { assertTrue(access.enabled()) }
        assertEquals(1, asked)

        granted = false
        now += AndroidAppCallAccess.ACCESS_CACHE_MILLIS
        assertFalse(access.enabled())
        assertEquals(2, asked)
    }

    @Test
    fun aMissingAccessIsAskedEveryTimeSoAGrantCountsAtOnce() {
        settings = HandLiveSettings()
        granted = false
        assertFalse(access.enabled())

        granted = true
        assertTrue(access.enabled())
        assertEquals(2, asked)
    }
}
