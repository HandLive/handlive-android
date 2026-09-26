package app.handlive.android.core.data.settings

/**
 * Settings keys of the Android hub (0.9.5), with the spec defaults. Settings belong to this device and apply to
 * every pair; peers only learn about them through `capability` (SET-02).
 */
data class HandLiveSettings(
    val setupStartedAt: Long? = null,
    val setupCompletedAt: Long? = null,
    /** `perm.requested`: permissions asked at least once, to tell "never asked" from "denied for good". */
    val permissionsRequested: Set<String> = emptySet(),
    val clipboardEnabled: Boolean = true,
    val smsEnabled: Boolean = true,
    val callEnabled: Boolean = true,
    val callAudioEnabled: Boolean = false,
    val allowOpusFallback: Boolean = true,
    val cameraEnabled: Boolean = false,
    val relayEnabled: Boolean = true,
    val clipAutoSend: Boolean = true,
    val clipA11yConsentAt: Long? = null,
    val clipSendImages: Boolean = true,
    val clipBlockSensitive: Boolean = true,
    /** `clip.auto_clear_s`: 0 (off), 60 or 300 seconds (CLIP-05). */
    val clipAutoClearSeconds: Int = DEFAULT_AUTO_CLEAR_SECONDS,
) {
    companion object {
        const val DEFAULT_AUTO_CLEAR_SECONDS = 60
        val AUTO_CLEAR_CHOICES = listOf(0, 60, 300)
    }
}
