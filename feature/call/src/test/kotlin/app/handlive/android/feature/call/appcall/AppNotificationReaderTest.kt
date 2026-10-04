package app.handlive.android.feature.call.appcall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.test.core.app.ApplicationProvider
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
 * Real `CallStyle` notifications (built by the framework, like Telegram's) read into [AppNotification]: the shape, the
 * intents the posting app created itself, the caller only when asked, and no title or text read for any notification
 * at read time; the listener's filter keeps only `CallStyle` and ongoing notifications.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppNotificationReaderTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val reader = AppNotificationReader(context)

    @Before
    fun setUp() {
        manager.createNotificationChannel(NotificationChannel("calls", "Calls", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun intent(name: String): PendingIntent =
        PendingIntent.getBroadcast(context, name.hashCode(), Intent(name), PendingIntent.FLAG_IMMUTABLE)

    private fun builder() =
        NotificationCompat
            .Builder(context, "calls")
            .setSmallIcon(android.R.drawable.sym_call_incoming)

    private fun post(notification: Notification): StatusBarNotification {
        manager.notify("t", 7, notification)
        return manager.activeNotifications.single()
    }

    @Test
    fun anIncomingCallStyleNotificationIsReadWithItsIntentsAndCaller() {
        val person = Person.Builder().setName("Nguyễn Văn A").build()
        val sbn =
            post(
                builder()
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, intent("decline"), intent("answer")))
                    .build(),
            )

        val read = reader.read(sbn)

        assertEquals(sbn.key, read.key)
        assertEquals(context.packageName, read.packageName)
        assertEquals(AppCallParser.CALL_TYPE_INCOMING, read.callType)
        assertEquals(AppCallShape.RINGING, AppCallParser.shape(read))
        assertNotNull(read.intents.answer)
        assertNotNull(read.intents.decline)
        assertNull(read.intents.hangUp)
        assertEquals("Nguyễn Văn A", read.caller())
    }

    @Test
    fun anOngoingCallStyleNotificationHasItsHangUpIntent() {
        val person = Person.Builder().setName("Trần Thị B").build()
        val sbn =
            post(
                builder()
                    .setOngoing(true)
                    .setStyle(NotificationCompat.CallStyle.forOngoingCall(person, intent("hangup")))
                    .build(),
            )

        val read = reader.read(sbn)

        assertEquals(AppCallParser.CALL_TYPE_ONGOING, read.callType)
        assertTrue(read.ongoing)
        assertNotNull(read.intents.hangUp)
        assertNotNull(AppCallEndAction.select(read))
        assertEquals("Trần Thị B", read.caller())
    }

    @Test
    fun anOrdinaryOngoingNotificationHasItsOrdinaryActionsAndNoCallType() {
        val sbn =
            post(
                builder()
                    .setOngoing(true)
                    .setContentTitle("Telegram")
                    .addAction(android.R.drawable.ic_menu_close_clear_cancel, "End", intent("end"))
                    .build(),
            )

        val read = reader.read(sbn)

        assertNull(read.callType)
        assertTrue(read.ongoing)
        assertEquals(1, read.intents.actions.size)
        assertNotNull(AppCallEndAction.select(read))
        assertNull(AppCallParser.shape(read))
        assertEquals("Telegram", read.caller())
    }

    @Test
    fun anActionWithoutAnIntentCountsAsAnActionWithoutAnIntent() {
        val notification = builder().setContentTitle("x").build()
        notification.actions = arrayOf(Notification.Action.Builder(0, "Semantic", null).build())

        val read = reader.read(post(notification))

        assertEquals(listOf(null), read.intents.actions)
        assertNull(AppCallEndAction.select(read))
    }

    @Test
    fun theReadTouchesNeitherTheTitleNorTheText() {
        val notification = builder().setCategory("msg").build()
        notification.extras.putCharSequence(Notification.EXTRA_TITLE, Exploding)
        notification.extras.putCharSequence(Notification.EXTRA_TEXT, Exploding)

        val read = reader.read(post(notification))

        assertFalse(read.ongoing)
        assertNull(AppCallParser.shape(read))
    }

    @Test
    fun anIntentAnotherAppCreatedCountsAsAbsent() {
        val foreign = intent("decline").also { shadowOf(it).setCreatorPackage(OTHER_APP) }
        val person = Person.Builder().setName("Nguyễn Văn A").build()
        val ringing =
            post(
                builder()
                    .setStyle(NotificationCompat.CallStyle.forIncomingCall(person, foreign, intent("answer")))
                    .build(),
            )
        val intents = reader.read(ringing).intents
        assertNull("not the posting app's own intent", intents.decline)
        assertNotNull(intents.answer)

        val end = intent("end").also { shadowOf(it).setCreatorPackage(OTHER_APP) }
        val icon = android.R.drawable.ic_menu_close_clear_cancel
        val inCall = reader.read(post(builder().setOngoing(true).addAction(icon, "End", end).build()))
        assertEquals("an action without a usable intent", listOf(null), inCall.intents.actions)
        assertNull(AppCallEndAction.select(inCall))
    }

    @Test
    fun onlyCallStyleAndOngoingNotificationsPassTheFilter() {
        val person = Person.Builder().setName("A").build()
        val ringing =
            builder().setStyle(NotificationCompat.CallStyle.forIncomingCall(person, intent("d"), intent("a"))).build()
        val ongoing = builder().setOngoing(true).build()
        val message = builder().setContentTitle("x").build()

        assertTrue(AppNotificationReader.candidate(post(ringing)))
        assertTrue(AppNotificationReader.candidate(post(ongoing)))
        assertFalse(AppNotificationReader.candidate(post(message)))
    }

    @Test
    fun aBareCallTypeExtraOnAnOrdinaryNotificationIsNoCall() {
        val sbn = post(fakeCall())

        val read = reader.read(sbn)

        assertNull("no CallStyle template, no call", read.callType)
        assertNull(AppCallParser.shape(read))
        assertFalse(AppNotificationReader.candidate(sbn))
    }

    @Test
    fun belowApi31TheCallTypeExtraIsAllThereIs() {
        val sbn = post(fakeCall())

        assertEquals(AppCallParser.CALL_TYPE_INCOMING, AppNotificationReader(context, sdk = 30).read(sbn).callType)
        assertTrue(AppNotificationReader.candidate(sbn, sdk = 30))
    }

    /** An ordinary notification dressed as a call: the extras a real `CallStyle` sets, without its template. */
    private fun fakeCall(): Notification =
        builder().setContentTitle("Bank").build().apply {
            extras.putInt("android.callType", AppCallParser.CALL_TYPE_INCOMING)
            extras.putParcelable("android.answerIntent", intent("answer"))
        }

    @Test
    fun theCallerIsTheTitleWhenTheNotificationHasNoCallPerson() {
        val read = reader.read(post(builder().setContentTitle("Zalo call").build()))

        assertEquals("Zalo call", read.caller())
    }

    private companion object {
        const val OTHER_APP = "com.example.other"
    }

    /** A title that fails the test if anything reads its characters. */
    private object Exploding : CharSequence {
        override val length: Int get() = error("the title was read")

        override fun get(index: Int): Char = error("the title was read")

        override fun subSequence(
            startIndex: Int,
            endIndex: Int,
        ): CharSequence = error("the title was read")

        override fun toString(): String = error("the title was read")
    }
}
