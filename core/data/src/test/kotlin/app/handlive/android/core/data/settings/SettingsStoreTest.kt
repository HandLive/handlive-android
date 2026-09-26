package app.handlive.android.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Settings keys and defaults of 0.9.5 (SET-01, SET-02). */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStoreTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val scope = TestScope(UnconfinedTestDispatcher())
    private val store =
        SettingsStore(
            PreferenceDataStoreFactory.create(scope = scope.backgroundScope) {
                folder.newFile("handlive_settings.preferences_pb").also { it.delete() }
            },
        )

    @Test
    fun defaultsMatchTheSpec() =
        scope.runTest {
            val settings = store.current()
            assertEquals(HandLiveSettings(), settings)
            assertEquals(true, settings.clipboardEnabled)
            assertEquals(true, settings.clipAutoSend)
            assertEquals(true, settings.clipSendImages)
            assertEquals(true, settings.clipBlockSensitive)
            assertEquals(60, settings.clipAutoClearSeconds)
            assertEquals(true, settings.relayEnabled)
            assertEquals(false, settings.callAudioEnabled)
            assertEquals(false, settings.cameraEnabled)
            assertNull(settings.setupCompletedAt)
        }

    @Test
    fun writesAreReadBackAndClearRestoresDefaults() =
        scope.runTest {
            store.set(SettingsKeys.FEATURE_CLIPBOARD, false)
            store.set(SettingsKeys.CLIP_AUTO_CLEAR_S, 300)
            store.set(SettingsKeys.RELAY_ENABLED, false)
            val changed = store.current()
            assertEquals(false, changed.clipboardEnabled)
            assertEquals(300, changed.clipAutoClearSeconds)
            assertEquals(false, changed.relayEnabled)

            store.clear()
            assertEquals(HandLiveSettings(), store.current())
        }

    @Test
    fun autoClearOutsideTheOfferedChoicesFallsBackToTheDefault() =
        scope.runTest {
            store.set(SettingsKeys.CLIP_AUTO_CLEAR_S, 42)
            assertEquals(60, store.current().clipAutoClearSeconds)
            store.set(SettingsKeys.CLIP_AUTO_CLEAR_S, 0)
            assertEquals(0, store.current().clipAutoClearSeconds)
        }

    @Test
    fun setupStartIsRecordedOnceAndRequestedPermissionsAccumulate() =
        scope.runTest {
            store.markSetupStarted(100)
            store.markSetupStarted(200)
            assertEquals(100L, store.current().setupStartedAt)
            store.addRequestedPermissions(listOf("CAMERA"))
            store.addRequestedPermissions(listOf("READ_SMS", "CAMERA"))
            assertEquals(setOf("CAMERA", "READ_SMS"), store.current().permissionsRequested)
        }
}
