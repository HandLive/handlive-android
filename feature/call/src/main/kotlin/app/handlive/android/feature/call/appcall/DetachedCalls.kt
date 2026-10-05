package app.handlive.android.feature.call.appcall

/**
 * The detached app calls (CALL-05 E11), by `call_id`: the ongoing notifications of their package that stood when each
 * was detached, so that only one posted since, with the shape of an in-call notification, can hold the call again —
 * never a media player, a download or an upload whose single action is Cancel.
 */
internal class DetachedCalls {
    private val before = HashMap<String, Set<String>>()

    val isEmpty: Boolean get() = before.isEmpty()

    /** [callId] was detached while the keys [standing] of its package stood. */
    fun add(
        callId: String,
        standing: Set<String>,
    ) {
        before[callId] = standing
    }

    fun remove(callId: String) {
        before.remove(callId)
    }

    /** The detached call among [contexts] that [notification], an in-call notification of its package, holds now. */
    fun heldBy(
        notification: AppNotification,
        contexts: Collection<AppCallContext>,
    ): AppCallContext? {
        if (!notification.inCallShaped()) return null
        return contexts.firstOrNull { context ->
            context.detached &&
                context.packageName == notification.packageName &&
                before[context.callId]?.contains(notification.key) == false
        }
    }
}
