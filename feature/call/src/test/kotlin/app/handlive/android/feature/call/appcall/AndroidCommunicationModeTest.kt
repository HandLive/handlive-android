package app.handlive.android.feature.call.appcall

import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** CALL-05 E11: the audio mode from `AudioManager`, watched from API 31; below it nothing is in communication. */
@RunWith(RobolectricTestRunner::class)
class AndroidCommunicationModeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audio = context.getSystemService(AudioManager::class.java)
    private val mode = AndroidCommunicationMode(context)

    @Test
    @Config(sdk = [35])
    fun aCallInTheCommunicationModeIsInCommunication() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        assertTrue(mode.inCommunication())

        audio.mode = AudioManager.MODE_NORMAL
        assertFalse(mode.inCommunication())
    }

    @Test
    @Config(sdk = [35])
    fun theWatchTellsWhenTheModeLeavesCommunicationUntilStopped() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        var left = 0
        mode.watch { left++ }
        assertEquals("still in communication", 0, left)

        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(context.mainLooper).idle()
        assertEquals(1, left)

        mode.stop()
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        audio.mode = AudioManager.MODE_NORMAL
        shadowOf(context.mainLooper).idle()
        assertEquals("no longer watched", 1, left)
    }

    @Test
    @Config(sdk = [35])
    fun aModeThatAlreadyLeftIsToldAtOnce() {
        audio.mode = AudioManager.MODE_NORMAL
        var left = 0

        mode.watch { left++ }

        assertEquals(1, left)
    }

    @Test
    @Config(sdk = [30])
    fun belowApi31NothingIsInCommunicationAndAWatchEndsAtOnce() {
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        var left = 0

        assertFalse(mode.inCommunication())
        mode.watch { left++ }

        assertEquals(1, left)
    }
}
