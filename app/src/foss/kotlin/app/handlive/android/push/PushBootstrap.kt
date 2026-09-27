package app.handlive.android.push

import android.content.Context

/**
 * The default flavor has no Play Services and no Firebase (plan decision I8): no push token, no FCM wake-up. A client
 * outside the LAN reaches this phone while it is connected to the relay (CONN-03).
 */
object PushBootstrap {
    @Suppress("UNUSED_PARAMETER")
    fun start(context: Context) = Unit
}
