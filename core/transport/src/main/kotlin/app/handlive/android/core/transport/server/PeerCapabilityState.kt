package app.handlive.android.core.transport.server

import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.transport.capability.EffectiveFeatures
import app.handlive.android.core.transport.capability.Feature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The peer's latest capability snapshot and the features effective between the two devices (0.7.2): replaced as a
 * whole by every `capability/hello|update`, recomputed when this phone's own capability changes.
 */
internal class PeerCapabilityState(
    private val local: () -> CapabilityData,
) {
    private val peerFlow = MutableStateFlow<CapabilityData?>(null)
    private val effectiveFlow = MutableStateFlow<Set<Feature>>(emptySet())

    val peer: StateFlow<CapabilityData?> = peerFlow.asStateFlow()
    val effective: StateFlow<Set<Feature>> = effectiveFlow.asStateFlow()

    fun apply(peer: CapabilityData) {
        peerFlow.value = peer
        effectiveFlow.value = EffectiveFeatures.compute(local(), peer)
    }

    fun recompute(localNow: CapabilityData) {
        peerFlow.value?.let { effectiveFlow.value = EffectiveFeatures.compute(localNow, it) }
    }
}
