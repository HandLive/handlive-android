package app.handlive.android.feature.connection.capability

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CallFeature
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * SET-01 API 2 logic 4 and step 14: this phone's capability follows permissions granted while the service runs, when
 * a new session starts as well as when HandLive comes back on screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CapabilityPublisherTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val settings = MutableStateFlow(HandLiveSettings())
    private val accessibilityRunning = MutableStateFlow(false)
    private val environmentVersion = MutableStateFlow(0)

    @Before
    fun phoneWithTelephonyAndNotifications() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setNotificationAccess(true)
    }

    @Test
    fun aNewSessionReadsThePermissionsGrantedWhileTheServiceRuns() =
        runTest {
            val capability = start()
            assertEquals(AndroidPermissions.RUNTIME, capability.state.value.permissionsMissing)

            // `pm grant` or the App info page: Android tells HandLive nothing and the app is not on screen.
            grant(AndroidPermissions.SMS)
            val afterSms = capability.refresh()
            assertEquals(CALL_ONLY, afterSms.permissionsMissing)
            assertEquals(true, afterSms.features.sms?.canSend)

            grant(AndroidPermissions.CALLS)
            val afterCalls = capability.refresh()
            assertEquals(emptyList<String>(), afterCalls.permissionsMissing)
            assertEquals(
                CallFeature(enabled = true, canAnswer = true, canEnd = true, callerId = true, appCalls = true),
                afterCalls.features.call,
            )
            // Feature modules and the open sessions (`capability/update`) follow the same state.
            assertEquals(afterCalls, capability.state.value)
        }

    @Test
    fun notificationAccessIsListedUntilTheUserGrantsItInSettings() =
        runTest {
            setNotificationAccess(false)
            val capability = start()
            assertEquals(
                AndroidPermissions.RUNTIME + AndroidPermissions.NOTIFICATION_LISTENER,
                capability.state.value.permissionsMissing,
            )
            assertEquals(
                false,
                capability.state.value.features.call
                    ?.appCalls,
            )

            // The Notification access page of the system: no broadcast reaches HandLive, the listener service connects.
            setNotificationAccess(true)
            val granted = capability.refresh()

            assertEquals(AndroidPermissions.RUNTIME, granted.permissionsMissing)
            assertEquals(true, granted.features.call?.appCalls)
        }

    @Test
    fun theAppBackOnScreenStillRebuildsTheCapability() =
        runTest {
            val capability = start()
            grant(AndroidPermissions.SMS + AndroidPermissions.CALLS)
            // A-UI onResume → ConnectionRuntime.refreshEnvironment().
            environmentVersion.value++
            runCurrent()
            assertEquals(emptyList<String>(), capability.state.value.permissionsMissing)
        }

    @Test
    fun aSettingChangeStillRebuildsTheCapability() =
        runTest {
            val capability = start()
            settings.value = HandLiveSettings(smsEnabled = false, callEnabled = false)
            runCurrent()
            val rebuilt = capability.state.value
            assertEquals(emptyList<String>(), rebuilt.permissionsMissing)
            assertEquals(false, rebuilt.features.sms?.enabled)
        }

    private fun TestScope.start() =
        CapabilityPublisher(LocalEnvironmentReader(context))
            .state(backgroundScope, settings.value, settings, accessibilityRunning, environmentVersion)
            .also { runCurrent() }

    /** The user allowed (or not) HandLive's listener in Settings › Notification access. */
    private fun setNotificationAccess(granted: Boolean) {
        val listener = ComponentName(context.packageName, NotificationAccess.LISTENER_CLASS)
        shadowOf(context.getSystemService(NotificationManager::class.java))
            .setNotificationListenerAccessGranted(listener, granted)
    }

    private fun grant(permissions: List<String>) =
        shadowOf(context).grantPermissions(*permissions.map(AndroidPermissions::fullName).toTypedArray())

    private companion object {
        val CALL_ONLY = listOf(AndroidPermissions.READ_CALL_LOG, AndroidPermissions.ANSWER_PHONE_CALLS)
    }
}
