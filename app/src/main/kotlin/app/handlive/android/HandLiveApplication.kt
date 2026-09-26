package app.handlive.android

import android.app.Application
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.pairing.PairingFeature

/**
 * Process start: notification channels exist before anything posts, and feature modules register with the
 * connection runtime before [app.handlive.android.feature.connection.HandLiveService] can start (boot, update).
 */
class HandLiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        BenchLog.install(this)
        PairingFeature.install(this)
        ClipboardFeature.install(this)
    }
}
