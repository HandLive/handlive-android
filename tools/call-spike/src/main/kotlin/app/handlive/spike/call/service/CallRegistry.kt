package app.handlive.spike.call.service

import android.os.SystemClock
import android.service.notification.StatusBarNotification
import app.handlive.spike.call.logic.CallSnapshot

/**
 * The notifications the probe can act on, keyed by the short key hash used in logs and adb commands: call
 * notifications (with a [CallSnapshot]) and the latest notifications of watched calling apps (snapshot null), whose
 * ordinary actions may hold the in-call "End" button. Also remembers the last command sent, to time its effect.
 */
object CallRegistry {
    private class Entry(
        val notification: StatusBarNotification,
        val snapshot: CallSnapshot?,
    )

    private val entries = LinkedHashMap<String, Entry>()
    private var lastCommand: Pair<String, Long>? = null

    @Synchronized
    fun put(
        keyHash: String,
        notification: StatusBarNotification,
        snapshot: CallSnapshot?,
    ) {
        entries.remove(keyHash)
        entries[keyHash] = Entry(notification, snapshot)
    }

    @Synchronized
    fun remove(keyHash: String): Boolean = entries.remove(keyHash) != null

    /** The notification for [keyHash], or the newest call notification when [keyHash] is null. */
    @Synchronized
    fun find(keyHash: String?): Pair<String, StatusBarNotification>? {
        if (keyHash != null) return entries[keyHash]?.let { keyHash to it.notification }
        return entries.entries.lastOrNull { it.value.snapshot != null }?.let { it.key to it.value.notification }
    }

    @Synchronized
    fun summary(): List<Pair<String, CallSnapshot?>> = entries.map { it.key to it.value.snapshot }

    @Synchronized
    fun markCommand(what: String) {
        lastCommand = what to SystemClock.elapsedRealtime()
    }

    /** Milliseconds since the last command and its name, for the `since_cmd` field; null before any command. */
    @Synchronized
    fun sinceCommand(): String? = lastCommand?.let { "${it.first}:${SystemClock.elapsedRealtime() - it.second}" }
}
