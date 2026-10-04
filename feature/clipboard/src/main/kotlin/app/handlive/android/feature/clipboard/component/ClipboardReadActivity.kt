package app.handlive.android.feature.clipboard.component

import android.app.Activity
import android.app.PendingIntent
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog

/**
 * CLIP-01 API 2 and 4, CLIP-05 step 7: a transparent activity without animation that takes focus for an instant —
 * Android 10+ lets only the focused app read the clipboard. It reads item 0 in `onWindowFocusChanged(true)` and
 * closes (an image once its copy is done), reads only the description in verification mode, or takes the text of
 * a Share intent without touching the clipboard. No focus within 1 s → E3.
 */
class ClipboardReadActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var handled = false
    private val giveUp = Runnable { onNoFocus() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (launchOf(intent)) {
            Launch.SHARE -> {
                onShare()
            }

            Launch.OWN -> {
                handler.postDelayed(giveUp, ClipLimits.FOCUS_WAIT_MILLIS)
            }

            Launch.REFUSED -> {
                // Another app reached the exported Share alias without ACTION_SEND: nothing is read or sent.
                handled = true
                finishQuietly()
            }
        }
    }

    private fun onShare() {
        handled = true
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        val read =
            if (text.isEmpty()) {
                LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, ClipboardValues.SOURCE_SHARE)
            } else {
                // API 4 logic 1–3: sent only, never written to the phone's clipboard; no IS_SENSITIVE to read.
                LocalRead.Text(text, sensitiveExtra = false, source = ClipboardValues.SOURCE_SHARE)
            }
        ClipboardFeature.get(this).module.onLocalRead(read)
        finishQuietly()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || handled) return
        handled = true
        handler.removeCallbacks(giveUp)
        val feature = ClipboardFeature.get(this)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_VERIFY) {
            feature.module.trace.deliverVerification(feature.clipboard.describe())
            finishQuietly()
        } else {
            val clip = getSystemService(ClipboardManager::class.java).primaryClip
            feature.readInBackground(clip, sourceOf(intent)) { runOnUiThread(::finishQuietly) }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(giveUp)
        super.onDestroy()
    }

    private fun onNoFocus() {
        if (handled) return
        handled = true
        val feature = ClipboardFeature.get(this)
        if (intent.getStringExtra(EXTRA_MODE) == MODE_VERIFY) {
            feature.module.trace.deliverVerification(null)
        } else {
            // E3 without a clip read: the window never got focus (another window, the lock screen, an OEM overlay).
            BenchLog.event(
                BenchEvent.CLIP_READ_FAILED,
                "reason" to ReadFailure.EMPTY_OR_NOT_TEXT.name.lowercase(),
                "stage" to "focus",
                "why" to "no_focus",
                "source" to sourceOf(intent),
            )
            feature.module.onLocalRead(LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, sourceOf(intent)))
        }
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            overridePendingTransition(0, 0)
        }
    }

    /** How the activity was started (CLIP-01 API 2, API 4). */
    internal enum class Launch {
        /** `ACTION_SEND` from another app's share sheet: only `EXTRA_TEXT` is read. */
        SHARE,

        /** HandLive's own intent (the activity itself is not exported): `source` and `mode` extras count. */
        OWN,

        /** Any other start, the exported Share alias without `ACTION_SEND` included: ignored, nothing is read. */
        REFUSED,
    }

    companion object {
        /** The exported `activity-alias` of the manifest; every other entry names this activity itself. */
        internal const val SHARE_ALIAS = "app.handlive.android.feature.clipboard.component.ClipboardShareTarget"

        /**
         * Only `ACTION_SEND` is handled through the alias, and extras other than `EXTRA_TEXT` are honoured only from
         * HandLive's own intents, which name the (non-exported) activity directly.
         */
        internal fun launchOf(intent: Intent?): Launch =
            when {
                intent?.action == Intent.ACTION_SEND -> Launch.SHARE

                // Deny by default: only an intent naming this (non-exported) activity is HandLive's own.
                intent?.component?.className == ClipboardReadActivity::class.java.name -> Launch.OWN

                else -> Launch.REFUSED
            }

        private const val EXTRA_SOURCE = "source"
        private const val EXTRA_MODE = "mode"
        private const val MODE_VERIFY = "verify"
        private const val REQUEST_MANUAL = 1

        private fun sourceOf(intent: Intent): String =
            intent.getStringExtra(EXTRA_SOURCE) ?: ClipboardValues.SOURCE_AUTO

        private fun intent(
            context: Context,
            source: String,
        ): Intent =
            Intent(context, ClipboardReadActivity::class.java)
                .putExtra(EXTRA_SOURCE, source)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

        /** API 1 logic 3: a bound Accessibility service may start activities from the background. */
        fun startAutomaticRead(context: Context) {
            runCatching { context.startActivity(intent(context, ClipboardValues.SOURCE_AUTO)) }.onFailure {
                // The system refused the background start (CLIP-01 API 1 logic 3 did not hold on this build).
                BenchLog.event(
                    BenchEvent.CLIP_READ_FAILED,
                    "reason" to ReadFailure.EMPTY_OR_NOT_TEXT.name.lowercase(),
                    "stage" to "start",
                    "why" to (it::class.simpleName ?: "exception"),
                    "source" to ClipboardValues.SOURCE_AUTO,
                )
            }
        }

        /** The tile's target on API 29–33 (`startActivityAndCollapse(Intent)`). */
        fun manualIntent(context: Context): Intent = intent(context, ClipboardValues.SOURCE_MANUAL)

        /** API 3: the notification button and the tile open the activity directly (no trampoline, Android 12+). */
        fun manualPendingIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_MANUAL,
                intent(context, ClipboardValues.SOURCE_MANUAL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        /** CLIP-05 step 7: reads only the description; `false` when the activity could not start. */
        fun startVerification(context: Context): Boolean =
            runCatching {
                context.startActivity(intent(context, ClipboardValues.SOURCE_AUTO).putExtra(EXTRA_MODE, MODE_VERIFY))
            }.isSuccess
    }
}
