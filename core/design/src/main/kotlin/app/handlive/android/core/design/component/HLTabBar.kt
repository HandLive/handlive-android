package app.handlive.android.core.design.component

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme

private val TabMinWidth = 96.dp
private val BarShadow = 8.dp

/**
 * The floating tab bar of 03-android.md: a `radius-capsule` capsule of `glass-fill` with a `glass-stroke` border and
 * a shadow; the selected tab shows the filled symbol in `accent`. API 29–30 and increased contrast use an opaque
 * background. Every tab is at least 48 dp and is read by TalkBack as a tab with its selected state.
 */
@Composable
fun HLTabBar(
    items: List<HLTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = HandLiveTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    val opaque = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || HandLiveTheme.appearance.isHighContrast
    Row(
        modifier =
            modifier
                .shadow(BarShadow, shape)
                .clip(shape)
                .background(if (opaque) colors.secondarySystemBackground else colors.glassFill)
                .border(1.dp, colors.glassStroke, shape)
                .padding(HandLiveTheme.spacing.space4)
                .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space4),
    ) {
        items.forEachIndexed { index, item ->
            val selected = index == selectedIndex
            val tint = if (selected) colors.accent else colors.secondaryLabel
            Column(
                modifier =
                    Modifier
                        .sizeIn(minWidth = TabMinWidth, minHeight = HandLiveTheme.sizes.hitAndroid)
                        .clip(shape)
                        .background(if (selected) colors.tertiarySystemFill else Color.Transparent)
                        .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(index) })
                        .padding(horizontal = HandLiveTheme.spacing.space16, vertical = HandLiveTheme.spacing.space4),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                HLIcon(symbol = item.symbol, contentDescription = null, tint = tint, filled = selected)
                BasicText(text = item.label, style = HandLiveTheme.typography.caption1.copy(color = tint))
            }
        }
    }
}
