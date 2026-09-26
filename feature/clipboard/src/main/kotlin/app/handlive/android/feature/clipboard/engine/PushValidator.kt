package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardValues

/** What this phone accepts from one peer right now (QC1, QC5): clipboard active for the pair, `mimes`, limits. */
class Acceptance(
    val active: Boolean,
    val mimes: Collection<String>,
    val maxTextBytes: Long = ClipLimits.MAX_TEXT_BYTES,
    val maxImageBytes: Long = ClipLimits.MAX_IMAGE_BYTES,
)

/** An error `ack` for a refused push (`details.status = rejected`); [message] is an English diagnostic. */
class Rejection(
    val code: ErrorCode,
    val message: String,
)

/**
 * Receiver checks of a `clipboard/push` in the order of CLIP-01 API 5 logic 5 and CLIP-03 API 3 rule 1: structure
 * (`BAD_REQUEST`), clipboard active (`FEATURE_DISABLED`), own limit (`CLIP_TOO_LARGE`), accepted MIME type
 * (`CLIP_UNSUPPORTED_MIME`).
 */
object PushValidator {
    fun check(
        push: ClipboardPushData,
        acceptance: Acceptance,
    ): Rejection? =
        (kindProblem(push) ?: contentProblem(push))?.let { Rejection(ErrorCode.BAD_REQUEST, it) }
            ?: when {
                !acceptance.active -> Rejection(ErrorCode.FEATURE_DISABLED, "Clipboard sync is off")

                size(
                    push,
                ) > limit(push, acceptance) -> Rejection(ErrorCode.CLIP_TOO_LARGE, "Clip exceeds the size limit")

                push.mime !in acceptance.mimes -> Rejection(ErrorCode.CLIP_UNSUPPORTED_MIME, "MIME type not accepted")

                else -> null
            }

    /** Bytes the clip carries: UTF-8 of inline text, or the transfer size. */
    fun size(push: ClipboardPushData): Long =
        push.text
            ?.toByteArray(Charsets.UTF_8)
            ?.size
            ?.toLong() ?: push.transfer?.size ?: 0

    /** A Kotlin string decoded from JSON may still hold a lone surrogate, which has no UTF-8 form. */
    fun isWellFormedText(text: String): Boolean = Charsets.UTF_8.newEncoder().canEncode(text)

    private fun kindProblem(push: ClipboardPushData): String? =
        when (push.kind) {
            ClipboardValues.KIND_TEXT -> {
                if (push.mime == ClipboardValues.MIME_TEXT) null else MISMATCH
            }

            ClipboardValues.KIND_IMAGE -> {
                if (push.mime.startsWith(IMAGE_PREFIX) &&
                    push.text == null
                ) {
                    null
                } else {
                    MISMATCH
                }
            }

            else -> {
                "unknown kind"
            }
        }

    private fun contentProblem(push: ClipboardPushData): String? =
        when {
            (push.text == null) == (push.transfer == null) -> "exactly one of text and transfer is required"
            push.text?.let(::isWellFormedText) == false -> "text is not valid UTF-8"
            push.transfer?.let(ChunkPlan::isWellFormed) == false -> "inconsistent transfer"
            else -> null
        }

    private fun limit(
        push: ClipboardPushData,
        acceptance: Acceptance,
    ): Long = if (push.kind == ClipboardValues.KIND_IMAGE) acceptance.maxImageBytes else acceptance.maxTextBytes

    private const val MISMATCH = "kind does not match mime"
    private const val IMAGE_PREFIX = "image/"
}
