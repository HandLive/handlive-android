package app.handlive.android

import android.app.Application
import app.handlive.android.core.transport.relay.RelayConfig
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.notification.NotificationChannels
import app.handlive.android.feature.pairing.PairingFeature
import app.handlive.android.feature.relay.RelayFeature
import app.handlive.android.feature.sms.SmsFeature
import app.handlive.android.push.PushBootstrap

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
        SmsFeature.install(this)
        RelayFeature.install(this, relayConfig())
        PushBootstrap.start(this)
    }

    /** CONN-03: this build's relay host and the pins of its certificate chain (0.4.3). */
    private fun relayConfig(): RelayConfig {
        val extraPins =
            BuildConfig.RELAY_EXTRA_PINS
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return RelayConfig(BuildConfig.RELAY_HOST.trim(), RelayConfig.DEFAULT_PINS + extraPins)
    }
}
