package app.handlive.android.feature.clipboard.component

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.TransferProgress
import app.handlive.android.feature.connection.notification.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/** CLIP-01 fields 8–13 and CLIP-03 fields 2–7: catalog texts, the `clipboard` channel, buttons and toasts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipNotifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifier = ClipNotifier(context)
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test
    fun sensitiveContentIsSilentOnTheClipboardChannelWithSendAnywayForTwoMinutes() {
        notifier.sensitiveBlocked()
        notifier.sensitiveBlocked()
        val notification = posted().single()
        assertEquals(NotificationChannels.CLIPBOARD, notification.channelId)
        assertEquals("Sensitive Content Blocked", NotificationCompat.getContentTitle(notification))
        assertEquals(
            "HandLive doesn't send content that looks like a password or card number.",
            NotificationCompat.getContentText(notification),
        )
        assertEquals("Send Anyway", notification.actions.single().title)
        assertEquals(120_000L, notification.timeoutAfter)
    }

    @Test
    fun conflictNamesTheDeviceAndOffersSendAgain() {
        notifier.conflict("MacBook của Lan", "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e")
        val notification = posted().single()
        assertEquals("Clipboard Not Updated on MacBook của Lan", NotificationCompat.getContentTitle(notification))
        assertEquals("That device just copied something new.", NotificationCompat.getContentText(notification))
        assertEquals("Send Again", notification.actions.single().title)
    }

    @Test
    @Config(qualifiers = "vi")
    fun vietnameseTexts() {
        notifier.conflict("MacBook của Lan", "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e")
        assertEquals("Chưa ghi lên MacBook của Lan", NotificationCompat.getContentTitle(posted().single()))
        notifier.show(ClipMessage.SentTo("MacBook của Lan"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Đã gửi tới MacBook của Lan", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun imageProgressShowsPercentWithCancelAndGoesAway() {
        val progress = TransferProgress("t-1", "pair-mac", "Lan's Pixel", sending = true, percent = 45)
        notifier.progress(progress)
        val notification = posted().single()
        assertEquals("Sending image to Lan's Pixel — 45%", NotificationCompat.getContentText(notification))
        assertEquals(45, notification.extras.getInt(NotificationCompat.EXTRA_PROGRESS))
        assertEquals("Cancel", notification.actions.single().title)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        notifier.progress(progress.copy(sending = false, percent = 47))
        assertEquals("Sending image to Lan's Pixel — 45%", NotificationCompat.getContentText(posted().single()))
        notifier.progress(progress.copy(percent = null))
        assertTrue(posted().isEmpty())
    }

    @Test
    fun resultsAndErrorsAreToastsInPlace() {
        notifier.show(ClipMessage.NotConnected)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Not connected — will send if reconnected within 2 minutes", ShadowToast.getTextOfLatestToast())
        notifier.show(ClipMessage.ImageTooLarge)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Image is too large (up to 10 MB)", ShadowToast.getTextOfLatestToast())
        assertTrue(posted().isEmpty())
    }
}
