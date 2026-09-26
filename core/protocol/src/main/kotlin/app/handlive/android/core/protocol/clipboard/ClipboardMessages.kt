package app.handlive.android.core.protocol.clipboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `op` names of `type = clipboard` (0.7.1). */
object ClipboardOp {
    const val PUSH = "push"
    const val CHUNK = ClipboardChunkPlaintext.OP
    const val CANCEL = "cancel"
    const val CONFLICT = "conflict"
}

/** Values of `kind`, `mime`, `source` and the `ack` statuses of `clipboard/push` (CLIP-01 API 5). */
object ClipboardValues {
    const val KIND_TEXT = "text"
    const val KIND_IMAGE = "image"
    const val MIME_TEXT = "text/plain"
    const val MIME_PNG = "image/png"
    const val MIME_JPEG = "image/jpeg"
    const val SOURCE_AUTO = "auto"
    const val SOURCE_MANUAL = "manual"
    const val SOURCE_SHARE = "share"
    const val STATUS_APPLIED = "applied"
    const val STATUS_IGNORED = "ignored"
    const val STATUS_REJECTED = "rejected"
    const val REASON_CONFLICT = "conflict"
    const val REASON_DUPLICATE = "duplicate"
    const val REASON_CANCELLED = "cancelled"
    const val CANCEL_SUPERSEDED = "superseded"
    const val CANCEL_USER = "user"
    const val CANCEL_TIMEOUT = "timeout"
}

/**
 * `clipboard/push` (CLIP-01 API 5, CLIP-03 API 3): exactly one of [text] (inline) and [transfer] (chunks) is set;
 * [width]/[height] only for images. [originTs] is on the origin device's clock (QC8 b).
 */
@Serializable
data class ClipboardPushData(
    @SerialName("clip_id") val clipId: String,
    val kind: String,
    val mime: String,
    val text: String? = null,
    val transfer: ClipboardTransfer? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sensitive: Boolean,
    @SerialName("origin_ts") val originTs: Long,
    val source: String,
    @SerialName("origin_device_id") val originDeviceId: String,
)

/** `transfer` of a chunked clip (CLIP-03 API 3); `sha256` is b64u of 32 bytes. */
@Serializable
data class ClipboardTransfer(
    @SerialName("transfer_id") val transferId: String,
    val size: Long,
    val sha256: String,
    @SerialName("chunk_size") val chunkSize: Int,
    @SerialName("chunk_count") val chunkCount: Int,
)

/**
 * `ack.data` of a push (`applied`, `ignored` + reason) and `ack.error.details` of a refused one (`rejected`,
 * with `transfer_id` for `CLIP_CHECKSUM_MISMATCH`).
 */
@Serializable
data class ClipboardAckData(
    @SerialName("clip_id") val clipId: String,
    val status: String,
    val reason: String? = null,
    @SerialName("transfer_id") val transferId: String? = null,
)

/** `clipboard/cancel` (CLIP-03 API 5): `reason` ∈ {superseded, user, timeout}. */
@Serializable
data class ClipboardCancelData(
    @SerialName("transfer_id") val transferId: String,
    val reason: String,
)

/** `clipboard/conflict` (CLIP-01 API 6): the device that kept its own content, for the "Send Again" notification. */
@Serializable
data class ClipboardConflictData(
    @SerialName("clip_id") val clipId: String,
    @SerialName("origin_device_id") val originDeviceId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_name") val deviceName: String,
)
