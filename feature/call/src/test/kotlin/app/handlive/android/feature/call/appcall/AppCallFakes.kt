package app.handlive.android.feature.call.appcall

/**
 * A calling app's PendingIntent that records how often it was sent, in which order across intents, and whether each
 * send carried the background-start option.
 */
class FakeAppIntent(
    val name: String,
    private val sent: MutableList<String>? = null,
) : AppIntent {
    var sends = 0
        private set

    /** The `backgroundStart` of every send, in order. */
    val backgroundStarts = mutableListOf<Boolean>()

    /** The app canceled the PendingIntent: [send] reports `false`. */
    var canceled = false

    override fun send(backgroundStart: Boolean): Boolean {
        if (canceled) return false
        sends++
        backgroundStarts += backgroundStart
        sent?.add(name)
        return true
    }
}

/** The notifications of a calling app, as fixtures with the shapes the 2026-10-01 spike saw on the S25 (Android 16). */
object AppCallFixtures {
    const val TELEGRAM = "org.telegram.messenger"
    const val ZALO = "com.zing.zalo"

    /** A caller name that must never appear in a log, an exception text or a `toString()`. */
    const val CALLER = "Nguyễn Văn A"

    const val CALL_TYPE_INCOMING = 1
    const val CALL_TYPE_ONGOING = 2
    const val CALL_TYPE_SCREENING = 3

    val labels = AppLabels { packageName -> if (packageName == TELEGRAM) "Telegram" else "Zalo" }

    /** Telegram ringing: `CallStyle` incoming, decline (broadcast) and answer (activity) intents. */
    @Suppress("LongParameterList")
    fun telegramRinging(
        key: String = "0|org.telegram.messenger|203|null|10148",
        caller: String? = CALLER,
        answer: AppIntent? = FakeAppIntent("answer"),
        decline: AppIntent? = FakeAppIntent("decline"),
        vouched: Boolean = true,
        onCallerRead: () -> Unit = {},
    ) = notification(
        key = key,
        packageName = TELEGRAM,
        callType = CALL_TYPE_INCOMING,
        ongoing = false,
        vouched = vouched,
        answer = answer,
        decline = decline,
        caller = caller,
        onCallerRead = onCallerRead,
    )

    /** Telegram in call: channel `Other3`, ongoing, no `CallStyle`, one ordinary action "End". */
    fun telegramInCall(
        key: String = "0|org.telegram.messenger|202|null|10148",
        actions: List<AppIntent?> = listOf(FakeAppIntent("end")),
        ongoing: Boolean = true,
        onCallerRead: () -> Unit = {},
    ) = notification(
        key = key,
        packageName = TELEGRAM,
        ongoing = ongoing,
        actions = actions,
        caller = "Telegram",
        onCallerRead = onCallerRead,
    )

    /**
     * Every field of a notification has a default, so a test names only what it is about; the probes count how often
     * the caller and the intents are read.
     */
    @Suppress("LongParameterList")
    fun notification(
        key: String,
        packageName: String,
        callType: Int? = null,
        ongoing: Boolean = false,
        vouched: Boolean = true,
        answer: AppIntent? = null,
        decline: AppIntent? = null,
        hangUp: AppIntent? = null,
        actions: List<AppIntent?> = emptyList(),
        caller: String? = null,
        onCallerRead: () -> Unit = {},
        onIntentsRead: () -> Unit = {},
    ) = AppNotification(
        key = key,
        packageName = packageName,
        callType = callType,
        ongoing = ongoing,
        vouched = vouched,
        details =
            object : AppNotificationDetails {
                override fun intents(): AppIntents {
                    onIntentsRead()
                    return AppIntents(answer, decline, hangUp, actions)
                }

                override fun caller(): String? {
                    onCallerRead()
                    return caller
                }
            },
    )
}

/** `call.app_calls` ∧ Notification access ∧ `feature.call`, switchable. */
class FakeAppCallAccess : AppCallAccess {
    var enabled = true

    override fun enabled() = enabled
}

/** Whether HandLive may start another app's answer screen from the background (its accessibility service is bound). */
class FakeExemption : BackgroundStartExemption {
    var held = true

    override fun held() = held
}

/** The "tap to answer" notification as a list of what was asked of it. */
class FakeTapNotifier : TapToAnswerNotifier {
    class Posted(
        val callId: String,
        val label: String,
        val caller: String?,
        val answer: AppIntent,
    )

    val posted = mutableListOf<Posted>()
    val cancelled = mutableListOf<String>()

    /** `false`: the phone cannot show notifications. */
    var canShow = true

    override fun post(
        callId: String,
        label: String,
        caller: String?,
        answer: AppIntent,
    ): Boolean {
        if (canShow) posted += Posted(callId, label, caller, answer)
        return canShow
    }

    override fun cancel(callId: String) {
        cancelled += callId
    }
}
