package app.handlive.android.feature.call.appcall

/**
 * CALL-05 detection on the shape of a notification (never its text): a `CallStyle` notification carries
 * `android.callType` — 1 incoming (ringing), 2 ongoing; 3 (screening) and any other type are ignored. A notification
 * without `CallStyle` (category `call` or not) never creates a call; it can only become the in-call notification of a
 * call that rings when it is ongoing, as any ongoing notification of its package can. Anything else is not a call.
 */
object AppCallParser {
    /** `Notification.CallStyle` call types (API 31 constants, spelled out so minSdk 29 needs no version check). */
    const val CALL_TYPE_INCOMING = 1
    const val CALL_TYPE_ONGOING = 2

    /** `null` when [notification] does not start a call: not `CallStyle`, a screening one, or a type unknown here. */
    fun shape(notification: AppNotification): AppCallShape? =
        when (notification.callType) {
            CALL_TYPE_INCOMING -> AppCallShape.RINGING
            CALL_TYPE_ONGOING -> AppCallShape.ONGOING
            else -> null
        }
}

/**
 * The action that ends an app call, found on the in-call notification: its `android.hangUpIntent`, else its only
 * action when it has exactly one; otherwise none. A title is never matched as text (titles are localized).
 */
object AppCallEndAction {
    fun select(notification: AppNotification): AppIntent? =
        notification.intents.hangUp ?: notification.intents.actions.singleOrNull()
}
