package app.handlive.android.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.ui.UiSamples
import app.handlive.android.ui.main.StatusBanners
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/**
 * SET-02 field 39: on without the Accessibility service, a tap on Auto-Send on Copy asks before anything changes;
 * Turn On and Send Manually go through the usual setting, Cancel changes nothing, and every other state applies the
 * tap at once.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutoSendNotOnSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val consented = HandLiveSettings(clipA11yConsentAt = 1L)
    private val notOn = SettingsUiState(consented, accessibilityServiceOn = false)
    private val calls = mutableListOf<Boolean>()
    private val actions =
        object : SettingsActions by UiSamples.NoActions {
            override fun setAutoSend(enabled: Boolean) {
                calls += enabled
            }
        }

    private val title = "Turn On Auto-Send on Copy?"
    private val switch = isToggleable() and hasText("Auto-Send on Copy")

    private fun show(state: SettingsUiState) {
        compose.setContent { HandLiveTheme { SettingsScreen(state, StatusBanners(), actions, "English") } }
    }

    private fun tapSwitch() = compose.onNode(switch, useUnmergedTree = false).performClick()

    @Test
    fun aTapAsksAndChangesNothingYet() {
        show(notOn)
        tapSwitch()
        compose.onNodeWithText(title).assertExists()
        compose
            .onNodeWithText("HandLive isn't on in Accessibility yet, so what you copy isn't sent automatically.")
            .assertExists()
        compose.onNode(switch).assertIsOn()
        assertEquals(emptyList<Boolean>(), calls)
    }

    @Test
    fun turnOnGoesTheWayToTheService() {
        show(notOn)
        tapSwitch()
        compose.onNodeWithText("Turn On").performClick()
        assertEquals(listOf(true), calls)
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun sendManuallyTurnsAutomaticSendingOff() {
        show(notOn)
        tapSwitch()
        compose.onNodeWithText("Send Manually").performClick()
        assertEquals(listOf(false), calls)
        compose.onNodeWithText(title).assertDoesNotExist()
    }

    @Test
    fun cancelChangesNothing() {
        show(notOn)
        tapSwitch()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNode(switch).assertIsOn()
        assertEquals(emptyList<Boolean>(), calls)
    }

    @Test
    fun backChangesNothing() {
        show(notOn)
        tapSwitch()
        compose.runOnIdle { ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNode(switch).assertIsOn()
        assertEquals(emptyList<Boolean>(), calls)
    }

    @Test
    fun aTapOutsideChangesNothing() {
        show(notOn)
        tapSwitch()
        // The sheet's own window: its scrim fills it, the actions sit at the bottom.
        compose.onAllNodes(isRoot()).onLast().performTouchInput { click(Offset(10f, 10f)) }
        compose.onNodeWithText(title).assertDoesNotExist()
        compose.onNode(switch).assertIsOn()
        assertEquals(emptyList<Boolean>(), calls)
    }

    @Test
    fun theSheetClosesWhenAutoSendIsTurnedOffElsewhere() {
        var state by mutableStateOf(notOn)
        compose.setContent { HandLiveTheme { SettingsScreen(state, StatusBanners(), actions, "English") } }
        tapSwitch()
        compose.onNodeWithText(title).assertExists()
        compose.runOnIdle { state = notOn.copy(settings = consented.copy(clipAutoSend = false)) }
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals("nothing written by the sheet", emptyList<Boolean>(), calls)
    }

    @Test
    fun aWorkingServiceTurnsOffAtOnce() {
        show(SettingsUiState(consented, accessibilityServiceOn = true))
        tapSwitch()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(listOf(false), calls)
    }

    @Test
    fun offTurnsOnAtOnce() {
        show(SettingsUiState(consented.copy(clipAutoSend = false)))
        tapSwitch()
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(listOf(true), calls)
    }

    @Test
    fun theSheetClosesOnceTheServiceIsOn() {
        var state by mutableStateOf(notOn)
        compose.setContent { HandLiveTheme { SettingsScreen(state, StatusBanners(), actions, "English") } }
        tapSwitch()
        compose.onNodeWithText(title).assertExists()
        compose.runOnIdle { state = notOn.copy(accessibilityServiceOn = true) }
        compose.onNodeWithText(title).assertDoesNotExist()
        // A later resume without the service does not bring it back by itself.
        compose.runOnIdle { state = notOn }
        compose.onNodeWithText(title).assertDoesNotExist()
        assertEquals(emptyList<Boolean>(), calls)
    }

    @Test
    fun theSheetSurvivesARecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { HandLiveTheme { SettingsScreen(notOn, StatusBanners(), actions, "English") } }
        tapSwitch()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(title).assertExists()
    }
}
