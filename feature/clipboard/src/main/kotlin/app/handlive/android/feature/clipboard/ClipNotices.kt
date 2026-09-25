package app.handlive.android.feature.clipboard

/** A message reported in place as a toast (C19: clipboard errors are never pushed as notifications). */
sealed interface ClipMessage {
    /** CLIP-01 field 11: the manual send reached a device. */
    data class SentTo(
        val deviceName: String,
    ) : ClipMessage

    /** CLIP-01 field 11: the manual send is the clip just received from [deviceName] (QC4, E9). */
    data class SkippedJustReceived(
        val deviceName: String,
    ) : ClipMessage

    /** CLIP-01 field 11: [deviceName] refused the manual send (not `FEATURE_DISABLED`, not `CLIP_TOO_LARGE`). */
    data class WriteFailedOnDevice(
        val deviceName: String,
    ) : ClipMessage

    /** CLIP-01 E6 on the manual path. */
    data object NotConnected : ClipMessage

    /** CLIP-01 E3 on the manual path. */
    data object EmptyOrNotText : ClipMessage

    /** CLIP-01 E5 (field 10). */
    data object TextTooLarge : ClipMessage

    /** CLIP-03 E2 (field 5). */
    data object ImageTooLarge : ClipMessage

    /** CLIP-03 E3 on the manual path. */
    data object ImageUnreadable : ClipMessage

    /** CLIP-03 E4, second checksum mismatch (field 6). */
    data object ImageSendFailed : ClipMessage

    /** CLIP-03 E9 (field 7). */
    data object ImageNoSpace : ClipMessage

    /** CLIP-01 E10 on the manual path: the peer answered `FEATURE_DISABLED`. */
    data class FeatureDisabled(
        val deviceName: String,
    ) : ClipMessage
}

/** Progress of an image over 1 MiB (CLIP-03 fields 2–4); `percent = null` removes it. */
data class TransferProgress(
    val transferId: String,
    val pairId: String,
    val deviceName: String,
    val sending: Boolean,
    val percent: Int?,
)

/**
 * What the clipboard feature shows the user: toasts in place, and the notifications of the `clipboard` channel
 * (sensitive content, conflict, image progress). Texts come from the catalog.
 */
interface ClipNotices {
    fun show(message: ClipMessage)

    /** QC3: "Sensitive Content Blocked" with "Send Anyway" (valid 120 s); a new one replaces the old one. */
    fun sensitiveBlocked()

    /** CLIP-01 field 12: "Clipboard Not Updated on <device>" with "Send Again" (valid 120 s). */
    fun conflict(
        deviceName: String,
        clipId: String,
    )

    fun progress(progress: TransferProgress)
}
