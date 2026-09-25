package app.handlive.android.core.design.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import app.handlive.android.core.design.theme.HandLiveTheme

/**
 * The top of a screen (03-android.md "Navigation" and "Large title"): on a subscreen a `chevron_left` with the
 * previous screen's name, then the large title (`android-large-title`, a heading for TalkBack). The system back
 * gesture works as well.
 */
@Composable
fun HLScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    backLabel: String? = null,
    onBack: (() -> Unit)? = null,
) {
    val colors = HandLiveTheme.colors
    val spacing = HandLiveTheme.spacing
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = spacing.marginCompact)) {
        if (onBack != null && backLabel != null) {
            Row(
                modifier =
                    Modifier
                        .heightIn(min = HandLiveTheme.sizes.hitAndroid)
                        .clickable(role = Role.Button, onClick = onBack)
                        .padding(end = spacing.space8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.space4),
            ) {
                HLIcon(symbol = HLSymbol.ChevronLeft, contentDescription = null, tint = colors.accent)
                BasicText(text = backLabel, style = HandLiveTheme.typography.body.copy(color = colors.accent))
            }
        }
        BasicText(
            text = title,
            style = HandLiveTheme.typography.largeTitle.copy(color = colors.label),
            modifier = Modifier.padding(top = spacing.space8, bottom = spacing.space8).semantics { heading() },
        )
    }
}
