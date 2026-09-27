package app.handlive.android.ui.settings

import app.handlive.android.core.strings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** SET-01 field 10 for SMS: the card status from SET-02 field 7, telephony and the permissions (steps 8, 10). */
class FeatureStatusTest {
    @Test
    fun theStatusFollowsTheSwitchTelephonyAndThePermissions() {
        assertEquals(FeatureStatus.ON, SmsAccessState().status(enabled = true))
        assertEquals(FeatureStatus.OFF, SmsAccessState(missing = setOf("READ_SMS")).status(enabled = false))
        assertEquals(FeatureStatus.UNSUPPORTED, SmsAccessState(telephony = false).status(enabled = true))
        assertEquals(
            FeatureStatus.NEEDS_PERMISSION,
            SmsAccessState(missing = setOf("READ_SMS", "READ_CONTACTS")).status(enabled = true),
        )
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
        val partly = SmsAccessState(missing = setOf("READ_SMS", "READ_CONTACTS"), deniedForGood = setOf("READ_SMS"))
        val all = partly.copy(deniedForGood = setOf("READ_SMS", "READ_CONTACTS"))

        assertEquals(FeatureStatus.NEEDS_PERMISSION, partly.status(enabled = true))
        assertEquals(FeatureStatus.PERMISSION_DENIED, all.status(enabled = true))
        assertEquals(R.string.permission_grant, smsPermissionAction(partly.status(enabled = true)))
        assertEquals(R.string.common_open_settings, smsPermissionAction(all.status(enabled = true)))
        assertNull(smsPermissionAction(FeatureStatus.ON))
        assertNull(smsPermissionAction(FeatureStatus.UNSUPPORTED))
    }
}
