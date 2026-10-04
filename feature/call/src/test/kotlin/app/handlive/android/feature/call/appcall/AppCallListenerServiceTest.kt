package app.handlive.android.feature.call.appcall

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.connection.capability.NotificationAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNotificationListenerService
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/**
 * The listener service (CALL-05 API 3): HandLive's own notifications, the cellular dialers' and every notification that
 * is neither `CallStyle` nor ongoing are dropped on the listener thread; every other one goes to A-CALL as its shape
 * with its post time, the standing ones are followed (oldest first) when it starts reading, access changes reach the
 * capability, it waits for the settings and unbinds itself while `call.app_calls` or `feature.call` is off, and a
 * failing callback skips the notification without crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppCallListenerServiceTest {
    private class Target : AppCallListenerTarget {
        var wanted: Boolean? = true
        val posted = mutableListOf<Pair<AppNotification, Long>>()
        val removed = mutableListOf<Pair<String, Long>>()
        val changes = mutableListOf<Pair<Boolean, Long>>()

        /** A-CALL fails on the notifications of this package. */
        var failingPackage: String? = null

        override fun appCallsWanted() = wanted

        override fun posted(
            notification: AppNotification,
            at: Long,
        ) {
            check(notification.packageName != failingPackage) { "A-CALL failed" }
            posted += notification to at
        }

        override fun removed(
            key: String,
            at: Long,
        ) {
            removed += key to at
        }

        override fun listenerChanged(
            connected: Boolean,
            at: Long,
        ) {
            changes += connected to at
        }
    }

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val target = Target()
    private val service = Robolectric.buildService(AppCallListenerService::class.java).create().get()

    @Before
    fun setUp() {
        AppCallListenerService.targetFactory = { target }
        AppCallListenerService.dialersFactory = { DialerPackages { AndroidDialerPackages.TELEPHONY + DIALER } }
        ShadowNotificationListenerService.reset()
    }

    @After
    fun tearDown() {
        AppCallListenerService.targetFactory = null
        AppCallListenerService.dialersFactory = null
    }

    private fun sbn(
        pkg: String,
        key: Int,
        postTime: Long,
        callType: Int? = null,
        ongoing: Boolean = false,
    ): StatusBarNotification {
        val notification =
            Notification().apply {
                if (callType != null) {
                    // What `Notification.CallStyle` writes: its template and the call type.
                    extras.putString(Notification.EXTRA_TEMPLATE, Notification.CallStyle::class.java.name)
                    extras.putInt("android.callType", callType)
                }
                if (ongoing) flags = flags or Notification.FLAG_ONGOING_EVENT
            }
        return ReflectionHelpers.callConstructor(
            StatusBarNotification::class.java,
            ClassParameter.from(String::class.java, pkg),
            ClassParameter.from(String::class.java, pkg),
            ClassParameter.from(Int::class.javaPrimitiveType, key),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Int::class.javaPrimitiveType, 10_148),
            ClassParameter.from(Int::class.javaPrimitiveType, 1),
            ClassParameter.from(Notification::class.java, notification),
            ClassParameter.from(android.os.UserHandle::class.java, Process.myUserHandle()),
            ClassParameter.from(String::class.java, null),
            ClassParameter.from(Long::class.javaPrimitiveType, postTime),
        )
    }

    @Test
    fun aNotificationOfAnotherAppGoesToA_CallAsItsShapeWithItsPostTime() {
        val telegram = sbn("org.telegram.messenger", 203, 1_727_150_400_123L, callType = 1)

        service.onNotificationPosted(telegram)

        val (read, at) = target.posted.single()
        assertEquals(1_727_150_400_123L, at)
        assertEquals(telegram.key, read.key)
        assertEquals("org.telegram.messenger", read.packageName)
        assertEquals(AppCallShape.RINGING, AppCallParser.shape(read))
    }

    @Test
    fun theOwnNotificationsAreSkipped() {
        service.onNotificationPosted(sbn(context.packageName, 1, 10L, callType = 1))
        service.onNotificationRemoved(sbn(context.packageName, 1, 10L), null, 8)

        assertTrue(target.posted.isEmpty())
        assertTrue(target.removed.isEmpty())
    }

    @Test
    fun aRemovalGoesToA_CallByKey() {
        val telegram = sbn("org.telegram.messenger", 203, 5L, callType = 1)

        service.onNotificationRemoved(telegram, null, 8)

        assertEquals(telegram.key, target.removed.single().first)
    }

    @Test
    fun theStandingNotificationsAreFollowedOldestFirstWhenTheListenerConnects() {
        val shadow = shadowOf(service) as ShadowNotificationListenerService
        shadow.addActiveNotification(sbn(TELEGRAM, 203, 300L, callType = 1))
        shadow.addActiveNotification(sbn(context.packageName, 2, 200L))
        shadow.addActiveNotification(sbn("com.google.android.gm", 3, 100L))
        shadow.addActiveNotification(sbn("com.spotify.music", 4, 50L, ongoing = true))

        service.onListenerConnected()

        assertEquals(
            listOf("com.spotify.music" to 50L, TELEGRAM to 300L),
            target.posted.map { (n, at) -> n.packageName to at },
        )
        assertEquals("the capability is read again", listOf(true), target.changes.map { it.first })
        assertEquals(0, shadow.unbindRequestCount)
    }

    @Test
    fun onlyCallStyleAndOngoingNotificationsAreQueued() {
        service.onNotificationPosted(sbn("com.google.android.gm", 1, 10L))
        service.onNotificationPosted(sbn("com.spotify.music", 2, 20L, ongoing = true))
        service.onNotificationPosted(sbn(TELEGRAM, 3, 30L, callType = 2))

        assertEquals(listOf(20L, 30L), target.posted.map { it.second })
    }

    @Test
    fun theNotificationsOfTheCellularDialersAreDropped() {
        service.onNotificationPosted(sbn(DIALER, 1, 10L, callType = 1))
        service.onNotificationPosted(sbn("com.android.server.telecom", 2, 20L, callType = 1))
        service.onNotificationPosted(sbn("com.android.phone", 3, 30L, ongoing = true))
        service.onNotificationPosted(sbn(TELEGRAM, 4, 40L, callType = 1))

        assertEquals(listOf(TELEGRAM), target.posted.map { it.first.packageName })
    }

    @Test
    fun theDialersAreAskedOnlyForANotificationThatMayBeACall() {
        var asked = 0
        AppCallListenerService.dialersFactory = { DialerPackages { setOf(DIALER).also { asked++ } } }
        val fresh = Robolectric.buildService(AppCallListenerService::class.java).create().get()

        fresh.onNotificationPosted(sbn("com.google.android.gm", 1, 10L))
        fresh.onNotificationPosted(sbn(context.packageName, 2, 20L, callType = 1))
        assertEquals(0, asked)

        fresh.onNotificationPosted(sbn(TELEGRAM, 3, 30L, callType = 1))
        assertEquals(1, asked)
    }

    @Test
    fun aFailingNotificationIsSkippedAndTheListenerGoesOn() {
        target.failingPackage = ZALO
        val shadow = shadowOf(service) as ShadowNotificationListenerService
        shadow.addActiveNotification(sbn(ZALO, 1, 10L, callType = 1))
        shadow.addActiveNotification(sbn(TELEGRAM, 203, 20L, callType = 1))

        service.onListenerConnected()
        service.onNotificationPosted(sbn(ZALO, 2, 30L, callType = 1))
        service.onNotificationPosted(sbn(TELEGRAM, 204, 40L, callType = 1))

        assertEquals(listOf(20L, 40L), target.posted.map { it.second })
        assertEquals(listOf(true), target.changes.map { it.first })
    }

    @Test
    fun aFailingTargetNeverCrashesTheCallbacks() {
        AppCallListenerService.targetFactory = { error("A-CALL is not there") }
        val broken = Robolectric.buildService(AppCallListenerService::class.java).create().get()

        broken.onListenerConnected()
        broken.onNotificationPosted(sbn(TELEGRAM, 1, 10L, callType = 1))
        broken.onNotificationRemoved(sbn(TELEGRAM, 1, 10L), null, 8)
        broken.onListenerDisconnected()
    }

    @Test
    fun beforeTheSettingsAreLoadedTheListenerReadsNothingThenFollowsThem() {
        target.wanted = null
        val shadow = shadowOf(service) as ShadowNotificationListenerService
        shadow.addActiveNotification(sbn(TELEGRAM, 203, 100L, callType = 1))

        service.onListenerConnected()
        service.onNotificationPosted(sbn(TELEGRAM, 204, 150L, callType = 1))
        assertTrue(target.posted.isEmpty())
        assertEquals(0, shadow.unbindRequestCount)

        target.wanted = true
        AppCallListenerService.follow(context, wanted = true)

        assertEquals(listOf(100L), target.posted.map { it.second })
        assertEquals(listOf(true), target.changes.map { it.first })
        assertEquals("no rebind: it was bound", 0, ShadowNotificationListenerService.getRebindRequestCount())
    }

    @Test
    fun settingsLoadedAsOffUnbindAWaitingListener() {
        target.wanted = null
        service.onListenerConnected()

        target.wanted = false
        AppCallListenerService.follow(context, wanted = false)

        assertTrue(target.posted.isEmpty())
        assertEquals(1, (shadowOf(service) as ShadowNotificationListenerService).unbindRequestCount)
    }

    @Test
    fun theCapabilityChecksTheAccessOfThisVeryListener() {
        assertEquals(AppCallListenerService::class.java.name, NotificationAccess.LISTENER_CLASS)
    }

    @Test
    fun disconnectingEndsTheCallsAndRereadsTheCapability() {
        service.onListenerConnected()
        service.onListenerDisconnected()

        assertEquals(listOf(true, false), target.changes.map { it.first })
    }

    @Test
    fun withAppCallsOffTheListenerReadsNothingAndUnbindsItself() {
        target.wanted = false
        val shadow = shadowOf(service) as ShadowNotificationListenerService
        shadow.addActiveNotification(sbn(TELEGRAM, 203, 100L, callType = 1))

        service.onListenerConnected()

        assertTrue(target.posted.isEmpty())
        assertEquals(1, shadow.unbindRequestCount)
    }

    @Test
    fun followingTheSettingRebindsWhenWantedAndUnbindsWhenNot() {
        service.onListenerConnected()

        AppCallListenerService.follow(context, wanted = false)
        assertEquals(1, (shadowOf(service) as ShadowNotificationListenerService).unbindRequestCount)

        AppCallListenerService.follow(context, wanted = true)
        assertEquals(1, ShadowNotificationListenerService.getRebindRequestCount())
        assertFalse(ComponentName(context, AppCallListenerService::class.java).flattenToString().isEmpty())
    }

    private companion object {
        const val TELEGRAM = "org.telegram.messenger"
        const val ZALO = "com.zing.zalo"
        const val DIALER = "com.google.android.dialer"
    }
}
