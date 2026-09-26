package app.handlive.android.feature.connection.capability

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.server.ControlSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Keeps this phone's capability current (SET-02 API 1): recomputed whenever a setting, the Accessibility service
 * or a permission changes, and sent as one full `capability/update` snapshot on every open session; changes within
 * 300 ms are coalesced.
 */
class CapabilityPublisher(
    private val reader: LocalEnvironmentReader,
) {
    fun state(
        scope: CoroutineScope,
        initialSettings: HandLiveSettings,
        settings: Flow<HandLiveSettings>,
        accessibilityRunning: StateFlow<Boolean>,
        environmentVersion: Flow<Int>,
    ): StateFlow<CapabilityData> {
        val initial = LocalCapabilityBuilder.build(initialSettings, reader.read(accessibilityRunning.value))
        return combine(settings, accessibilityRunning, environmentVersion) { current, running, _ ->
            LocalCapabilityBuilder.build(current, reader.read(running))
        }.stateIn(scope, SharingStarted.Eagerly, initial)
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
