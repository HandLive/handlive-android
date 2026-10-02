package app.handlive.android.ui.system

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.call.appcall.AppCallListenerService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-01 API 9: where "Continue" on the Notification access primer leads, by API level. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemPagesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fromApi30ItOpensTheEntryOfTheCallListenerInNotificationAccess() {
        val intent = SystemPages.notificationListenerSettings(context, sdk = 30)

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS, intent.action)
        assertEquals(
            ComponentName(context, AppCallListenerService::class.java).flattenToString(),
            intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME),
        )
    }

    @Test
    fun onApi29ItOpensTheListOfApps() {
        val intent = SystemPages.notificationListenerSettings(context, sdk = 29)

        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, intent.action)
        assertNull(intent.getStringExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME))
    }

    @Test
    fun theFallbackWhenTheEntryIsNotSupportedIsTheList() {
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, SystemPages.notificationListenerList().action)
    }
}
