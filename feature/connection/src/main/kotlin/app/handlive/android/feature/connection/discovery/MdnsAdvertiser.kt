package app.handlive.android.feature.connection.discovery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the advertiser registers: service `_handlive._tcp`, instance name, port, TXT. A new [networkGeneration]
 * (the default network changed) forces a fresh registration even when nothing else changed.
 */
data class MdnsRegistration(
    val serviceName: String,
    val port: Int,
    val txt: MdnsTxtRecord,
    val networkGeneration: Int = 0,
)

/** Registers one DNS-SD service (Android: `NsdManager`); fakes stand in for it in JVM tests. */
interface MdnsRegistrar {
    /** Registers [registration]; `true` on success (`onServiceRegistered`), `false` on `onRegistrationFailed`. */
    suspend fun register(registration: MdnsRegistration): Boolean

    suspend fun unregister()
}

/**
 * Keeps the phone advertised (CONN-01 API 1). `NsdManager` cannot update TXT in place, so every change unregisters
 * and registers again, at most once per [minInterval]; a failed registration is retried after 5 s, 30 s and then
 * every 5 min (clients still reach the phone through `last_host` meanwhile).
 */
class MdnsAdvertiser(
    private val registrar: MdnsRegistrar,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minInterval: Duration = 1.seconds,
    private val retryDelays: List<Duration> = listOf(5.seconds, 30.seconds, 5.minutes),
) {
    private val lock = Mutex()
    private var wanted: MdnsRegistration? = null
    private var registered: MdnsRegistration? = null
    private var lastApplied = Long.MIN_VALUE / 2
    private var pending: Job? = null
    private var failures = 0

    /** Advertise [registration] (replacing what is advertised); coalesced to one update per [minInterval]. */
    fun advertise(registration: MdnsRegistration) {
        scope.launch {
            lock.withLock {
                wanted = registration
                if (pending?.isActive != true) pending = scope.launch { applyWhenDue() }
            }
        }
    }

    /** Stop advertising (service stopped). */
    suspend fun stop() {
        lock.withLock {
            wanted = null
            pending?.cancel()
            if (registered != null) registrar.unregister()
            registered = null
        }
    }

    private suspend fun applyWhenDue() {
        val wait = lastApplied + minInterval.inWholeMilliseconds - clock()
        if (wait > 0) delay(wait)
        val success =
            lock.withLock {
                val target = wanted ?: return
                if (target == registered) return
                lastApplied = clock()
                if (registered != null) registrar.unregister()
                registered = null
                registrar.register(target).also { if (it) registered = target }
            }
        if (success) {
            failures = 0
            // A change that arrived while registering is applied on the next round.
            lock.withLock { if (wanted != registered) pending = scope.launch { applyWhenDue() } }
        } else {
            delay(retryDelays[minOf(failures, retryDelays.lastIndex)])
            failures++
            lock.withLock { pending = scope.launch { applyWhenDue() } }
        }
    }
}
