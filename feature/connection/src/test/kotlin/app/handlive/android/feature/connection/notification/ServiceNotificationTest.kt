package app.handlive.android.feature.connection.notification

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.feature.connection.ConnectedPeer
import app.handlive.android.feature.connection.session.PeerSession
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CONN-01 field 6 and SET-01 API 3: channel `hl_service` and the three texts of the ongoing notification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ServiceNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun textFollowsTheConnectedClients() {
        assertEquals("Waiting for a connection", text(emptyList()))
        assertEquals("Connected to MacBook của Lan", text(listOf(peer("MacBook của Lan"))))
        assertEquals("Connected to 2 devices", text(listOf(peer("MacBook"), peer("iPad"))))
    }

    @Test
    @Config(qualifiers = "vi")
    fun vietnameseTexts() {
        assertEquals("Đang chờ kết nối", text(emptyList()))
        assertEquals("Đã kết nối với 2 thiết bị", text(listOf(peer("MacBook"), peer("iPad"))))
    }

    @Test
    fun channelsAreLowImportanceWithCatalogNames() {
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        val service = manager.getNotificationChannel(NotificationChannels.SERVICE)
        assertEquals("Connection service", service.name)
        assertEquals(NotificationManager.IMPORTANCE_LOW, service.importance)
        assertEquals(false, service.canShowBadge())
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationChannels.CLIPBOARD).importance,
        )
        assertEquals("Permissions", manager.getNotificationChannel(NotificationChannels.PERMISSION).name)
        assertEquals(
            NotificationManager.IMPORTANCE_LOW,
            manager.getNotificationChannel(NotificationChannels.PERMISSION).importance,
        )
    }

    @Test
    fun everyChannelCarriesItsCatalogDescription() {
        NotificationChannels.createAll(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        assertEquals(
            "Connection status with your Mac, iPhone, and iPad, and the Send Clipboard button.",
            manager.getNotificationChannel(NotificationChannels.SERVICE).description,
        )
        assertEquals(
            "Blocked sensitive content, clipboard conflicts, and image transfer progress.",
            manager.getNotificationChannel(NotificationChannels.CLIPBOARD).description,
        )
        assertEquals(
            "Suggestions to grant a permission when a Mac or iPhone needs a feature of this phone.",
            manager.getNotificationChannel(NotificationChannels.PERMISSION).description,
        )
    }

    @Test
    fun notificationIsOngoingSilentServiceCategoryWithoutTitle() {
        val notification = ServiceNotification.build(context, emptyList(), openApp = null, sendClipboard = null)
        assertEquals(NotificationCompat.CATEGORY_SERVICE, notification.category)
        assertEquals(null, NotificationCompat.getContentTitle(notification))
        assertEquals(true, notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }

    private fun text(peers: List<ConnectedPeer>): String =
        NotificationCompat
            .getContentText(ServiceNotification.build(context, peers, openApp = null, sendClipboard = null))
            .toString()

    private fun peer(name: String) = ConnectedPeer("id-$name", name, PeerPlatform.MACOS, PeerSession.Channel.LAN)
}
