package app.handlive.android.feature.connection.capability

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.capability.CapabilityData
import app.handlive.android.core.protocol.capability.CapabilityFeatures
import app.handlive.android.core.protocol.capability.ClipboardFeature
import app.handlive.android.core.protocol.capability.RelayFeature
import app.handlive.android.core.protocol.session.PROTOCOL_VERSION

/** Facts about this phone that feed the capability but are not settings (SET-01 API 2, CLIP-01 A3). */
data class LocalEnvironment(
    /** "1.0.0 (100)": versionName and versionCode. */
    val appVersion: String,
    val osVersion: String,
    val model: String,
    /** `ClipboardAccessibilityService` is connected (auto-send needs it, D4). */
    val accessibilityServiceRunning: Boolean,
    /** Android 13+ and `POST_NOTIFICATIONS` not granted (SET-01 E1). */
    val notificationsMissing: Boolean,
)

/**
 * Builds this phone's `capability/hello|update` (0.7.2, SET-02 API 1). Phase 1 implements the clipboard only, so
 * SMS, calls, call audio and camera are absent — a feature the phone does not have yet is off for every peer.
 */
object LocalCapabilityBuilder {
    const val MAX_TEXT_BYTES = 1_048_576L
    const val MAX_IMAGE_BYTES = 10_485_760L
    val TEXT_MIMES = listOf("text/plain")
    val ALL_MIMES = listOf("text/plain", "image/png", "image/jpeg")
    const val NOTIFICATIONS_PERMISSION = "POST_NOTIFICATIONS"

    fun build(
        settings: HandLiveSettings,
        environment: LocalEnvironment,
    ): CapabilityData =
        CapabilityData(
            protocol = PROTOCOL_VERSION,
            appVersion = environment.appVersion,
            platform = "android",
            osVersion = environment.osVersion,
            model = environment.model,
            features =
                CapabilityFeatures(
                    clipboard =
                        ClipboardFeature(
                            enabled = settings.clipboardEnabled,
                            // CLIP-01 E1: auto-send needs the setting, the recorded consent and the running service.
                            autoSend =
                                settings.clipAutoSend &&
                                    settings.clipA11yConsentAt != null &&
                                    environment.accessibilityServiceRunning,
                            maxTextBytes = MAX_TEXT_BYTES,
                            maxImageBytes = MAX_IMAGE_BYTES,
                            mimes = if (settings.clipSendImages) ALL_MIMES else TEXT_MIMES,
                        ),
                    relay = RelayFeature(enabled = settings.relayEnabled),
                ),
            // Only permissions of enabled features count, plus notifications on Android 13+ (SET-01 API 2 rule 1).
            permissionsMissing =
                if (environment.notificationsMissing) {
                    listOf(
                        NOTIFICATIONS_PERMISSION,
                    )
                } else {
                    emptyList()
                },
        )
}
