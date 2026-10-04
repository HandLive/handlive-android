package app.handlive.android.ui.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.ui.system.RestrictedSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-01 E8, E11 through the lifecycle: field 14 once per return, none after Back, none after App info. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpecialAccessReturnEffectTest {
    @get:Rule
    val compose = createComposeRule()

    private val owner =
        object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }

    private var now =
        SpecialAccessState(
            restriction = RestrictedSettings.LIKELY,
            settings = HandLiveSettings(clipA11yConsentAt = 1L),
            accessibilityOn = false,
            notificationAccess = false,
        )
    private val shown = mutableListOf<Route>()
    private val trip = SpecialAccessTrip()

    private fun start() {
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                SpecialAccessReturnEffect(trip, read = { now }, show = { shown += it })
            }
        }
        compose.waitForIdle()
    }

    /** The user leaves for [page] (the app pauses) and comes back (it resumes). */
    private fun trip(page: SystemPage?) {
        compose.runOnIdle {
            owner.registry.currentState = Lifecycle.State.STARTED
            page?.let(trip::leaveFor)
            owner.registry.currentState = Lifecycle.State.RESUMED
        }
        compose.waitForIdle()
    }

    @Test
    fun backFromAccessibilityWithoutTheServiceShowsField14OnceAndBackDoesNotBringItAgain() {
        start()
        trip(SystemPage.ACCESSIBILITY)
        assertEquals(listOf(Route.RestrictedSetting(openAppInfo = true)), shown)
        trip(null)
        assertEquals("a second resume (shade, rotation, Back) shows nothing more", 1, shown.size)
    }

    @Test
    fun backFromNotificationAccessWithoutItShowsItsField14() {
        start()
        trip(SystemPage.NOTIFICATION_ACCESS)
        assertEquals(listOf(Route.RestrictedSetting(notificationAccess = true, openAppInfo = true)), shown)
    }

    @Test
    fun backFromAppInfoNavigatesNowhere() {
        start()
        trip(SystemPage.APP_INFO)
        assertEquals(emptyList<Route>(), shown)
    }

    @Test
    fun autoSendOffStaysQuietEvenWhenBlocked() {
        now = now.copy(restriction = RestrictedSettings.BLOCKED, settings = now.settings.copy(clipAutoSend = false))
        start()
        trip(SystemPage.ACCESSIBILITY)
        assertEquals(emptyList<Route>(), shown)
    }

    @Test
    fun theServiceTurnedOnEndsTheTrip() {
        now = now.copy(accessibilityOn = true)
        start()
        trip(SystemPage.ACCESSIBILITY)
        assertEquals(emptyList<Route>(), shown)
    }
}
