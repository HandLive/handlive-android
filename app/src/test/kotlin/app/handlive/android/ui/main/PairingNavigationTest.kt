package app.handlive.android.ui.main

import app.handlive.android.core.strings.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** SET-01 step 8: where "Done" on the pairing result leads, and the back button of the feature list. */
class PairingNavigationTest {
    @Test
    fun afterThePhonesFirstPairTheFeatureListOpensOverTheDevicesTab() {
        val stack = mutableListOf<Route>(Route.PairDevice)
        stack.closePairing(firstPair = true)
        assertEquals(listOf(Route.Permissions(afterFirstPairing = true)), stack)
        // The back button names the screen it returns to (03-android.md "Navigation").
        assertEquals(R.string.pairing_devices, Route.Permissions(afterFirstPairing = true).backLabel)
    }

    @Test
    fun aSecondTapOnDoneOpensTheFeatureListOnce() {
        val stack = mutableListOf<Route>(Route.PairDevice)
        stack.closePairing(firstPair = true)
        stack.closePairing(firstPair = true)
        assertEquals(listOf(Route.Permissions(afterFirstPairing = true)), stack)
    }

    @Test
    fun anyOtherPairGoesBackToTheTabs() {
        val stack = mutableListOf<Route>(Route.PairDevice)
        stack.closePairing(firstPair = false)
        assertEquals(emptyList<Route>(), stack)
    }

    @Test
    fun theFeatureListOpenedFromSettingsGoesBackToSettings() {
        assertEquals(R.string.settings_title, Route.Permissions().backLabel)
    }
}
