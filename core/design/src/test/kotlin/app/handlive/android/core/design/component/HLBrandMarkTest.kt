package app.handlive.android.core.design.component

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.handlive.android.core.design.theme.HandLiveTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The welcome screen's brand mark: drawn by day and by night (its night drawable), decorative for TalkBack, and the
 * step's title stays a heading when the mark is the hero (Onboarding README).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HLBrandMarkTest {
    @get:Rule
    val compose = createComposeRule()

    private val decorative = SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription)

    @Test
    fun markIsDrawnAndDecorative() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) { HLBrandMark(Modifier.testTag("mark")) }
        }
        compose.onNodeWithTag("mark").assertExists().assert(decorative)
    }

    @Test
    @Config(qualifiers = "night")
    fun markIsDrawnAtNightAndDecorative() {
        compose.setContent {
            HandLiveTheme(darkTheme = true, highContrast = false) { HLBrandMark(Modifier.testTag("mark")) }
        }
        compose.onNodeWithTag("mark").assertExists().assert(decorative)
    }

    @Test
    fun stepWithTheMarkKeepsTheTitleAsHeading() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                HLStepScreen(title = "Welcome to HandLive", body = null, hero = { HLBrandMark() }) {}
            }
        }
        compose
            .onNodeWithText("Welcome to HandLive")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }
}
