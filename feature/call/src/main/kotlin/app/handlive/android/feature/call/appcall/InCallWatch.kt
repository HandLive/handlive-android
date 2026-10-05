package app.handlive.android.feature.call.appcall

/**
 * The candidates for the in-call notification of one ringing call: the ongoing notifications of its package that
 * already stood when it started are never one, and those first posted after it started, while its ringing
 * notification stands, are remembered (oldest first) until they are removed.
 */
internal class InCallWatch(
    private val before: Set<String>,
) {
    class Candidate(
        val notification: AppNotification,
        /** The post time of the key's first version: when the call was answered. */
        val postedAt: Long,
    )

    private val candidates = LinkedHashMap<String, Candidate>()

    /** [key] did not stand when the call started. */
    fun isNew(key: String) = key !in before

    /** The latest version of [notification], with the post time of its first. */
    fun remember(
        notification: AppNotification,
        at: Long,
    ) {
        candidates[notification.key] = Candidate(notification, candidates[notification.key]?.postedAt ?: at)
    }

    fun forget(key: String) {
        candidates.remove(key)
    }

    fun first(): Candidate? = candidates.values.firstOrNull()
}
