package app.handlive.android.core.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

/** DataStore keys, named exactly as in 0.9.5. */
object SettingsKeys {
    val SETUP_STARTED_AT = longPreferencesKey("setup.started_at")
    val SETUP_COMPLETED_AT = longPreferencesKey("setup.completed_at")
    val PERM_REQUESTED = stringSetPreferencesKey("perm.requested")
    val FEATURE_CLIPBOARD = booleanPreferencesKey("feature.clipboard")
    val FEATURE_SMS = booleanPreferencesKey("feature.sms")
    val FEATURE_CALL = booleanPreferencesKey("feature.call")
    val FEATURE_CALL_AUDIO = booleanPreferencesKey("feature.call_audio")
    val ALLOW_OPUS_FALLBACK = booleanPreferencesKey("call_audio.allow_opus_fallback")
    val FEATURE_CAMERA = booleanPreferencesKey("feature.camera")
    val RELAY_ENABLED = booleanPreferencesKey("relay.enabled")
    val CLIP_AUTO_SEND = booleanPreferencesKey("clip.auto_send")
    val CLIP_A11Y_CONSENT_AT = longPreferencesKey("clip.a11y_consent_at")
    val CLIP_SEND_IMAGES = booleanPreferencesKey("clip.send_images")
    val CLIP_BLOCK_SENSITIVE = booleanPreferencesKey("clip.block_sensitive")
    val CLIP_AUTO_CLEAR_S = intPreferencesKey("clip.auto_clear_s")
}

/**
 * Reads and writes the 0.9.5 keys (SET-01, SET-02). One instance per process: DataStore forbids two stores on one
 * file. A write that fails leaves the old value in place and throws (SET-02 E4).
 */
class SettingsStore(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<HandLiveSettings> =
        dataStore.data
            .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
            .map(::toSettings)

    suspend fun current(): HandLiveSettings = settings.first()

    suspend fun <T> set(
        key: Preferences.Key<T>,
        value: T,
    ) {
        dataStore.edit { it[key] = value }
    }

    suspend fun remove(key: Preferences.Key<*>) {
        dataStore.edit { it.remove(key) }
    }

    /** SET-01 step 2: only the first start is recorded. */
    suspend fun markSetupStarted(now: Long) {
        dataStore.edit { if (it[SettingsKeys.SETUP_STARTED_AT] == null) it[SettingsKeys.SETUP_STARTED_AT] = now }
    }

    suspend fun markSetupCompleted(now: Long) = set(SettingsKeys.SETUP_COMPLETED_AT, now)

    /** SET-01 step 10: remember permissions that were asked, as short names. */
    suspend fun addRequestedPermissions(permissions: Collection<String>) {
        dataStore.edit { it[SettingsKeys.PERM_REQUESTED] = it[SettingsKeys.PERM_REQUESTED].orEmpty() + permissions }
    }

    /** SET-02 A5: every key back to its default. */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    companion object {
        const val FILE_NAME = "handlive_settings"

        fun create(context: Context): SettingsStore =
            SettingsStore(
                PreferenceDataStoreFactory.create {
                    context.applicationContext.preferencesDataStoreFile(FILE_NAME)
                },
            )

        private fun toSettings(prefs: Preferences): HandLiveSettings {
            val defaults = HandLiveSettings()
            return HandLiveSettings(
                setupStartedAt = prefs[SettingsKeys.SETUP_STARTED_AT],
                setupCompletedAt = prefs[SettingsKeys.SETUP_COMPLETED_AT],
                permissionsRequested = prefs[SettingsKeys.PERM_REQUESTED].orEmpty(),
                clipboardEnabled = prefs[SettingsKeys.FEATURE_CLIPBOARD] ?: defaults.clipboardEnabled,
                smsEnabled = prefs[SettingsKeys.FEATURE_SMS] ?: defaults.smsEnabled,
                callEnabled = prefs[SettingsKeys.FEATURE_CALL] ?: defaults.callEnabled,
                callAudioEnabled = prefs[SettingsKeys.FEATURE_CALL_AUDIO] ?: defaults.callAudioEnabled,
                allowOpusFallback = prefs[SettingsKeys.ALLOW_OPUS_FALLBACK] ?: defaults.allowOpusFallback,
                cameraEnabled = prefs[SettingsKeys.FEATURE_CAMERA] ?: defaults.cameraEnabled,
                relayEnabled = prefs[SettingsKeys.RELAY_ENABLED] ?: defaults.relayEnabled,
                clipAutoSend = prefs[SettingsKeys.CLIP_AUTO_SEND] ?: defaults.clipAutoSend,
                clipA11yConsentAt = prefs[SettingsKeys.CLIP_A11Y_CONSENT_AT],
                clipSendImages = prefs[SettingsKeys.CLIP_SEND_IMAGES] ?: defaults.clipSendImages,
                clipBlockSensitive = prefs[SettingsKeys.CLIP_BLOCK_SENSITIVE] ?: defaults.clipBlockSensitive,
                clipAutoClearSeconds =
                    prefs[SettingsKeys.CLIP_AUTO_CLEAR_S]
                        ?.takeIf { it in HandLiveSettings.AUTO_CLEAR_CHOICES }
                        ?: defaults.clipAutoClearSeconds,
            )
        }
    }
}
