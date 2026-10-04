package app.handlive.android.feature.clipboard.module

import java.io.File

/** Why a local read produced nothing to send (CLIP-01 E3, CLIP-03 E2, E3, E10). */
enum class ReadFailure {
    /** Empty clip, no focus within 1 s, or neither text nor image (`CLIP_UNSUPPORTED_MIME`, logged only). */
    EMPTY_OR_NOT_TEXT,

    /** The image source is over `CLIP_MAX_IMAGE` (read stops at the limit + 1 byte). */
    IMAGE_TOO_LARGE,

    /** The image stream could not be read or decoded. */
    IMAGE_UNREADABLE,

    /** The URI could not be read (grant lost or never given): silent on `auto`, told on `manual` (E10). */
    PERMISSION_LOST,
}

/** What `ClipboardReadActivity`, the in-app listener or the Share target read (CLIP-01 API 2, 4; CLIP-03 API 1). */
sealed interface LocalRead {
    /** `auto`, `manual` or `share` (CLIP-01 API 5 `source`). */
    val source: String

    class Text(
        val text: String,
        /** `EXTRA_IS_SENSITIVE` on the `ClipDescription` (QC3); always `false` for Share. */
        val sensitiveExtra: Boolean,
        override val source: String,
        /** Sanitized `ClipData.Item.htmlText` beside the text (CLIP-01 API 2 logic 2); `null` for plain text, Share. */
        val html: String? = null,
    ) : LocalRead

    /** An image copied into `cache/clip/` before the activity closed. */
    class Image(
        val file: File,
        val mime: String,
        val sensitiveExtra: Boolean,
        override val source: String,
    ) : LocalRead

    class Failed(
        val reason: ReadFailure,
        override val source: String,
    ) : LocalRead
}
