package app.handlive.android.feature.call.system

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import app.handlive.android.feature.call.context.PhoneState
import app.handlive.android.feature.call.context.SimReport
import java.util.concurrent.Executor

/**
 * The call state listeners of CALL-01 API 2: one on the default `TelephonyManager` (the aggregate state, the only
 * source of the context state) and one per active SIM on `createForSubscriptionId(subId)` (only to find `sub_id`).
 * API 31+ uses `TelephonyCallback.CallStateListener`, API 29–30 `PhoneStateListener` with `LISTEN_CALL_STATE`, whose
 * `phoneNumber` is not used — the number always comes from the broadcast. On registration the system reports the
 * current state at once (E8). Callbacks run on [executor], the A-CALL thread; [clock] stamps them.
 */
class TelephonyWatcher(
    context: Context,
    private val executor: Executor,
    private val clock: () -> Long,
    private val onState: (PhoneState, Long) -> Unit,
    private val onSimState: (SimReport) -> Unit,
) {
    private val telephony = context.applicationContext.getSystemService(TelephonyManager::class.java)
    private var main: Registration? = null
    private val perSim = HashMap<Int, Registration>()

    /** Registers the default listener and the per-SIM ones; `false` when the system refused (`READ_PHONE_STATE`). */
    @Synchronized
    fun start(subIds: List<Int>): Boolean {
        val manager = telephony
        if (main == null && manager != null) {
            main = register(manager) { state -> PhoneState.fromCallState(state)?.let { onState(it, clock()) } }
        }
        if (main != null) updateSims(subIds)
        return main != null
    }

    /** The SIM list changed (`OnSubscriptionsChangedListener`): the per-SIM listeners follow it. */
    @Synchronized
    fun updateSims(subIds: List<Int>) {
        val manager = telephony ?: return
        (perSim.keys - subIds.toSet()).forEach { perSim.remove(it)?.remove() }
        for (subId in subIds.filterNot(perSim::containsKey)) {
            val simManager = runCatching { manager.createForSubscriptionId(subId) }.getOrNull() ?: continue
            register(simManager) { state ->
                PhoneState.fromCallState(state)?.let { onSimState(SimReport(subId, it, clock())) }
            }?.let { perSim[subId] = it }
        }
    }

    /** `feature.call` off or `READ_PHONE_STATE` lost (API 2 logic 4): every listener goes. */
    @Synchronized
    fun stop() {
        main?.remove()
        main = null
        perSim.values.forEach(Registration::remove)
        perSim.clear()
    }

    private fun register(
        manager: TelephonyManager,
        onChange: (Int) -> Unit,
    ): Registration? =
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                CallbackRegistration(manager, executor, onChange)
            } else {
                ListenerRegistration(manager, executor, onChange)
            }
        } catch (_: SecurityException) {
            null
        }

    private interface Registration {
        fun remove()
    }

    /** API 31+; needs `READ_PHONE_STATE`, checked by the caller (a `SecurityException` means it was revoked). */
    @RequiresApi(Build.VERSION_CODES.S)
    @SuppressLint("MissingPermission")
    private class CallbackRegistration(
        private val manager: TelephonyManager,
        executor: Executor,
        onChange: (Int) -> Unit,
    ) : Registration {
        private val callback =
            object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = onChange(state)
            }

        init {
            manager.registerTelephonyCallback(executor, callback)
        }

        override fun remove() {
            runCatching { manager.unregisterTelephonyCallback(callback) }
        }
    }

    /** API 29–30: `PhoneStateListener` on [executor] (constructor of API 29). */
    @Suppress("DEPRECATION")
    private class ListenerRegistration(
        private val manager: TelephonyManager,
        executor: Executor,
        onChange: (Int) -> Unit,
    ) : Registration {
        private val listener =
            object : PhoneStateListener(executor) {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(
                    state: Int,
                    phoneNumber: String?,
                ) = onChange(state)
            }

        init {
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }

        override fun remove() {
            runCatching { manager.listen(listener, PhoneStateListener.LISTEN_NONE) }
        }
    }
}
