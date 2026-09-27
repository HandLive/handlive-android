package app.handlive.android.settings

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import app.handlive.android.MainActivity
import app.handlive.android.core.crypto.keystore.AndroidKeystoreSecretStore
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.core.transport.tls.AndroidTlsIdentityStorage
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.ServiceLauncher
import app.handlive.android.feature.connection.ServiceState
import app.handlive.android.feature.pairing.PairingFeature
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Delete All HandLive Data" on this phone once the relay step is done or skipped (SET-02 A4–A6, API 7): the
 * clients on the LAN or USB are told (`pair/revoke`, `reason = reinstall`) and every pair is deleted; the
 * Accessibility service turns itself off (its state lives in the system settings) and the service stops, so no
 * connection remains; then the keys go first (`hl_master` makes anything left unreadable), then the TLS identity, the
 * database, the settings and the notifications; HandLive starts again at the welcome screen with new keys.
 */
class DataEraser(
    context: Context,
) {
    private val context = context.applicationContext
    private val data = HandLiveData.get(this.context)

    suspend fun erase() {
        PairingFeature.get(context).unpair.revokeAllForReinstall()
        stopEverything()
        // One stretch off the main thread up to the exit, so the UI never runs on the deleted data.
        withContext(Dispatchers.IO) {
            AndroidKeystoreSecretStore.destroy(context)
            AndroidTlsIdentityStorage.delete(context)
            data.deleteDatabase()
            data.settings.clear()
            NotificationManagerCompat.from(context).cancelAll()
            restart()
        }
    }

    private suspend fun stopEverything() {
        val clipboard = ClipboardFeature.get(context)
        data.settings.set(SettingsKeys.CLIP_AUTO_SEND, false)
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            while (clipboard.accessibilityRunning) delay(POLL_MILLIS)
        }
        ServiceLauncher.stop(context)
        withTimeoutOrNull(STOP_TIMEOUT_MILLIS) {
            ConnectionRuntime.get(context).state.first { it == ServiceState.STOPPED || it == ServiceState.FAILED }
        }
    }

    /** Every process singleton held the deleted keys and database: a new process starts from the welcome screen. */
    private fun restart() {
        context.startActivity(Intent.makeRestartActivityTask(ComponentName(context, MainActivity::class.java)))
        Runtime.getRuntime().exit(0)
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 3_000L
        const val POLL_MILLIS = 100L
    }
}
