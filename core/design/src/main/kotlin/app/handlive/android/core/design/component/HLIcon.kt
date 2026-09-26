package app.handlive.android.core.design.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme

/** Material Symbols draw on a 24 dp grid (optical size 24). */
private val SYMBOL_SIZE = 24.dp

/**
 * A Material Symbols Rounded glyph tinted with a token colour (`label` by default). Pass [contentDescription] only
 * when the icon carries meaning of its own; an icon next to text that says the same thing stays silent (`null`).
 * The touch target is the caller's job (≥ 48 dp, `size-hit-android`).
 */
@Composable
fun HLIcon(
    symbol: HLSymbol,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = HandLiveTheme.colors.label,
    filled: Boolean = false,
    size: Dp = SYMBOL_SIZE,
) {
    Image(
        painter = painterResource(if (filled) symbol.filled else symbol.outline),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
}
