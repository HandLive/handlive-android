package app.handlive.spike.call.logic

import java.security.MessageDigest

/**
 * What the probe keeps from one posted notification: only its shape. No title, text, caller name or number is ever
 * copied here, so nothing personal can reach the log.
 */
data class NotificationFacts(
    val packageName: String,
    val key: String,
    val category: String?,
    val channelId: String?,
    // `Notification.EXTRA_CALL_TYPE` of a `CallStyle` notification; null when the notification is not `CallStyle`.
    val callType: Int?,
    val hasCallPerson: Boolean,
    val hasAnswerIntent: Boolean,
    val hasDeclineIntent: Boolean,
    val hasHangUpIntent: Boolean,
    // Labels of the ordinary actions (UI words such as "End call"), used to find a hang-up action without `CallStyle`.
    val actionTitles: List<String>,
    val ongoing: Boolean,
)

enum class CallPhase { RINGING, ONGOING, SCREENING, UNKNOWN }

/** A call notification as the spike reports it; [keyHash] stands for the notification key in logs and commands. */
data class CallSnapshot(
    val packageName: String,
    val keyHash: String,
    val phase: CallPhase,
    val callStyle: Boolean,
    val canAnswer: Boolean,
    val canDecline: Boolean,
    val canHangUp: Boolean,
    val actionTitles: List<String>,
    val channelId: String?,
)

/**
 * Classifies notifications the way CALL-05 would: a call is a `CallStyle` notification (it carries
 * `android.callType`) or one in the `call` category. `CallStyle` call types: 1 incoming, 2 ongoing, 3 screening.
 */
object CallNotificationParser {
    const val CATEGORY_CALL = "call"

    /** Calling apps whose non-call notifications are also logged (shape only), to find their in-call notification. */
    private val WATCHED_APPS =
        setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "com.zing.zalo",
            "com.whatsapp",
            "com.facebook.orca",
            "com.viber.voip",
            "com.google.android.apps.tachyon",
            "com.skype.raider",
            "com.discord",
            "org.thoughtcrime.securesms",
        )

    fun isWatchedApp(packageName: String): Boolean = packageName in WATCHED_APPS

    fun parse(facts: NotificationFacts): CallSnapshot? {
        val callStyle = facts.callType != null
        if (!callStyle && facts.category != CATEGORY_CALL) return null
        val phase =
            when (facts.callType) {
                1 -> CallPhase.RINGING
                2 -> CallPhase.ONGOING
                3 -> CallPhase.SCREENING
                else -> if (facts.ongoing) CallPhase.ONGOING else CallPhase.UNKNOWN
            }
        return CallSnapshot(
            packageName = facts.packageName,
            keyHash = keyHash(facts.key),
            phase = phase,
            callStyle = callStyle,
            canAnswer = facts.hasAnswerIntent,
            canDecline = facts.hasDeclineIntent,
            canHangUp = facts.hasHangUpIntent,
            actionTitles = facts.actionTitles,
            channelId = facts.channelId,
        )
    }

    fun keyHash(key: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)
}
