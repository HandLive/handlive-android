package app.handlive.android.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import app.handlive.android.core.design.theme.HandLiveTheme

/**
 * A label and its value on one line — the value at the end — while both fit; otherwise stacked, the value under
 * the label, as iOS does at the largest text sizes. Nothing is squeezed into a column of single letters or cut at
 * 200 % text size (03-android.md "Type and icons").
 */
@Composable
fun HLLabelValueLayout(
    label: @Composable () -> Unit,
    value: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val gap = HandLiveTheme.spacing.space12
    Layout(contents = listOf(label, value), modifier = modifier) { (labels, values), constraints ->
        val labelPart = labels.first()
        val valuePart = values.first()
        val spacing = gap.roundToPx()
        val labelWidth = labelPart.maxIntrinsicWidth(Constraints.Infinity)
        val valueWidth = valuePart.maxIntrinsicWidth(Constraints.Infinity)
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        if (labelWidth + spacing + valueWidth <= constraints.maxWidth) {
            val placedLabel = labelPart.measure(loose.copy(maxWidth = labelWidth))
            val placedValue = valuePart.measure(loose.copy(maxWidth = valueWidth))
            val height = maxOf(placedLabel.height, placedValue.height)
            layout(constraints.maxWidth, height) {
                placedLabel.place(0, (height - placedLabel.height) / 2)
                placedValue.place(constraints.maxWidth - placedValue.width, (height - placedValue.height) / 2)
            }
        } else {
            val placedLabel = labelPart.measure(loose)
            val placedValue = valuePart.measure(loose)
            layout(constraints.maxWidth, placedLabel.height + placedValue.height) {
                placedLabel.place(0, 0)
                placedValue.place(0, placedLabel.height)
            }
        }
    }
}
