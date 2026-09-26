package app.handlive.android.push

import app.handlive.android.feature.relay.RelayFeature
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * FCM data messages (CONN-04 API 3, step 9a): `{"t":"wake","p":<pair_id>,"r":<reason>}` without content. A wake-up
 * starts the connection service (high-priority FCM may start a foreground service) and connects to the relay, where
 * the client that asked is waiting.
 */
class FcmPushService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data[TYPE] == WAKE) RelayFeature.get().onWake()
    }

    override fun onNewToken(token: String) {
        RelayFeature.get().onPushToken(token)
    }

    private companion object {
        const val TYPE = "t"
        const val WAKE = "wake"
    }
}
