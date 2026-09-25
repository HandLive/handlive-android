package app.handlive.android.ui.onboarding

import app.handlive.android.ui.system.Manufacturer
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.PhoneEnvironmentReader
import app.handlive.android.ui.system.UnusedAppPause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SET-01 part A: each step skips itself when already satisfied; at most five screens. */
class SetupStepsTest {
    private val fresh =
        PhoneEnvironment(
            notificationPermissionRuntime = true,
            notificationsAllowed = false,
            batteryExempt = false,
            manufacturer = Manufacturer.XIAOMI,
            unusedAppPause = UnusedAppPause.ENABLED,
            restrictedSettingsLikely = false,
        )

    @Test
    fun aNewPhoneOnAndroid13GoesThroughEveryStep() {
        assertEquals(SetupStep.NOTIFICATIONS, SetupSteps.afterWelcome(fresh))
        assertEquals(SetupStep.BACKGROUND, SetupSteps.afterServiceStarted(fresh))
        assertEquals(SetupStep.AUTOSTART, SetupSteps.afterBackground(fresh))
    }

    @Test
    fun satisfiedStepsAreSkipped() {
        val ready =
            fresh.copy(
                notificationPermissionRuntime = false,
                batteryExempt = true,
                manufacturer = null,
                unusedAppPause = UnusedAppPause.NOT_AVAILABLE,
            )
        assertEquals(SetupStep.START_SERVICE, SetupSteps.afterWelcome(ready))
        assertEquals(SetupStep.DONE, SetupSteps.afterServiceStarted(ready))
        assertEquals(SetupStep.START_SERVICE, SetupSteps.afterWelcome(fresh.copy(notificationsAllowed = true)))
        assertEquals(
            SetupStep.AUTOSTART,
            SetupSteps.afterBackground(ready.copy(unusedAppPause = UnusedAppPause.ENABLED)),
        )
    }

    @Test
    fun manufacturersFollowTheConfigurationTable() {
        assertEquals(Manufacturer.XIAOMI, PhoneEnvironmentReader.manufacturerOf("Xiaomi"))
        assertEquals(Manufacturer.XIAOMI, PhoneEnvironmentReader.manufacturerOf("POCO"))
        assertEquals(Manufacturer.OPPO, PhoneEnvironmentReader.manufacturerOf("OnePlus"))
        assertEquals(Manufacturer.OPPO, PhoneEnvironmentReader.manufacturerOf("realme"))
        assertEquals(Manufacturer.SAMSUNG, PhoneEnvironmentReader.manufacturerOf("samsung"))
        assertNull(PhoneEnvironmentReader.manufacturerOf("Google"))
    }
}
