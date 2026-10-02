package app.handlive.android.feature.call.appcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.call.CallConstants
import app.handlive.android.feature.connection.notification.NotificationChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The "tap to answer" notification (`answer_mode = tap`): on the channel `hl_app_call`, titled "Answer <app> Call", the
 * caller as its text, private on the lock screen with only the title public, its content intent the app's answer
 * intent, gone after 60 s or on tap; nothing is posted — and `false` returned — when notifications are not allowed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class AndroidTapToAnswerNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidTapToAnswerNotifier(context)
    private val answer =
        PendingAppIntent(
            PendingIntent.getActivity(
                context,
                1,
                Intent("org.telegram.messenger.ANSWER").setPackage("org.telegram.messenger"),
                PendingIntent.FLAG_IMMUTABLE,
            ),
            context,
        )

    @Before
    fun setUp() = NotificationChannels.createAll(context)

    @Test
    fun theNotificationNamesTheAppShowsTheCallerAndRunsTheAnswerIntentOnTap() {
        assertTrue(notifier.post("call-1", "Telegram", "Nguyễn Văn A", answer))

        val posted = shadowOf(manager).getNotification("app_call:call-1", 1)
        assertEquals(NotificationChannels.APP_CALL, posted.channelId)
        assertEquals("hl_app_call", posted.channelId)
        assertEquals("Answer Telegram Call", posted.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertEquals("Nguyễn Văn A", posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString())
        assertEquals("org.telegram.messenger.ANSWER", shadowOf(posted.contentIntent).savedIntent.action)
        assertTrue(posted.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(CallConstants.APP_CALL_TAP_NOTIFICATION_TTL_MILLIS, posted.timeoutAfter)
        assertFalse("not a call notification", posted.category == Notification.CATEGORY_CALL)
        assertNull("no CallStyle", posted.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun onTheLockScreenOnlyTheTitleIsPublic() {
        notifier.post("call-1", "Telegram", "Nguyễn Văn A", answer)

        val posted = shadowOf(manager).getNotification("app_call:call-1", 1)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        val public = posted.publicVersion
        assertNotNull(public)
        assertEquals("Answer Telegram Call", public.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertNull(public.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
    }

    @Test
    fun withoutACallerThereIsNoText() {
        notifier.post("call-1", "Zalo", null, answer)

        val posted = shadowOf(manager).getNotification("app_call:call-1", 1)
        assertEquals("Answer Zalo Call", posted.extras.getCharSequence(NotificationCompat.EXTRA_TITLE).toString())
        assertNull(posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT))
    }

    @Test
    fun oneNotificationPerCallAndCancelRemovesIt() {
        notifier.post("call-1", "Telegram", null, answer)
        notifier.post("call-1", "Telegram", null, answer)
        notifier.post("call-2", "Zalo", null, answer)
        assertEquals(2, shadowOf(manager).size())

        notifier.cancel("call-1")

        assertNull(shadowOf(manager).getNotification("app_call:call-1", 1))
        assertNotNull(shadowOf(manager).getNotification("app_call:call-2", 1))
        notifier.cancel("call-1")
    }

    @Test
    fun theChannelIsHighImportanceNamedCallsFromOtherAppsWithoutDescription() {
        val channel: NotificationChannel = manager.getNotificationChannel(NotificationChannels.APP_CALL)

        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertEquals("Calls from Other Apps", channel.name.toString())
        assertTrue(channel.description.isNullOrEmpty())
    }

    @Test
    fun nothingIsPostedWhenNotificationsAreNotAllowed() {
        shadowOf(manager).setNotificationsEnabled(false)

        assertFalse(notifier.post("call-1", "Telegram", null, answer))
        assertEquals(0, shadowOf(manager).size())
    }

    @Test
    fun nothingIsPostedWhenTheChannelIsBlocked() {
        manager.createNotificationChannel(
            NotificationChannel(NotificationChannels.APP_CALL, "x", NotificationManager.IMPORTANCE_NONE),
        )
        val blocked = manager.getNotificationChannel(NotificationChannels.APP_CALL)
        assertEquals(NotificationManager.IMPORTANCE_NONE, blocked.importance)

        assertFalse(notifier.post("call-1", "Telegram", null, answer))
    }

    @Test
    fun anIntentThatIsNotAPendingIntentCannotBeTapped() {
        assertFalse(notifier.post("call-1", "Telegram", null, FakeAppIntent("answer")))
        assertEquals(0, shadowOf(manager).size())
    }
}
