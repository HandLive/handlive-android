package app.handlive.android.push

import android.content.Context
import app.handlive.android.BuildConfig
import app.handlive.android.feature.relay.RelayFeature
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

/**
 * FCM of the `gms` flavor (CONN-04): Firebase is set up from build settings (no google-services.json in git); the
 * registration token goes to the relay (`PUT /v1/devices/me/push-token`). Without the settings there is no push.
 */
object PushBootstrap {
    fun start(context: Context) {
        val options = options() ?: return
        val app = runCatching { FirebaseApp.getInstance() }.getOrNull() ?: FirebaseApp.initializeApp(context, options)
        if (app == null) return
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            if (!token.isNullOrBlank()) RelayFeature.get().onPushToken(token)
        }
    }

    private fun options(): FirebaseOptions? {
        val values =
            listOf(
                BuildConfig.FCM_APPLICATION_ID,
                BuildConfig.FCM_API_KEY,
                BuildConfig.FCM_PROJECT_ID,
                BuildConfig.FCM_SENDER_ID,
            )
        if (values.any { it.isBlank() }) return null
        return FirebaseOptions
            .Builder()
            .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
            .setApiKey(BuildConfig.FCM_API_KEY)
            .setProjectId(BuildConfig.FCM_PROJECT_ID)
            .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
            .build()
    }
}
