package app.handlive.android

import android.app.Application
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.notification.NotificationChannels

/**
 * Process start: notification channels exist before anything posts, and feature modules register with the
 * connection runtime before [app.handlive.android.feature.connection.HandLiveService] can start (boot, update).
 */
class HandLiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.createAll(this)
        BenchLog.install(this)
    }
}
