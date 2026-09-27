package app.handlive.android.feature.call.system

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.connection.notification.OpenRequest
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SET-01 field 17 for calls: "<device> needs call permission on this phone — tap to allow" on the `permission`
 * channel, at most once per 24 h, opening the calls primer; nothing while notifications are off (E1).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class CallPermissionNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private var now = 1_727_150_400_000L
    private val notifier = CallPermissionNotifier(context) { now }
    private val mac =
        PeerSession(
            PeerSession.PeerInfo("pair-mac", "pair-mac-device", "MacBook của Lan", PeerPlatform.MACOS),
            PeerSession.Channel.LAN,
            MutableStateFlow(emptySet()),
            MutableStateFlow<CapabilityData?>(null),
            { _, _, _ -> },
            { now },
        )

    @Before
    fun setUp() {
        NotificationChannels.createAll(context)
        // The app's launcher activity, which the notification opens (a library test has none of its own).
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val info =
            ResolveInfo().apply {
                activityInfo =
                    ActivityInfo().apply {
                        packageName = context.packageName
                        name = "app.handlive.android.MainActivity"
                    }
            }
        shadowOf(context.packageManager).addResolveInfoForIntent(launcher, info)
    }

    @Test
    fun theSuggestionNamesTheDeviceAndOpensTheCallsPrimerOnceADay() {
        notifier.onPermissionMissing(mac, "android.permission.ANSWER_PHONE_CALLS")
        notifier.onPermissionMissing(mac, "android.permission.READ_CALL_LOG")

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationChannels.PERMISSION, posted.channelId)
        assertEquals(
            "MacBook của Lan needs call permission on this phone — tap to allow",
            posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
        )
        val open = shadowOf(posted.contentIntent).savedIntent
        assertEquals(OpenRequest.CALL_PERMISSION, open.getStringExtra(OpenRequest.EXTRA))

        now += 24 * 60 * 60 * 1000L
        notifier.onPermissionMissing(mac, "android.permission.READ_CALL_LOG")
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertTrue("posted again, replacing the first", shadowOf(manager).size() == 1)
    }

    @Test
    fun nothingIsPostedWhileNotificationsAreOff() {
        shadowOf(manager).setNotificationsEnabled(false)
        notifier.onPermissionMissing(mac, "android.permission.ANSWER_PHONE_CALLS")
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
