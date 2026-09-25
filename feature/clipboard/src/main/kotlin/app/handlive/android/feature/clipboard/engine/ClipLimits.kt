package app.handlive.android.feature.clipboard.engine

/** Constants of 0.10 and of the clipboard group (04-clipboard, "New constants used in this group"). */
object ClipLimits {
    /** `CLIP_MAX_TEXT`: 1 MiB of UTF-8. */
    const val MAX_TEXT_BYTES = 1_048_576L

    /** `CLIP_MAX_IMAGE`: 10 MiB after normalization. */
    const val MAX_IMAGE_BYTES = 10_485_760L

    /** `CLIP_INLINE_MAX`: a push whose plaintext is larger sends its text in chunks (QC5). */
    const val INLINE_MAX_BYTES = 180 * 1024

    /** Images above 1 MiB show progress (CLIP-03 field 2). */
    const val PROGRESS_MIN_BYTES = 1_048_576L

    /** `CLIP_STALE_AFTER`: replay window (QC7), sensitive content and "Send Again" lifetime (QC3, CLIP-01 field 13). */
    const val STALE_AFTER_MILLIS = 120_000L

    /** `CLIP_LOOP_WINDOW` (QC4, CLIP-01 E9). */
    const val LOOP_WINDOW_MILLIS = 5_000L

    /** Accessibility copy signals are ignored for 1 s after HandLive writes the clipboard (QC4). */
    const val SIGNAL_IGNORE_MILLIS = 1_000L

    /** `CLIP_DETECT_DEBOUNCE` (CLIP-01 API 1 rule 3). */
    const val DETECT_DEBOUNCE_MILLIS = 300L

    /** `CLIP_TRANSFER_IDLE_TIMEOUT` (CLIP-03 E7). */
    const val TRANSFER_IDLE_MILLIS = 30_000L

    /** `ClipboardReadActivity` waits this long for window focus (CLIP-01 API 2 rule 1, E3). */
    const val FOCUS_WAIT_MILLIS = 1_000L

    /** Files of `cache/clip/` older than this are deleted when A-SVC starts (CLIP-03 API 6 rule 2). */
    const val FILE_MAX_AGE_MILLIS = 3_600_000L
}
