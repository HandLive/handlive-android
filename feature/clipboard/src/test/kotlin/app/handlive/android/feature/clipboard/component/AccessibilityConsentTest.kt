package app.handlive.android.feature.clipboard.component

import android.content.Context
import android.provider.Settings
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.core.data.settings.SettingsStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CLIP-01 A2: "Agree" records the consent; "Send Manually" turns auto-send off; the system page to go to. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AccessibilityConsentTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = TestScope(UnconfinedTestDispatcher())
    private val store =
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope.backgroundScope) {
                folder.newFile("handlive_settings.preferences_pb").also { it.delete() }
            },
        )
    private val consent = AccessibilityConsent(context, store)

    @Test
    fun agreeRecordsTheConsentAndSendManuallyTurnsAutoSendOff() =
        scope.runTest {
            assertEquals(null, store.current().clipA11yConsentAt)
            consent.agree(1_727_150_000_000L)
            assertEquals(1_727_150_000_000L, store.current().clipA11yConsentAt)
            assertEquals(true, store.current().clipAutoSend)
            consent.sendManually()
            assertEquals(false, store.current().clipAutoSend)
        }

    @Test
    fun agreeLeadsToTheAccessibilitySettings() {
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, consent.settingsIntent().action)
        assertFalse(consent.isServiceEnabled())
    }
}
