package app.handlive.android.feature.relay.push

import app.handlive.android.feature.relay.attempt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Retries the pushes waiting in `push_outbox` when they fall due (CONN-04 E2): sleeps until the next one, or until
 * [wake] (a new entry, a new network, a new relay link).
 */
internal class PushOutboxRunner(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val sender: () -> PushSender,
    private val allowed: () -> Boolean,
) {
    private val wakes = MutableStateFlow(0)
    private var loop: Job? = null

    fun start() {
        if (loop?.isActive == true) return
        loop =
            scope.launch {
                while (true) {
                    val wake = wakes.value
                    if (allowed()) attempt { sender().retryDue() }
                    var next: Long? = null
                    attempt { next = sender().nextDue() }
                    val wait = next?.let { (it - clock()).coerceAtLeast(MIN_WAIT_MILLIS) } ?: IDLE_WAIT_MILLIS
                    withTimeoutOrNull(wait) { wakes.first { it != wake } }
                    delay(MIN_WAIT_MILLIS)
                }
            }
    }

    fun wake() {
        wakes.update { it + 1 }
    }

    private companion object {
        const val MIN_WAIT_MILLIS = 1_000L
        const val IDLE_WAIT_MILLIS = 15 * 60 * 1000L
    }
}
