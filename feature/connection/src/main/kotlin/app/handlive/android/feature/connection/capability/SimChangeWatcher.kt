package app.handlive.android.feature.connection.capability

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import java.util.concurrent.Executor

/**
 * Calls [onChange] when the SIMs change (SET-02 API 1 logic 1: `OnSubscriptionsChangedListener`), so `features.sms`
 * (`sims`, `default_sub_id`) goes out again as `capability/update`.
 */
class SimChangeWatcher(
    context: Context,
    private val onChange: () -> Unit,
) {
    private val subscriptions = context.applicationContext.getSystemService(SubscriptionManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var listener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    /** Registers on the main thread (API 29 builds the listener's handler from the calling thread's looper). */
    fun start() {
        mainHandler.post {
            if (listener != null || subscriptions == null) return@post
            val created =
                object : SubscriptionManager.OnSubscriptionsChangedListener() {
                    override fun onSubscriptionsChanged() = onChange()
                }
            val registered =
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        subscriptions.addOnSubscriptionsChangedListener(Executor(mainHandler::post), created)
                    } else {
                        @Suppress("DEPRECATION")
                        subscriptions.addOnSubscriptionsChangedListener(created)
                    }
                }.isSuccess
            if (registered) listener = created
        }
    }

    fun stop() {
        mainHandler.post {
            listener?.let { runCatching { subscriptions?.removeOnSubscriptionsChangedListener(it) } }
            listener = null
        }
    }
}
