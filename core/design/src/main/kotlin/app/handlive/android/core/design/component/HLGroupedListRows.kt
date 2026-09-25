package app.handlive.android.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme

/** Cao tối thiểu của dòng danh sách nhóm trên Android (GroupedList/README.md, Toggle/README.md). */
private val RowMinHeight = 56.dp

/** Khung dòng: cao ≥ 56 dp, lề `space-16`, nội dung căn giữa theo chiều dọc; chữ được xuống dòng, không cắt. */
@Composable
fun HLGroupedRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = HandLiveTheme.spacing.space16, vertical = HandLiveTheme.spacing.space8),
        horizontalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space12),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
fun HLNavigationRow(
    title: String,
    value: String?,
    onClick: () -> Unit,
) {
    val colors = HandLiveTheme.colors
    val typography = HandLiveTheme.typography
    HLGroupedRow(modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        if (value == null) {
            BasicText(text = title, style = typography.body.copy(color = colors.label), modifier = Modifier.weight(1f))
        } else {
            HLLabelValueLayout(
                label = { BasicText(text = title, style = typography.body.copy(color = colors.label)) },
                value = { BasicText(text = value, style = typography.body.copy(color = colors.secondaryLabel)) },
                modifier = Modifier.weight(1f),
            )
        }
        ChevronForward()
    }
}

@Composable
fun HLSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    unavailableReason: String?,
    description: String? = null,
) {
    val colors = HandLiveTheme.colors
    val typography = HandLiveTheme.typography
    val enabled = unavailableReason == null
    val stateText = switchStateDescription(checked)
    HLGroupedRow(
        modifier =
            Modifier
                .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                .semantics { stateDescription = stateText },
    ) {
        Column(modifier = Modifier.weight(1f)) {
            BasicText(text = title, style = typography.body.copy(color = colors.label))
            if (description != null) {
                BasicText(text = description, style = typography.subheadline.copy(color = colors.secondaryLabel))
            }
            if (unavailableReason != null) {
                BasicText(text = unavailableReason, style = typography.subheadline.copy(color = colors.textOrange))
            }
        }
        HLSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** Dòng chỉ đọc: nhãn bên trái, giá trị `secondary-label` bên phải; TalkBack đọc cả dòng một lần. */
@Composable
fun HLValueRow(
    title: String,
    value: String,
) {
    val colors = HandLiveTheme.colors
    val typography = HandLiveTheme.typography
    HLGroupedRow(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        HLLabelValueLayout(
            label = { BasicText(text = title, style = typography.body.copy(color = colors.label)) },
            value = { BasicText(text = value, style = typography.body.copy(color = colors.secondaryLabel)) },
            modifier = Modifier.weight(1f),
        )
    }
}

/** Dòng chọn một trong nhiều giá trị kiểu iOS: dấu kiểm `accent` ở dòng đang chọn; vai trò nút chọn cho TalkBack. */
@Composable
fun HLCheckRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = HandLiveTheme.colors
    HLGroupedRow(modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)) {
        BasicText(
            text = title,
            style = HandLiveTheme.typography.body.copy(color = colors.label),
            modifier = Modifier.weight(1f),
        )
        if (selected) HLIcon(symbol = HLSymbol.Check, contentDescription = null, tint = colors.accent)
    }
}

@Composable
fun HLActionRow(
    title: String,
    destructive: Boolean,
    onClick: () -> Unit,
) {
    val colors = HandLiveTheme.colors
    HLGroupedRow(modifier = Modifier.clickable(role = Role.Button, onClick = onClick)) {
        BasicText(
            text = title,
            style =
                HandLiveTheme.typography.body.copy(
                    color = if (destructive) colors.destructiveText else colors.accent,
                ),
        )
    }
}

/**
 * Mũi tên `chevron_right` vẽ tay (Material Symbols chưa đóng gói ở Phase 0), màu `tertiary-label`.
 * Chỉ để trang trí: không có semantics, TalkBack đọc vai trò Nút của dòng.
 */
@Composable
private fun ChevronForward() {
    val color = HandLiveTheme.colors.tertiaryLabel
    Canvas(modifier = Modifier.size(width = 8.dp, height = 14.dp)) {
        val stroke = 2.dp.toPx()
        val path =
            Path().apply {
                moveTo(stroke / 2, stroke / 2)
                lineTo(size.width - stroke / 2, size.height / 2)
                lineTo(stroke / 2, size.height - stroke / 2)
            }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
