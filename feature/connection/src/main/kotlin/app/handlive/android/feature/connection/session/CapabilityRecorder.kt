package app.handlive.android.feature.connection.session

import app.handlive.android.core.protocol.capability.CapabilityData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * The peer's capability of one session, kept in `features_json` (CONN-01 step 10): each new one while the session
 * runs, and the latest once more when the session ends unless it is already written. A session that ends right after
 * `capability/hello` thus still leaves the capability that decides the pushes to that client (SMS-02 step 10, CALL-01
 * step 5).
 */
internal class CapabilityRecorder(
    private val capability: StateFlow<CapabilityData?>,
    private val record: suspend (CapabilityData) -> Unit,
) {
    @Volatile
    private var written: CapabilityData? = null
    private var following: Job? = null

    fun start(scope: CoroutineScope) {
        following = capability.filterNotNull().onEach(::write).launchIn(scope)
    }

    /** The session ended: stop following, then write the latest capability if it is not written yet, uncancellable. */
    suspend fun finish() {
        withContext(NonCancellable) {
            following?.cancelAndJoin()
            capability.value?.takeIf { it !== written }?.let { write(it) }
        }
    }

    private suspend fun write(value: CapabilityData) {
        record(value)
        written = value
    }
}
