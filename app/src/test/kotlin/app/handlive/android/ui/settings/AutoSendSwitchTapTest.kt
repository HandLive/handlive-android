package app.handlive.android.ui.settings

import app.handlive.android.core.data.settings.HandLiveSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/** SET-02 field 2: only the "Auto-send isn't on yet" state asks before the switch changes. */
class AutoSendSwitchTapTest {
    private val consented = HandLiveSettings(clipA11yConsentAt = 1L)

    @Test
    fun onWithoutTheServiceAsks() {
        val notOn = SettingsUiState(consented, accessibilityServiceOn = false)
        assertEquals(AutoSendStatus.NEEDS_ACCESSIBILITY, notOn.autoSendStatus)
        assertEquals(AutoSendSwitchTap.ASK, autoSendSwitchTap(notOn.autoSendStatus))
        val neverAgreed = SettingsUiState(HandLiveSettings(), accessibilityServiceOn = false)
        assertEquals(
            "a fresh install: on, no consent yet",
            AutoSendSwitchTap.ASK,
            autoSendSwitchTap(neverAgreed.autoSendStatus),
        )
    }

    @Test
    fun onWithTheServiceAndOffApplyAtOnce() {
        val on = SettingsUiState(consented, accessibilityServiceOn = true)
        assertEquals(
            "turning a working service off is meant",
            AutoSendSwitchTap.APPLY,
            autoSendSwitchTap(on.autoSendStatus),
        )
        val off = SettingsUiState(consented.copy(clipAutoSend = false))
        assertEquals("turning on goes the usual way", AutoSendSwitchTap.APPLY, autoSendSwitchTap(off.autoSendStatus))
    }
}
