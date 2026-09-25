package app.handlive.android.feature.clipboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLIP-01 API 1 "recognized copy signals", including the spec example (Chrome, Vietnamese, "Sao chép"). */
class CopySignalMatcherTest {
    private val english = listOf("Copy", "Cut")
    private val vietnamese = listOf("Sao chép", "Cắt")

    private fun click(
        vararg texts: String,
        labels: List<String> = english,
    ) = CopySignalMatcher.matches(
        CopySignal(SignalKind.VIEW_CLICKED, "com.android.chrome", "android.widget.Button", texts.toList()),
        labels,
    )

    @Test
    fun tappingCopyOrCutMatchesInTheSystemLanguage() {
        assertTrue(click("Sao chép", labels = vietnamese))
        assertTrue(click("Cắt", labels = vietnamese))
        assertTrue(click("Copy"))
        assertTrue(click("Copy link"))
        assertFalse(click("Share"))
        assertFalse(click("Dán", labels = vietnamese))
    }

    @Test
    fun copiedToastsAndAnnouncementsMatch() {
        val toast =
            CopySignal(
                SignalKind.NOTIFICATION,
                "com.example.notes",
                null,
                listOf("Đã sao chép vào bảng nhớ tạm"),
                fromToast = true,
            )
        assertTrue(CopySignalMatcher.matches(toast, english))
        val notification =
            CopySignal(SignalKind.NOTIFICATION, "com.example.chat", null, listOf("Text copied"), fromToast = false)
        assertFalse(CopySignalMatcher.matches(notification, english))
        val announcement = CopySignal(SignalKind.ANNOUNCEMENT, "com.example.notes", null, listOf("Copied to clipboard"))
        assertTrue(CopySignalMatcher.matches(announcement, english))
    }

    @Test
    fun theSystemClipboardOverlayMatchesOnlyFromSystemUi() {
        val overlay = "com.android.systemui.clipboardoverlay.ClipboardOverlayWindow"
        assertTrue(
            CopySignalMatcher.matches(
                CopySignal(SignalKind.WINDOW_STATE, "com.android.systemui", overlay, emptyList()),
                english,
            ),
        )
        assertFalse(
            CopySignalMatcher.matches(
                CopySignal(SignalKind.WINDOW_STATE, "com.example", overlay, emptyList()),
                english,
            ),
        )
        val shade =
            CopySignal(
                SignalKind.WINDOW_STATE,
                "com.android.systemui",
                "android.widget.FrameLayout",
                listOf("Notification shade"),
            )
        assertFalse(CopySignalMatcher.matches(shade, english))
        assertFalse(
            CopySignalMatcher.matches(
                CopySignal(SignalKind.OTHER, "com.android.systemui", overlay, emptyList()),
                english,
            ),
        )
    }
}
