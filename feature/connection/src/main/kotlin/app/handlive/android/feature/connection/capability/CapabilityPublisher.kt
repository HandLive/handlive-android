package app.handlive.android.feature.connection.capability

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.server.ControlSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps this phone's capability current (SET-02 API 1): recomputed whenever a setting, the Accessibility service or
 * a permission changes, and sent as one full `capability/update` snapshot on every open session; changes within
 * 300 ms are coalesced.
 */
class CapabilityPublisher(
    private val reader: LocalEnvironmentReader,
) {
    /**
     * This phone's capability for as long as [scope] lives: rebuilt when a setting, the Accessibility service or
     * [environmentVersion] (the app back on screen, a SIM change) changes, and on demand through
     * [LocalCapability.refresh].
     */
    fun state(
        scope: CoroutineScope,
        initialSettings: HandLiveSettings,
        settings: Flow<HandLiveSettings>,
        accessibilityRunning: StateFlow<Boolean>,
        environmentVersion: Flow<Int>,
    ): LocalCapability {
        val latestSettings = settings.stateIn(scope, SharingStarted.Eagerly, initialSettings)
        val capability =
            LocalCapability {
                LocalCapabilityBuilder.build(latestSettings.value, reader.read(accessibilityRunning.value))
            }
        combine(latestSettings, accessibilityRunning, environmentVersion) { _, _, _ -> }
            .onEach { capability.refresh() }
            .launchIn(scope)
        return capability
    }

    @OptIn(FlowPreview::class)
    fun publishUpdates(
        scope: CoroutineScope,
        state: StateFlow<CapabilityData>,
        sessions: () -> List<ControlSession>,
    ) {
        state
            .drop(1)
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MILLIS)
            .onEach { sessions().forEach { session -> runCatching { session.sendCapabilityUpdate() } } }
            .launchIn(scope)
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 300L
    }
}

/**
 * This phone's capability (0.7.2): [state] is what feature modules follow and what `capability/update` carries.
 * Android tells an app nothing when one of its permissions is granted in Settings or with `pm grant`, so [refresh]
 * reads the phone again for each new session's `capability/hello` (SET-01 API 2 logic 4); a change it finds also
 * reaches the open sessions as `capability/update` (SET-01 step 14).
 */
class LocalCapability internal constructor(
    private val read: () -> CapabilityData,
) {
    private val snapshot = MutableStateFlow(read())

    val state: StateFlow<CapabilityData> = snapshot.asStateFlow()

    /** Reads the settings and the phone again, publishes the result and returns it. */
    @Synchronized
    fun refresh(): CapabilityData = read().also { snapshot.value = it }
}
