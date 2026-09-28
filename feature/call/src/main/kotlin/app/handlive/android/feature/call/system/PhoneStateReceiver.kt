package app.handlive.android.feature.call.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import app.handlive.android.feature.call.context.BroadcastCopy
import app.handlive.android.feature.call.context.PhoneState

/**
 * `ACTION_PHONE_STATE_CHANGED`, registered at runtime (CALL-01 API 3). With `READ_PHONE_STATE` and `READ_CALL_LOG`
 * every change arrives twice, in no fixed order, and only one copy may carry `EXTRA_INCOMING_NUMBER`: each copy goes to
 * the tracker as it is, the key's presence included. The broadcast never changes the context state.
 */
class PhoneStateReceiver(
    context: Context,
    private val clock: () -> Long,
    private val onCopy: (BroadcastCopy) -> Unit,
) {
    private val appContext = context.applicationContext
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                val state = PhoneState.fromExtra(intent.getStringExtra(TelephonyManager.EXTRA_STATE)) ?: return
                onCopy(BroadcastCopy(state, intent.hasExtra(NUMBER), intent.getStringExtra(NUMBER), clock()))
            }
        }
    private var registered = false

    @Synchronized
    fun register(): Boolean {
        if (!registered) {
            registered =
                runCatching {
                    // A protected system broadcast: it reaches a receiver that other apps cannot call.
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                }.isSuccess
        }
        return registered
    }

    @Synchronized
    fun unregister() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private companion object {
        /** Deprecated since API 29, still sent to apps with `READ_CALL_LOG` (API 3). */
        @Suppress("DEPRECATION")
        const val NUMBER = TelephonyManager.EXTRA_INCOMING_NUMBER
    }
}
