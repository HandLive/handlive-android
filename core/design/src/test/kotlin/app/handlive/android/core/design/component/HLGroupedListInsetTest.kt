package app.handlive.android.core.design.component

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
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A grouped list under a floating bar (the tab bar of the main screen): scrolled to the end, its last row ends above
 * the bar instead of under it, out of reach.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HLGroupedListInsetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun lastRowScrollsClearOfAFloatingBar() {
        val bar = 72.dp
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                Box(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalHLFloatingBarInset provides bar) {
                        HLGroupedList(Modifier.fillMaxSize().testTag("list")) {
                            repeat(SECTIONS) { index ->
                                section(title = "Section $index") {
                                    actionRow(title = "Row $index", onClick = {})
                                }
                            }
                        }
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
        compose.onNodeWithTag("list").performScrollToIndex(SECTIONS - 1)
        val lastRow = compose.onNodeWithText("Row ${SECTIONS - 1}").fetchSemanticsNode().boundsInRoot
        val barTop =
            compose
                .onNodeWithTag("bar")
                .fetchSemanticsNode()
                .boundsInRoot.top
        assertTrue("last row ends at ${lastRow.bottom}, the bar starts at $barTop", lastRow.bottom <= barTop)
    }

    private companion object {
        const val SECTIONS = 12
    }
}
