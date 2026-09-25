package app.handlive.android.feature.clipboard.engine

import java.util.Locale

/** The event kinds `ClipboardAccessibilityService` receives (`res/xml/clipboard_accessibility.xml`). */
enum class SignalKind { VIEW_CLICKED, NOTIFICATION, ANNOUNCEMENT, WINDOW_STATE, OTHER }

/**
 * The parts of an `AccessibilityEvent` the matcher reads: package, class, the event's text and description.
 * [fromToast] is `true` for a `TYPE_NOTIFICATION_STATE_CHANGED` whose parcelable data is not a `Notification`.
 */
class CopySignal(
    val kind: SignalKind,
    val packageName: String?,
    val className: String?,
    val texts: List<String>,
    val fromToast: Boolean = false,
)

/**
 * CLIP-01 API 1 "recognized copy signals": a tap on a Copy/Cut item, a "copied" toast or announcement, the system
 * clipboard overlay of Android 13+. Matching happens in memory; event text is never stored, logged or sent
 * (logic 4). Recognition depends on apps and OEMs (E2), so the manual path always stays available.
 */
object CopySignalMatcher {
    private const val SYSTEM_UI = "com.android.systemui"
    private val COPY_ITEM_WORDS = listOf("copy", "sao chép")
    private val COPIED_WORDS = listOf("copied", "đã sao chép", "clipboard", "bảng nhớ tạm", "bộ nhớ đệm")

    /** [copyLabels]: `android.R.string.copy` and `android.R.string.cut` in the system language. */
    fun matches(
        signal: CopySignal,
        copyLabels: Collection<String>,
    ): Boolean =
        when (signal.kind) {
            SignalKind.VIEW_CLICKED -> signal.texts.any { isCopyItem(it, copyLabels) }
            SignalKind.NOTIFICATION -> signal.fromToast && signal.texts.any(::saysCopied)
            SignalKind.ANNOUNCEMENT -> signal.texts.any(::saysCopied)
            SignalKind.WINDOW_STATE -> signal.packageName == SYSTEM_UI && isClipboardOverlay(signal)
            SignalKind.OTHER -> false
        }

    private fun isCopyItem(
        text: String,
        copyLabels: Collection<String>,
    ): Boolean {
        val lower = text.trim().lowercase(Locale.ROOT)
        return copyLabels.any { it.trim().lowercase(Locale.ROOT) == lower } || COPY_ITEM_WORDS.any { it in lower }
    }

    private fun saysCopied(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        return COPIED_WORDS.any { it in lower }
    }

    /** The overlay's class names contain "clipboard" on AOSP; OEM builds are settled by device tests. */
    private fun isClipboardOverlay(signal: CopySignal): Boolean =
        signal.className?.lowercase(Locale.ROOT)?.contains("clipboard") == true || signal.texts.any(::saysCopied)
}
