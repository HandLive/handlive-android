package app.handlive.android.core.design.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height a floating bar (the tab bar) covers at the bottom of the content, above the system navigation bar. The screen
 * that draws the bar provides it around its content; scrolling content ([HLGroupedList]) adds it to its bottom
 * padding, so the last row scrolls clear of the bar instead of ending under it (3-platforms/03-android.md: the content
 * is edge-to-edge and the tab bar floats over it). 0 where no bar floats over the content.
 */
val LocalHLFloatingBarInset = compositionLocalOf { 0.dp }

/**
 * What scrolling content keeps clear below its end: the system navigation bar (edge-to-edge) and any floating bar
 * ([LocalHLFloatingBarInset]). [HLGroupedList] uses it; a screen with its own scroll container adds it the same way.
 */
@Composable
fun hlContentBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + LocalHLFloatingBarInset.current
