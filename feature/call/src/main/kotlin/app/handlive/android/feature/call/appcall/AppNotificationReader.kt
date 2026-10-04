package app.handlive.android.feature.call.appcall

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.os.BundleCompat

/**
 * A [StatusBarNotification] as an [AppNotification]: its `CallStyle` type, ongoing flag and intents — never its title
 * or text. The caller (`callPerson.name`, else the title) is read only when the tracker asks for it, which it does for
 * call notifications alone. Only a PendingIntent the posting app created itself is kept: one created by another app
 * (and handed to it) counts as absent, so HandLive never sends someone else's intent on the calling app's behalf.
 */
class AppNotificationReader(
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
) {
    fun read(sbn: StatusBarNotification): AppNotification {
        val notification = sbn.notification
        return AppNotification(
            key = sbn.key,
            packageName = sbn.packageName,
            callType = callTypeOf(notification, sdk),
            ongoing = ongoing(notification),
            details =
                object : AppNotificationDetails {
                    override fun intents() = intentsOf(notification, sbn.packageName)

                    override fun caller() = callerOf(notification)
                },
        )
    }

    private fun intentsOf(
        notification: Notification,
        owner: String,
    ): AppIntents {
        val extras = notification.extras
        return AppIntents(
            answer = intentOf(extras, EXTRA_ANSWER_INTENT, owner),
            decline = intentOf(extras, EXTRA_DECLINE_INTENT, owner),
            hangUp = intentOf(extras, EXTRA_HANG_UP_INTENT, owner),
            actions = notification.actions.orEmpty().map { action -> action.actionIntent?.let { wrap(it, owner) } },
        )
    }

    private fun intentOf(
        extras: Bundle,
        key: String,
        owner: String,
    ): AppIntent? = BundleCompat.getParcelable(extras, key, PendingIntent::class.java)?.let { wrap(it, owner) }

    /** The intent when [owner], the app that posted the notification, created it; otherwise none. */
    private fun wrap(
        pending: PendingIntent,
        owner: String,
    ): AppIntent? = PendingAppIntent(pending, context).takeIf { pending.creatorPackage == owner }

    private fun callerOf(notification: Notification): String? {
        val extras = notification.extras
        val person = BundleCompat.getParcelable(extras, EXTRA_CALL_PERSON, Person::class.java)
        return person?.name?.toString() ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    }

    companion object {
        // `Notification.CallStyle` extras (API 31 constants, spelled out so minSdk 29 needs no version check).
        private const val EXTRA_CALL_TYPE = "android.callType"
        private const val EXTRA_CALL_PERSON = "android.callPerson"
        private const val EXTRA_ANSWER_INTENT = "android.answerIntent"
        private const val EXTRA_DECLINE_INTENT = "android.declineIntent"
        private const val EXTRA_HANG_UP_INTENT = "android.hangUpIntent"
        private const val CALL_STYLE_TEMPLATE = "android.app.Notification\$CallStyle"

        /**
         * The `android.callType` of a real `CallStyle` notification, `null` for any other. From API 31 the platform
         * template must be `Notification.CallStyle`, which Android posts only for an app with a foreground service or
         * a user-initiated job, or one asking for a full-screen intent: the bare extra, which any app can add to an
         * ordinary notification, would otherwise make a fake call whose answer intent HandLive sends with its
         * background-start exemption. Below API 31 there is no platform `CallStyle` (apps use the compat extras) and
         * nothing to check. An app that posts a real `CallStyle` still gets that exemption when the user answers.
         */
        internal fun callTypeOf(
            notification: Notification,
            sdk: Int,
        ): Int? {
            val extras = notification.extras
            val template = extras.getString(Notification.EXTRA_TEMPLATE)
            val styled = sdk < Build.VERSION_CODES.S || template == CALL_STYLE_TEMPLATE
            return if (styled && extras.containsKey(EXTRA_CALL_TYPE)) extras.getInt(EXTRA_CALL_TYPE) else null
        }

        /**
         * The filter of the listener thread (CALL-05 API 1 logic 1): only a `CallStyle` notification ([callTypeOf])
         * or an ongoing one (`FLAG_ONGOING_EVENT`, a possible in-call notification) can matter to
         * A-CALL; every other one is dropped before it is read or queued.
         */
        fun candidate(
            sbn: StatusBarNotification,
            sdk: Int = Build.VERSION.SDK_INT,
        ): Boolean = ongoing(sbn.notification) || callTypeOf(sbn.notification, sdk) != null

        private fun ongoing(notification: Notification) = notification.flags and Notification.FLAG_ONGOING_EVENT != 0
    }
}
