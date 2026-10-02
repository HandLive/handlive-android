package app.handlive.spike.call.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Does nothing. HandLive already runs an accessibility service (the clipboard one), and a service bound by the system
 * may exempt an app from background activity start limits; turning this one on by hand reproduces that condition for
 * spike question 2 (variant B). It reads no window content and listens to no other app.
 */
class BalExemptionService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        SpikeLog.write(this, listOf("ev" to "a11y_connected"))
    }
}
