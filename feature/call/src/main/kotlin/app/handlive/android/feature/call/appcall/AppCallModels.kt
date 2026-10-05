package app.handlive.android.feature.call.appcall

import app.handlive.android.core.protocol.call.AppCallState

/**
 * A PendingIntent of a calling app (answer, decline, hang up or an ordinary notification action), created by that app
 * itself. The system side wraps the real `PendingIntent` ([PendingAppIntent]); the logic and its tests work on this
 * interface.
 */
fun interface AppIntent {
    /**
     * Sends the intent; `false` when the app canceled it (`PendingIntent.CanceledException`). [backgroundStart]: with
     * the background-activity-start option (CALL-05 API 4) — only the answer intent, which starts the app's call
     * screen, gets it; a decline, an end or an ordinary action is sent without it.
     */
    fun send(backgroundStart: Boolean): Boolean
}

/** The label of a calling app (`PackageManager`), shown on the Mac next to the caller; display only. */
fun interface AppLabels {
    fun label(packageName: String): String
}

/** The PendingIntents of a notification: its `CallStyle` intents and the intents of its ordinary actions, in order. */
class AppIntents(
    val answer: AppIntent? = null,
    val decline: AppIntent? = null,
    val hangUp: AppIntent? = null,
    /** One entry per ordinary action (`null` = an action without an intent). */
    val actions: List<AppIntent?> = emptyList(),
)

/**
 * What a notification keeps behind the filter: its PendingIntents and the caller's name. The tracker asks for them only
 * when the notification is a call, or an ongoing notification awaited as the in-call one.
 */
interface AppNotificationDetails {
    fun intents(): AppIntents

    /** `callPerson.name`, else the notification title. */
    fun caller(): String?
}

/**
 * One posted notification as the listener hands it over: its package, key, `CallStyle` type and ongoing flag — what the
 * filter needs. The [details] are read lazily: every other notification is ignored without its title, text
 * or extras being read. Not a data class, so no accidental `toString()` can carry the name.
 */
class AppNotification(
    val key: String,
    val packageName: String,
    /** `android.callType` of a `CallStyle` notification; `null` for any other notification. */
    val callType: Int?,
    /** `FLAG_ONGOING_EVENT`. */
    val ongoing: Boolean,
    /**
     * Android vouches that this is a real call notification (CALL-05 API 1 logic 6): only then may its answer intent
     * start the app from the background with HandLive's exemption.
     */
    val vouched: Boolean,
    private val details: AppNotificationDetails,
) {
    /** The intents, read from the notification the first time they are asked for. */
    val intents: AppIntents by lazy { details.intents() }

    fun caller(): String? = details.caller()
}

/** What a call notification says about its call. */
enum class AppCallShape { RINGING, ONGOING }

/** What A-CALL follows the app calls by: the notification listener, and the audio mode for a detached call. */
enum class AppCallSignal { LISTENER, AUDIO_MODE }

/** The three things HandLive can do to an app call, by the app's own intents. */
enum class AppCallAction { ANSWER, DECLINE, END }

/**
 * An app call in A-CALL's memory (CALL-05): from its first call notification until the in-call one is removed, then
 * forgotten once its `ended` state has gone out. Never written to disk or a log: [toString] leaves the caller out.
 */
data class AppCallContext(
    val callId: String,
    val packageName: String,
    val label: String,
    val caller: String?,
    val state: String,
    val startedAt: Long,
    /** The key of the notification that holds the call now: the ringing one, then the in-call one. */
    val notificationKey: String,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val endReason: String? = null,
    /** The ringing notification's answer and decline intents; gone with that notification. */
    val answer: AppIntent? = null,
    /** Android vouches for the ringing notification ([AppNotification.vouched]): answering may be `direct`. */
    val vouched: Boolean = false,
    val decline: AppIntent? = null,
    /** The in-call notification's end action (hang-up intent, else its single action). */
    val end: AppIntent? = null,
    /** When the ringing notification was removed while the in-call one is awaited (link window). */
    val unlinkedAt: Long? = null,
    /** HandLive sent the app's decline / answer intent: how the call ended is `declined`, not `missed`. */
    val declineSent: Boolean = false,
    val answerSent: Boolean = false,
    /**
     * Ongoing, but its in-call notification went without the app removing it (the user swiped it away): the call goes
     * on while the audio mode is in communication, with no end action, until a new ongoing notification holds it.
     */
    val detached: Boolean = false,
) {
    val ended: Boolean get() = state == AppCallState.ENDED

    /** The ringing notification is gone and the in-call one is awaited. */
    val waitingForInCall: Boolean get() = state == AppCallState.RINGING && unlinkedAt != null

    override fun toString(): String = "AppCallContext(callId=$callId, state=$state)"
}
