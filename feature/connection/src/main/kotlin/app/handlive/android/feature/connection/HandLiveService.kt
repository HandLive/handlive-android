package app.handlive.android.feature.connection

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.connection.notification.ServiceNotification
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-SVC: foreground service of type `connectedDevice` (SET-01 API 3). It shows the ongoing `hl_service`
 * notification within the 5 s Android allows, then starts [ConnectionRuntime]; `START_STICKY` brings it back when
 * the system or an OEM kills it (CONN-02 E6).
 */
class HandLiveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var running = false

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val runtime = ConnectionRuntime.get(this)
        val foreground =
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    ServiceNotification.ID,
                    notification(runtime),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            }
        if (foreground.isFailure) {
            // ForegroundServiceStartNotAllowedException or SecurityException (SET-01 E2).
            runtime.markLaunch(accepted = false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running) {
            running = true
            scope.launch { runtime.start() }
            // CONN-01 field 6: the text follows the connected clients; CLIP-01 field 4: the button needs a client
            // with active clipboard.
            combine(runtime.connectedPeers, clipboardAvailable(runtime)) { peers, available -> peers to available }
                .drop(1)
                .onEach { NotificationManagerCompat.from(this).notifyIfAllowed(notification(runtime)) }
                .launchIn(scope)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val runtime = ConnectionRuntime.get(this)
        scope.launch { withContext(NonCancellable) { runtime.stop() } }.invokeOnCompletion { scope.cancel() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(runtime: ConnectionRuntime) =
        ServiceNotification.build(
            this,
            runtime.connectedPeers.value,
            openAppIntent(this),
            ServiceHooks.sendClipboardIntent
                ?.takeIf {
                    runtime.sessions.value.values
                        .any { it.isEffective(Feature.CLIPBOARD) }
                }?.invoke(this),
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun clipboardAvailable(runtime: ConnectionRuntime): Flow<Boolean> =
        runtime.sessions
            .flatMapLatest { open ->
                if (open.isEmpty()) {
                    flowOf(false)
                } else {
                    combine(open.values.map { it.effectiveFeatures }) { sets -> sets.any { Feature.CLIPBOARD in it } }
                }
            }.distinctUntilChanged()

    private fun NotificationManagerCompat.notifyIfAllowed(notification: android.app.Notification) {
        if (areNotificationsEnabled()) runCatching { notify(ServiceNotification.ID, notification) }
    }

    private companion object {
        fun openAppIntent(context: Context): PendingIntent? =
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            }
    }
}

/** Hooks the app installs at start-up, so this module does not depend on the clipboard feature. */
object ServiceHooks {
    /** The "Send Clipboard" action of the service notification (CLIP-01 field 4). */
    @Volatile
    var sendClipboardIntent: ((Context) -> PendingIntent)? = null
}
