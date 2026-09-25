package app.handlive.android.feature.clipboard.component

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.handlive.android.core.data.settings.SettingsKeys
import app.handlive.android.core.data.settings.SettingsStore

/**
 * CLIP-01 A1–A3 behind the disclosure (fields 1–3): "Agree" records `clip.a11y_consent_at` and leads to Settings ›
 * Accessibility; "Send Manually" turns `clip.auto_send` off (E1), and a running service then turns itself off. The UI
 * shows the disclosure text from the catalog and reads the service state here (API 1 logic 6).
 */
class AccessibilityConsent(
    private val context: Context,
    private val settings: SettingsStore,
) {
    /** "Agree": the consent time is stored and auto-send is on; then [settingsIntent] opens the system page (A2). */
    suspend fun agree(now: Long) {
        settings.set(SettingsKeys.CLIP_A11Y_CONSENT_AT, now)
        settings.set(SettingsKeys.CLIP_AUTO_SEND, true)
    }

    /** "Send Manually", or field 1 turned off: only the manual path remains (E1). */
    suspend fun sendManually() = settings.set(SettingsKeys.CLIP_AUTO_SEND, false)

    /** A2: Settings › Accessibility, where the user selects HandLive. */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** The service is turned on in the system settings (it may still be binding). */
    fun isServiceEnabled(): Boolean =
        context
            .getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                info.resolveInfo?.serviceInfo?.let {
                    it.packageName == context.packageName && it.name == ClipboardAccessibilityService::class.java.name
                } == true
            }
}
