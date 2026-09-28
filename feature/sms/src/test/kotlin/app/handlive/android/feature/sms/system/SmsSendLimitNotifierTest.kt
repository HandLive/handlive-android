package app.handlive.android.feature.sms.system

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.db.PeerPlatform
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** SMS-04 field 12: the send-limit notice names the client, on the `permission` channel, from the string catalog. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en")
class SmsSendLimitNotifierTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val mac =
        PeerSession(
            PeerSession.PeerInfo("pair-mac", "pair-mac-device", "Lan's MacBook", PeerPlatform.MACOS),
            PeerSession.Channel.LAN,
            MutableStateFlow(emptySet()),
            MutableStateFlow<CapabilityData?>(null),
            { _, _, _ -> },
            { 0L },
        )

    @Before
    fun setUp() = NotificationChannels.createAll(context)

    @Test
    fun theNoticeNamesTheClientThatWentOverTheLimit() {
        SmsSendLimitNotifier(context).onSendLimited(mac)
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals(NotificationChannels.PERMISSION, posted.channelId)
        assertEquals(
            "HandLive stopped sending messages from Lan's MacBook for now. Too many were sent in a short time.",
            posted.extras.getCharSequence(NotificationCompat.EXTRA_TEXT).toString(),
        )
    }
}
