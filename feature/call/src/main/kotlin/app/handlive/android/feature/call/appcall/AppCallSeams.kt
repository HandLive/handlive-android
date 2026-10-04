package app.handlive.android.feature.call.appcall

/**
 * What the app calls may do right now (SET-01 API 2, CALL-05): the setting `call.app_calls`, the setting
 * `feature.call` and Notification access. Read at every event and request, since the user can change them at any
 * time.
 */
fun interface AppCallAccess {
    fun enabled(): Boolean
}

/**
 * HandLive may start another app's answer screen from the background — its accessibility service is bound, which
 * exempts it from the background-activity-start limits (spike T3.2). Decides `answer_mode`: `direct` or `tap`.
 */
fun interface BackgroundStartExemption {
    fun held(): Boolean
}

/**
 * Whether answering [context] starts the app from the background (`answer_mode = direct`, CALL-05 API 1 logic 6): the
 * exemption is held and Android vouches for the ringing notification, so it is never lent to a forged call.
 */
fun BackgroundStartExemption.lendsTo(context: AppCallContext): Boolean = context.vouched && held()

/**
 * The "tap to answer" notification of the phone (`answer_mode = tap`): its content intent is the app's answer intent,
 * so the user's tap — not HandLive — starts the app's answer screen. Removed when the call leaves ringing, and by
 * itself after `APP_CALL_TAP_NOTIFICATION_TTL`.
 */
interface TapToAnswerNotifier {
    /**
     * Posts the notification of [callId] for the app [label], with the [caller] as its text when known; `false` when
     * the phone cannot show it (notifications not allowed, the channel blocked).
     */
    fun post(
        callId: String,
        label: String,
        caller: String?,
        answer: AppIntent,
    ): Boolean

    fun cancel(callId: String)
}
