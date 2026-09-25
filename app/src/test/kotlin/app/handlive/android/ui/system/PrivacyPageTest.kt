package app.handlive.android.ui.system

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/** SET-01 field 1: "HandLive and Your Privacy" opens the page in the app's display language. */
class PrivacyPageTest {
    @Test
    fun vietnameseOpensTheVietnamesePage() {
        assertEquals(
            "https://github.com/HandLive/handlive/blob/main/docs/privacy.vi.md",
            PrivacyPage.url(Locale.forLanguageTag("vi")),
        )
        assertEquals(
            "https://github.com/HandLive/handlive/blob/main/docs/privacy.vi.md",
            PrivacyPage.url(Locale.forLanguageTag("vi-VN")),
        )
    }

    @Test
    fun everyOtherLanguageOpensTheEnglishPage() {
        listOf("en", "en-US", "en-XA", "fr").forEach { tag ->
            assertEquals(
                "https://github.com/HandLive/handlive/blob/main/docs/privacy.md",
                PrivacyPage.url(Locale.forLanguageTag(tag)),
            )
        }
    }
}
