package app.handlive.android.feature.call.appcall

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle

/**
 * How the answer intent of another app is sent from the background (spike T3.2). An activity start needs the sender
 * to opt in to background activity starts (API 34+): `MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS` on API 36+, the
 * deprecated `ALLOWED` on 34–35. Android still refuses the start unless HandLive holds an exemption (its bound
 * accessibility service), which is what `answer_mode` reports. The option lends HandLive's privilege to the app, so
 * it goes with the answer intent only.
 */
object BackgroundStartOptions {
    private const val API_34 = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    private const val API_36 = Build.VERSION_CODES.BAKLAVA

    /** The `setPendingIntentBackgroundActivityStartMode` value for [sdk], `null` where the option does not exist. */
    @Suppress("DEPRECATION")
    fun mode(sdk: Int): Int? =
        when {
            sdk >= API_36 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            sdk >= API_34 -> ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            else -> null
        }

    /** The options of a send; `mode` is non-null only from API 34, where the option's setter exists. */
    @SuppressLint("NewApi")
    fun bundle(sdk: Int): Bundle? =
        mode(sdk)?.let { ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(it).toBundle() }
}

/**
 * [AppIntent] over a calling app's `PendingIntent` that the app created itself ([AppNotificationReader] checks the
 * creator); [pending] is also the tap-to-answer notification's content.
 */
class PendingAppIntent(
    val pending: PendingIntent,
    private val context: Context,
    private val sdk: Int = Build.VERSION.SDK_INT,
) : AppIntent {
    override fun send(backgroundStart: Boolean): Boolean =
        try {
            pending.send(context, 0, null, null, null, null, options(backgroundStart))
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }

    /** The send options: the background-start option for [backgroundStart] (from API 34), none otherwise. */
    internal fun options(backgroundStart: Boolean): Bundle? =
        if (backgroundStart) BackgroundStartOptions.bundle(sdk) else null
}
