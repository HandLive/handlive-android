package app.handlive.android.feature.call.appcall

import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.handlive.android.feature.call.CallFeature
import java.util.concurrent.atomic.AtomicBoolean

/** What the listener service reports to: A-CALL in production. */
interface AppCallListenerTarget {
    /**
     * `call.app_calls` and `feature.call` are both on: the listener may read. `null` while the settings have not been
     * loaded yet: the listener waits for them ([AppCallListenerService.follow]).
     */
    fun appCallsWanted(): Boolean?

    fun posted(
        notification: AppNotification,
        at: Long,
    )

    fun removed(
        key: String,
        at: Long,
    )

    /** The listener connected or disconnected: Notification access, so the capability, may have changed. */
    fun listenerChanged(
        connected: Boolean,
        at: Long,
    )
}

/**
 * The notification listener of the calls of other apps (CALL-05 API 3): the system binds it once the user grants
 * Notification access. The filter runs here, on the listener thread, before anything is queued: HandLive's own
 * notifications, those of the cellular dialers ([DialerPackages]) and every notification that is neither `CallStyle`
 * nor ongoing are dropped; each remaining one goes to A-CALL as its shape — never its title or text — with its post
 * time. While `call.app_calls` or `feature.call` is off it unbinds itself and reads nothing; before the settings are
 * loaded it waits. The notifications standing when it starts reading are followed as if just posted, oldest first. A
 * callback that fails skips that notification and never takes the process down. Nothing is logged (group 6 rules).
 */
class AppCallListenerService : NotificationListenerService() {
    private val target get() = (targetFactory ?: { CallFeature.get(it).listenerTarget })(this)
    private val reader by lazy { AppNotificationReader(this) }
    private val dialers: DialerPackages by lazy { dialersFactory?.invoke(this) ?: AndroidDialerPackages(this) }

    /** Connected, and neither reading nor unbound yet: the settings decide ([resume]). */
    private val undecided = AtomicBoolean(false)

    override fun onListenerConnected() {
        running = this
        undecided.set(true)
        resume()
    }

    override fun onListenerDisconnected() {
        val at = System.currentTimeMillis()
        undecided.set(false)
        if (running === this) running = null
        guarded { target.listenerChanged(connected = false, at = at) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        // Before the settings decide, nothing is read: the standing notifications are read when reading starts.
        if (!undecided.get()) post(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap?,
        reason: Int,
    ) {
        val at = System.currentTimeMillis()
        guarded { if (sbn.packageName != packageName) target.removed(sbn.key, at) }
    }

    /** Once the settings are known: starts reading when app calls are wanted, else unbinds; once per connection. */
    private fun resume() {
        val wanted = guarded { target.appCallsWanted() } ?: return
        if (!undecided.compareAndSet(true, false)) return
        if (wanted) start() else unbind()
    }

    private fun start() {
        guarded { activeNotifications }.orEmpty().sortedBy { it.postTime }.forEach(::post)
        guarded { target.listenerChanged(connected = true, at = System.currentTimeMillis()) }
    }

    private fun post(sbn: StatusBarNotification) {
        guarded { if (accepts(sbn)) target.posted(reader.read(sbn), sbn.postTime) }
    }

    /** The filter of CALL-05 API 1 logic 1; the dialers are read only for a notification that passes the rest. */
    private fun accepts(sbn: StatusBarNotification): Boolean =
        sbn.packageName != packageName &&
            AppNotificationReader.candidate(sbn) &&
            sbn.packageName !in dialers.current()

    /** A service that is not bound (any more) has nothing to unbind. */
    private fun unbind() {
        try {
            requestUnbind()
        } catch (_: IllegalStateException) {
            // Already gone.
        }
    }

    companion object {
        @Volatile
        private var running: AppCallListenerService? = null

        /** Replaces A-CALL in tests. */
        @Volatile
        internal var targetFactory: ((Context) -> AppCallListenerTarget)? = null

        /** Replaces the dialers of the phone in tests. */
        @Volatile
        internal var dialersFactory: ((Context) -> DialerPackages)? = null

        /**
         * The settings were loaded or changed (CALL-05 API 3 logic 2): a listener waiting for them starts or unbinds;
         * otherwise reading resumes when wanted (rebind), or the listener unbinds when not.
         */
        fun follow(
            context: Context,
            wanted: Boolean,
        ) {
            val service = running
            when {
                service != null && service.undecided.get() -> {
                    service.resume()
                }

                wanted -> {
                    // Fails while Notification access is not granted: then there is nothing to rebind.
                    runCatching { requestRebind(ComponentName(context, AppCallListenerService::class.java)) }
                }

                else -> {
                    service?.unbind()
                }
            }
        }

        /** [block], or `null` when it fails in any way: that notification or event is skipped, nothing is logged. */
        private inline fun <T> guarded(block: () -> T): T? = runCatching(block).getOrNull()
    }
}
