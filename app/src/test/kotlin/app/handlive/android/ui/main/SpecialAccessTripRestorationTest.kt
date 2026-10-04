package app.handlive.android.ui.main

import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SET-01 E8, E11: the page HandLive opened lives in the saved state, so the activity recreated on the way (font or
 * display size changed in Accessibility, or the process restored) still knows where the user comes back from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpecialAccessTripRestorationTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePageOpenedSurvivesARecreation() {
        val restoration = StateRestorationTester(compose)
        var trip: SpecialAccessTrip? = null
        restoration.setContent { trip = rememberSpecialAccessTrip() }
        compose.runOnIdle { trip!!.leaveFor(SystemPage.ACCESSIBILITY) }
        trip = null
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(SystemPage.ACCESSIBILITY, trip!!.takeReturn())
            assertNull(trip!!.takeReturn())
        }
    }
}
