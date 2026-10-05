package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.feature.call.CallConstants
import kotlinx.coroutines.Job

/**
 * The events of the app calls, one at a time on the A-CALL thread: what the notification listener reports goes to the
 * [AppCallTracker], and every change to the sessions ([AppCallBroadcaster]). While app calls are off nothing is read
 * and nothing is sent; turning them off ends the calls in progress as `unknown`.
 */
internal class AppCallEvents(
    private val services: AppCallServices,
    /** Runs a task on the A-CALL thread after a delay (the link window of a call whose ringing notification went). */
    private val later: (Long, suspend () -> Unit) -> Job,
    private val clock: () -> Long,
) {
    private val tracker = services.tracker

    /** The audio mode is watched for the detached calls; touched on the A-CALL thread only. */
    private var watchingMode = false

    /** [notification] was posted at [at], its `postTime`. */
    suspend fun posted(
        notification: AppNotification,
        at: Long,
    ) {
        if (services.access.enabled()) changed(tracker.onPosted(notification, at), at)
    }

    /**
     * The notification [key] was removed; [at] = the wall clock of the listener callback; [byApp]: the app removed it
     * (an in-call notification removed otherwise detaches its call while a call holds the audio mode).
     */
    suspend fun removed(
        key: String,
        at: Long,
        byApp: Boolean,
    ) = changed(tracker.onRemoved(key, at, byApp), at)

    /** The audio mode left communication: the detached calls end. */
    suspend fun communicationLeft(at: Long) = changed(tracker.onLost(AppCallSignal.AUDIO_MODE, at), at)

    /** The listener was disconnected: the calls in progress can no longer be followed. */
    suspend fun disconnected(at: Long) = changed(tracker.onLost(AppCallSignal.LISTENER, at), at)

    /** The setting, Notification access or the background-start exemption changed. */
    suspend fun refresh(at: Long) {
        if (services.access.enabled()) republish() else changed(tracker.onLost(AppCallSignal.LISTENER, at), at)
    }

    /** A session got app calls in effect: it gets the calls in progress. */
    suspend fun republish() = services.broadcaster.publish(tracker.current, change = false)

    private suspend fun changed(
        contexts: List<AppCallContext>,
        os: Long,
    ) {
        if (contexts.isEmpty()) return
        contexts.forEach { services.trace.appCallChanged(it, os) }
        services.broadcaster.publish(contexts, change = true)
        for (context in contexts) {
            // The notification of the app is gone or the call moved on: nothing left to tap.
            if (context.state != AppCallState.RINGING || context.answer == null) services.tap.cancel(context.callId)
            if (context.waitingForInCall) waitForInCall(context)
        }
        followMode()
    }

    /** The audio mode is watched exactly while a call is detached; its end comes back through the A-CALL queue. */
    private fun followMode() {
        val wanted = tracker.hasDetached
        if (wanted == watchingMode) return
        watchingMode = wanted
        if (wanted) {
            tracker.mode.watch { later(0) { communicationLeft(clock()) } }
        } else {
            tracker.mode.stop()
        }
    }

    /**
     * The window is decided by post times: a post within it that is still on its way when the window ends is processed
     * first, so the end is decided [CallConstants.APP_CALL_LINK_GRACE_MILLIS] after the window, counted from the
     * removal's callback.
     */
    private fun waitForInCall(context: AppCallContext) {
        val since = checkNotNull(context.unlinkedAt)
        val end = since + CallConstants.APP_CALL_LINK_WINDOW_MILLIS + CallConstants.APP_CALL_LINK_GRACE_MILLIS
        later((end - clock()).coerceAtLeast(0)) {
            val at = clock()
            changed(tracker.onLinkWindowEnd(context.callId, since, at), at)
        }
    }
}
