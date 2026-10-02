package app.handlive.android.feature.call.appcall

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Sending a calling app's PendingIntent from the background (spike T3.2): only the answer intent carries the
 * background-activity-start option — ALLOW_ALWAYS on API 36+, the deprecated ALLOWED on 34–35, none before —,
 * every other intent goes without it, and an intent the app canceled is a `false`, not a crash.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PendingAppIntentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theBackgroundStartModeFollowsTheApiLevel() {
        assertNull(BackgroundStartOptions.mode(29))
        assertNull(BackgroundStartOptions.mode(33))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED, BackgroundStartOptions.mode(34))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED, BackgroundStartOptions.mode(35))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, BackgroundStartOptions.mode(36))
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS, BackgroundStartOptions.mode(37))
    }

    @Test
    fun theOptionsBundleExistsFromApi34Only() {
        assertNull(BackgroundStartOptions.bundle(33))
        assertNotNull(BackgroundStartOptions.bundle(34))
        assertNotNull(BackgroundStartOptions.bundle(36))
    }

    @Test
    fun onlyAnAnswerCarriesTheBackgroundStartOption() {
        val pending =
            PendingIntent.getBroadcast(context, 3, Intent("x.y.A").setPackage("x.y"), PendingIntent.FLAG_IMMUTABLE)

        assertNotNull(PendingAppIntent(pending, context, sdk = 36).options(backgroundStart = true))
        assertNull("decline, end, an ordinary action", PendingAppIntent(pending, context, sdk = 36).options(false))
        assertNull("before API 34 there is no option", PendingAppIntent(pending, context, sdk = 33).options(true))
    }

    @Test
    fun sendFiresTheAppsBroadcast() {
        val pending =
            PendingIntent.getBroadcast(
                context,
                1,
                Intent("org.telegram.messenger.DECLINE").setPackage("org.telegram.messenger"),
                PendingIntent.FLAG_IMMUTABLE,
            )

        assertTrue(PendingAppIntent(pending, context, sdk = 35).send(backgroundStart = false))

        assertEquals(
            listOf("org.telegram.messenger.DECLINE"),
            shadowOf(context as android.app.Application).broadcastIntents.map { it.action },
        )
    }

    @Test
    fun anIntentTheAppCanceledIsNotSent() {
        val pending =
            PendingIntent.getBroadcast(context, 2, Intent("x.y.Z").setPackage("x.y"), PendingIntent.FLAG_IMMUTABLE)
        pending.cancel()

        assertFalse(PendingAppIntent(pending, context, sdk = 35).send(backgroundStart = true))
    }
}
