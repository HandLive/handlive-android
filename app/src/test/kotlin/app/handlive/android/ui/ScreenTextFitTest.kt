package app.handlive.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A1.4 acceptance: at 200 % text size no text of any screen is cut, in English and in Vietnamese. Only a line the
 * design truncates on purpose (a device name in its row: one line with "…") may overflow.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w411dp-h2000dp")
class ScreenTextFitTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun englishFitsAtTwoHundredPercent() = checkAllScreens()

    @Test
    @Config(qualifiers = "vi-w411dp-h2000dp")
    fun vietnameseFitsAtTwoHundredPercent() = checkAllScreens()

    @Test
    @Config(qualifiers = "vi-w320dp-h2000dp")
    fun vietnameseFitsOnASmallPhone() = checkAllScreens()

    private fun checkAllScreens() {
        var current by mutableStateOf(ScreenCatalog.screens.keys.first())
        compose.setContent { Scaled(fontScale = 2f) { key(current) { ScreenCatalog.screens.getValue(current)() } } }
        ScreenCatalog.screens.keys.forEach { name ->
            compose.runOnUiThread { current = name }
            compose.waitForIdle()
            assertTextFits(name)
        }
    }

    private fun assertTextFits(screen: String) {
        val nodes =
            compose
                .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
                .fetchSemanticsNodes()
        assertTrue("$screen shows no text", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.forEach { layout ->
                val input = layout.layoutInput
                val truncatedByDesign = input.maxLines == 1 && input.overflow == TextOverflow.Ellipsis
                if (!truncatedByDesign) {
                    assertFalse("$screen: \"${input.text}\" is cut at 200 % (${describe(layout)})", isCut(layout))
                }
            }
        }
    }

    /**
     * Cut means: lines taller than the text's box, a line wider than it, or an ellipsis. Width is checked per line,
     * because centred text is laid out across the full constraint and `didOverflowWidth` would report it.
     */
    private fun isCut(layout: TextLayoutResult): Boolean {
        val widest = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) - layout.getLineLeft(it) } ?: 0f
        val ellipsized = (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
        return layout.didOverflowHeight || widest > layout.size.width + WIDTH_TOLERANCE || ellipsized
    }

    private fun describe(layout: TextLayoutResult) =
        "size ${layout.size}, paragraph ${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
            "lines ${layout.lineCount}"

    private companion object {
        const val WIDTH_TOLERANCE = 1f
    }
}
