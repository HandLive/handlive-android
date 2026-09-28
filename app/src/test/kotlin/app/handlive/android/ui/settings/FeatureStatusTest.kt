package app.handlive.android.ui.settings

import app.handlive.android.core.strings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * SET-01 field 10 for SMS and calls: the card status from SET-02 field 7 or 10, telephony and the permissions
 * (steps 8, 10).
 */
class FeatureStatusTest {
    @Test
    fun theStatusFollowsTheSwitchTelephonyAndThePermissions() {
        assertEquals(FeatureStatus.ON, FeatureAccess().status(enabled = true))
        assertEquals(FeatureStatus.OFF, FeatureAccess(missing = setOf("READ_SMS")).status(enabled = false))
        assertEquals(FeatureStatus.UNSUPPORTED, FeatureAccess(telephony = false).status(enabled = true))
        assertEquals(
            FeatureStatus.NEEDS_PERMISSION,
            FeatureAccess(missing = setOf("READ_SMS", "READ_CONTACTS")).status(enabled = true),
        )
    }

    @Test
    fun theCallsCardFollowsItsOwnSwitchAndPermissions() {
        val ui =
            SettingsUiState(
                sms = FeatureAccess(missing = setOf("READ_SMS")),
                calls = FeatureAccess(missing = setOf("ANSWER_PHONE_CALLS")),
            )
        assertEquals(FeatureStatus.NEEDS_PERMISSION, ui.callStatus)
        assertEquals(FeatureStatus.OFF, ui.copy(settings = ui.settings.copy(callEnabled = false)).callStatus)
        assertEquals(
            FeatureStatus.NEEDS_PERMISSION,
            ui.copy(settings = ui.settings.copy(callEnabled = false)).smsStatus,
        )
        assertEquals(FeatureStatus.ON, ui.copy(calls = FeatureAccess()).callStatus)
        assertEquals(FeatureStatus.UNSUPPORTED, ui.copy(calls = FeatureAccess(telephony = false)).callStatus)
    }

    @Test
    fun theRelayErrorsReplaceTheInternetConnectionDescription() {
        val ui = SettingsUiState()
        assertEquals(R.string.settings_internet_connection_description, ui.internetDescription)
        assertEquals(R.string.error_relay_pin_mismatch, ui.copy(relayPinMismatch = true).internetDescription)
        assertEquals(
            R.string.error_relay_device_revoked,
            ui.copy(relayDeviceRevoked = true, relayPinMismatch = true).internetDescription,
        )
    }

    @Test
    fun onlyWhenEveryMissingPermissionIsDeniedForGoodDoesTheCardSendToSettings() {
        val partly = FeatureAccess(missing = setOf("READ_SMS", "READ_CONTACTS"), deniedForGood = setOf("READ_SMS"))
        val all = partly.copy(deniedForGood = setOf("READ_SMS", "READ_CONTACTS"))

        assertEquals(FeatureStatus.NEEDS_PERMISSION, partly.status(enabled = true))
        assertEquals(FeatureStatus.PERMISSION_DENIED, all.status(enabled = true))
        assertEquals(R.string.permission_grant, permissionAction(partly.status(enabled = true)))
        assertEquals(R.string.common_open_settings, permissionAction(all.status(enabled = true)))
        assertNull(permissionAction(FeatureStatus.ON))
        assertNull(permissionAction(FeatureStatus.UNSUPPORTED))
    }
}
