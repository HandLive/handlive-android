package app.handlive.android.feature.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.handlive.android.core.data.HandLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Restarts A-SVC after a reboot or an app update (SET-01 API 3: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`), once the
 * first-run setup is complete. Android 15 still allows `connectedDevice` from `BOOT_COMPLETED`.
 */
class ServiceRestartReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (HandLiveData
                        .get(context)
                        .settings
                        .current()
                        .setupCompletedAt !=
                    null
                ) {
                    ServiceLauncher.start(context)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
