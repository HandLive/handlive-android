package app.handlive.android.ui.devices

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.component.LocalHLFloatingBarInset
import app.handlive.android.ui.Scaled
import app.handlive.android.ui.main.StatusBanners
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Devices tab without a pair: with a status banner it renders inside the grouped list (it used to crash there,
 * a vertical scroll inside the list's lazy column), and alone in a short window its "Add Device" button scrolls
 * clear of the floating tab bar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w800dp-h360dp")
class DevicesEmptyStateTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun emptyStateUnderABannerShowsAddDevice() {
        compose.setContent {
            Scaled(fontScale = 1f) {
                DevicesScreen(emptyList(), StatusBanners(notificationsOff = true), onOpen = {}, onAdd = {})
            }
        }
        compose.onNodeWithText("Add Device").assertExists()
    }

    /** A short window (split screen, landscape with large text): the empty state has to scroll. */
    @Test
    @Config(qualifiers = "en-w800dp-h240dp")
    fun addDeviceScrollsClearOfTheTabBar() {
        val bar = 72.dp
        compose.setContent {
            Scaled(fontScale = 2f) {
                Box(Modifier.fillMaxSize().testTag("screen")) {
                    CompositionLocalProvider(LocalHLFloatingBarInset provides bar) {
                        DevicesScreen(emptyList(), StatusBanners(), onOpen = {}, onAdd = {})
                    }
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(bar)
                            .testTag("bar"),
                    )
                }
            }
        }
        repeat(SWIPES) { compose.onNodeWithTag("screen").performTouchInput { swipeUp() } }
        val button = compose.onNodeWithText("Add Device").fetchSemanticsNode().boundsInRoot
        val barTop =
            compose
                .onNodeWithTag("bar")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("Add Device ends at ${button.bottom}, the bar starts at $barTop", button.bottom <= barTop)
    }

    private companion object {
        const val SWIPES = 4
    }
}
