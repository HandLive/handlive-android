package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallEndReason
import app.handlive.android.core.protocol.call.AppCallState
import app.handlive.android.feature.call.CallConstants

/**
 * The state machine of the app calls (CALL-05), fed one event at a time on the A-CALL thread by the notification
 * listener. Every event returns the contexts that changed — one that just ended is returned once and forgotten.
 *
 * - A ringing `CallStyle` notification creates a ringing context; a repost of the same key updates it.
 * - The in-call notification of a ringing call is an ongoing notification of the same package whose key first
 *   appeared after the call started (the in-call notification of Telegram is not `CallStyle`). An ongoing notification
 *   that already stood when the call started — music, a download, a service — and every later update of its key never
 *   is: its action must never become End. One posted while the ringing notification still stands becomes the in-call
 *   notification when the ringing one is removed; otherwise the context waits for one whose post time is within
 *   [CallConstants.APP_CALL_LINK_WINDOW_MILLIS] of that removal. A `CallStyle` ongoing notification of the package is
 *   the in-call notification at once. A decline sent by HandLive ends the call at once as `declined`; otherwise, when
 *   the window passes (the caller of [onLinkWindowEnd]), it ends `missed`, or `unknown` after an answer HandLive sent.
 * - The app removing the in-call notification ends the call (`ended`). Any other removal (the user swiped it away)
 *   detaches it while a call holds the audio mode, else ends it `unknown`; a detached call ends `unknown` when the
 *   mode leaves communication; its in-call notification posted again, or a new one of its package with the in-call
 *   shape, holds it again — any other ongoing notification of the package never does.
 *   Losing the listener ends every call as `unknown`.
 *
 * Only a notification that is a call, or the in-call notification of one, is read beyond its shape; the keys of the
 * standing ongoing notifications are kept in memory, nothing is logged or stored.
 */
class AppCallTracker(
    private val ids: () -> String,
    private val labels: AppLabels,
    /** The audio mode a detached call lives on: asked when one is detached, watched while one is (by A-CALL). */
    val mode: CommunicationMode = CommunicationMode.NONE,
) {
    private val contexts = LinkedHashMap<String, AppCallContext>()

    /** The ongoing notifications without `CallStyle` that stand now: key → package. */
    private val standing = HashMap<String, String>()

    /** What may become the in-call notification of each ringing context, by `call_id`. */
    private val watches = HashMap<String, InCallWatch>()

    private val detached = DetachedCalls()

    /** A call is detached: the audio mode must be followed. */
    val hasDetached: Boolean get() = !detached.isEmpty

    /** The calls in progress (ringing or ongoing), oldest first. */
    val current: List<AppCallContext> get() = contexts.values.toList()

    fun find(callId: String): AppCallContext? = contexts[callId]

    fun onPosted(
        notification: AppNotification,
        at: Long,
    ): List<AppCallContext> {
        val shape = AppCallParser.shape(notification)
        val holder = contexts.values.firstOrNull { it.notificationKey == notification.key }
        val changed =
            when {
                holder != null -> {
                    holder.updatedBy(notification, shape, at)
                }

                shape == AppCallShape.RINGING -> {
                    create(notification, shape, at)
                }

                else -> {
                    detached.heldBy(notification, contexts.values)?.reattachedTo(notification)
                        ?: link(notification, shape, at)
                        ?: shape?.let { create(notification, it, at) }
                }
            }
        if (shape == null && notification.ongoing) standing[notification.key] = notification.packageName
        return listOfNotNull(changed?.also(::keep))
    }

    /**
     * The notification [key] was removed at [at]; [byApp]: the app removed it itself. The [mode] is asked only when the
     * in-call notification of an ongoing call goes otherwise: a call that holds the audio mode is detached.
     */
    fun onRemoved(
        key: String,
        at: Long,
        byApp: Boolean = true,
    ): List<AppCallContext> {
        standing.remove(key)
        watches.values.forEach { it.forget(key) }
        val context = contexts.values.firstOrNull { it.notificationKey == key } ?: return emptyList()
        val inCall = watches[context.callId]?.first()
        val changed =
            when {
                context.state == AppCallState.ONGOING && byApp -> {
                    end(context, AppCallEndReason.ENDED, at)
                }

                context.state == AppCallState.ONGOING && mode.inCommunication() -> {
                    detached.add(context.callId, standing.filterValues { it == context.packageName }.keys.toSet())
                    context.copy(end = null, detached = true).also(::keep)
                }

                context.state == AppCallState.ONGOING -> {
                    end(context, AppCallEndReason.UNKNOWN, at)
                }

                context.waitingForInCall -> {
                    null
                }

                context.declineSent -> {
                    end(context, AppCallEndReason.DECLINED, at)
                }

                // The in-call notification came before the ringing one went: the call was answered when it was posted.
                inCall != null -> {
                    context.becameOngoing(inCall.notification, callStyle = false, at = inCall.postedAt).also(::keep)
                }

                // The ringing notification is gone: nothing can be answered or declined, the in-call one may follow.
                else -> {
                    context.copy(unlinkedAt = at, answer = null, decline = null).also(::keep)
                }
            }
        return listOfNotNull(changed)
    }

    /**
     * The link window of [callId] that began at [since] (when its ringing notification was removed) elapsed: no
     * in-call notification came, so the call is over. A wait that has begun again since is not touched.
     */
    fun onLinkWindowEnd(
        callId: String,
        since: Long,
        at: Long,
    ): List<AppCallContext> {
        val context = contexts[callId]?.takeIf { it.waitingForInCall && it.unlinkedAt == since } ?: return emptyList()
        val reason = if (context.answerSent) AppCallEndReason.UNKNOWN else AppCallEndReason.MISSED
        return listOf(end(context, reason, at))
    }

    /**
     * [signal] is gone, so the calls it followed end as `unknown`: the notification listener — every call, and what
     * stands is forgotten — or the audio mode of the detached calls, which left communication.
     */
    fun onLost(
        signal: AppCallSignal,
        at: Long,
    ): List<AppCallContext> {
        if (signal == AppCallSignal.LISTENER) standing.clear()
        return current
            .filter { signal == AppCallSignal.LISTENER || it.detached }
            .map { end(it, AppCallEndReason.UNKNOWN, at) }
    }

    /** HandLive sent the app's intent for [action] of [callId]; it decides how the call is said to have ended. */
    fun markSent(
        callId: String,
        action: AppCallAction,
    ) {
        val context = contexts[callId] ?: return
        contexts[callId] =
            when (action) {
                AppCallAction.ANSWER -> context.copy(answerSent = true)
                AppCallAction.DECLINE -> context.copy(declineSent = true)
                AppCallAction.END -> context
            }
    }

    private fun create(
        notification: AppNotification,
        shape: AppCallShape,
        at: Long,
    ): AppCallContext {
        val ringing = shape == AppCallShape.RINGING
        val context =
            AppCallContext(
                callId = ids(),
                packageName = notification.packageName,
                label = labelOf(labels, notification.packageName),
                caller = callerOf(notification),
                state = if (ringing) AppCallState.RINGING else AppCallState.ONGOING,
                startedAt = at,
                notificationKey = notification.key,
                answer = notification.intents.answer.takeIf { ringing },
                decline = notification.intents.decline.takeIf { ringing },
                vouched = ringing && notification.vouched,
                end = if (ringing) null else AppCallEndAction.select(notification),
            )
        if (ringing) {
            val before = standing.filterValues { it == notification.packageName }.keys
            watches[context.callId] = InCallWatch(before.toSet())
        }
        return context
    }

    /**
     * A notification of a package with a ringing context and held by none: a `CallStyle` ongoing one is that call's
     * in-call notification; an ongoing one that appeared after the call started is it when it comes within the window
     * after the ringing notification's removal, or is remembered while the ringing notification stands.
     */
    private fun link(
        notification: AppNotification,
        shape: AppCallShape?,
        at: Long,
    ): AppCallContext? {
        val ringing =
            contexts.values.filter { it.packageName == notification.packageName && it.state == AppCallState.RINGING }
        val fresh = ringing.filter { watches[it.callId]?.isNew(notification.key) == true }
        val waiting =
            fresh.firstOrNull {
                it.waitingForInCall && at - checkNotNull(it.unlinkedAt) <= CallConstants.APP_CALL_LINK_WINDOW_MILLIS
            }
        return when {
            shape == AppCallShape.ONGOING -> {
                ringing.firstOrNull()?.becameOngoing(notification, callStyle = true, at = at)
            }

            !notification.ongoing -> {
                null
            }

            waiting != null -> {
                waiting.becameOngoing(notification, callStyle = false, at = at)
            }

            else -> {
                fresh.firstOrNull { !it.waitingForInCall }?.let { watches[it.callId]?.remember(notification, at) }
                null
            }
        }
    }

    /** [context] changed and is still in progress: it replaces its old version; once ongoing it awaits nothing. */
    private fun keep(context: AppCallContext) {
        contexts[context.callId] = context
        if (context.state != AppCallState.RINGING) watches.remove(context.callId)
        if (!context.detached) detached.remove(context.callId)
    }

    private fun end(
        context: AppCallContext,
        reason: String,
        at: Long,
    ): AppCallContext {
        contexts.remove(context.callId)
        watches.remove(context.callId)
        detached.remove(context.callId)
        return context.endedAs(reason, at)
    }
}
