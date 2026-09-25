package app.handlive.android.feature.clipboard.component

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Intent
import android.content.res.Resources
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.engine.CopySignal
import app.handlive.android.feature.clipboard.engine.CopySignalMatcher
import app.handlive.android.feature.clipboard.engine.SignalKind
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * CLIP-01 API 1: notices the Copy action from tap events, "copied" toasts and announcements and the system
 * clipboard overlay, debounces the signals for 300 ms and opens `ClipboardReadActivity`. It works only with
 * `feature.clipboard`, `clip.auto_send` and the recorded consent; it never reads window content, and event text
 * is matched in memory and dropped (logic 4). Its binding drives `features.clipboard.auto_send` (A3).
 */
class ClipboardAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var scheduled = false
    private val openReader =
        Runnable {
            scheduled = false
            ClipboardReadActivity.startAutomaticRead(this)
        }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val feature = ClipboardFeature.get(this)
        feature.setAccessibilityRunning(true)
        scope?.cancel()
        scope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main).also { serviceScope ->
                // Logic 1: turning "Auto-Send on Copy" off in HandLive turns the service off in the system too.
                feature
                    .autoSendSetting()
                    .drop(1)
                    .filter { enabled -> !enabled }
                    .onEach { disableSelf() }
                    .launchIn(serviceScope)
            }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val feature = ClipboardFeature.get(this)
        if (isCopySignal(event, feature) && feature.module.acceptCopySignal()) {
            if (!scheduled) BenchLog.event(BenchEvent.COPY_DETECTED)
            scheduled = true
            handler.removeCallbacks(openReader)
            handler.postDelayed(openReader, ClipLimits.DETECT_DEBOUNCE_MILLIS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopWatching()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopWatching()
        super.onDestroy()
    }

    private fun stopWatching() {
        handler.removeCallbacks(openReader)
        scheduled = false
        scope?.cancel()
        scope = null
        ClipboardFeature.get(this).setAccessibilityRunning(false)
    }

    /** Logic 1–2: only with the settings and consent, never for HandLive's own windows. */
    private fun isCopySignal(
        event: AccessibilityEvent,
        feature: ClipboardFeature,
    ): Boolean =
        feature.autoSendAllowed() &&
            event.packageName?.toString() != packageName &&
            CopySignalMatcher.matches(signalOf(event), copyLabels())

    private fun signalOf(event: AccessibilityEvent): CopySignal =
        CopySignal(
            kind =
                when (event.eventType) {
                    AccessibilityEvent.TYPE_VIEW_CLICKED -> SignalKind.VIEW_CLICKED
                    AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> SignalKind.NOTIFICATION
                    AccessibilityEvent.TYPE_ANNOUNCEMENT -> SignalKind.ANNOUNCEMENT
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> SignalKind.WINDOW_STATE
                    else -> SignalKind.OTHER
                },
            packageName = event.packageName?.toString(),
            className = event.className?.toString(),
            texts = event.text.map(CharSequence::toString) + listOfNotNull(event.contentDescription?.toString()),
            fromToast = event.parcelableData !is Notification,
        )

    /** `android.R.string.copy` and `cut` in the system language, not the app's (API 1 response table). */
    private fun copyLabels(): List<String> =
        Resources.getSystem().let { listOf(it.getString(android.R.string.copy), it.getString(android.R.string.cut)) }
}
