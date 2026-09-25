package app.handlive.android.ui.onboarding

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.handlive.android.core.design.theme.HandLiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-01 fields 1–2: the privacy link and "Get Started", in both languages. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WelcomeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePrivacyLinkIsAButtonNextToGetStarted() {
        var privacy = 0
        var started = 0
        compose.setContent { HandLiveTheme { WelcomeScreen(onGetStarted = { started++ }, onPrivacy = { privacy++ }) } }
        compose
            .onNodeWithText("HandLive and Your Privacy")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        compose.onNodeWithText("Get Started").performClick()
        assertEquals(1, privacy)
        assertEquals(1, started)
    }

    @Test
    @Config(qualifiers = "vi")
    fun vietnameseTexts() {
        compose.setContent { HandLiveTheme { WelcomeScreen(onGetStarted = {}, onPrivacy = {}) } }
        compose.onNodeWithText("HandLive và quyền riêng tư của bạn").assertExists()
    }
}
