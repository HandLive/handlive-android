package app.handlive.android.feature.call.appcall

/**
 * What the notification listener (and the settings) tell A-CALL about the calls of other apps (CALL-05): each report
 * joins the queue of the A-CALL thread, behind the telephony events, and is processed to its end before the next.
 */
class AppCallInbox internal constructor(
    private val events: AppCallEvents,
    private val post: (suspend () -> Unit) -> Unit,
) {
    /** The listener saw [notification] posted at [at] (its post time); only a call notification is read further. */
    fun posted(
        notification: AppNotification,
        at: Long,
    ) = post { events.posted(notification, at) }

    /** The notification [key] was removed at [at]; [byApp]: by the app itself (`REASON_APP_CANCEL`, `…_ALL`). */
    fun removed(
        key: String,
        at: Long,
        byApp: Boolean,
    ) = post { events.removed(key, at, byApp) }

    /** The listener was disconnected or Notification access revoked: the calls in progress end as `unknown`. */
    fun listenerLost(at: Long) = post { events.disconnected(at) }

    /** The setting `call.app_calls`, Notification access or the background-start exemption changed. */
    fun environmentChanged(at: Long) = post { events.refresh(at) }
}
