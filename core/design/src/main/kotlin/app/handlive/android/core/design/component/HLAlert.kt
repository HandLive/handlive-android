package app.handlive.android.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.handlive.android.core.design.theme.HandLiveTheme

private val AlertMaxWidth = 320.dp

/**
 * An alert as on Apple platforms (Alert README): a specific title, an optional message that is a full sentence,
 * "Cancel" on the left and the action on the right — `destructive-text` when [destructive]. Not Material's
 * `AlertDialog`. Back and a tap outside cancel.
 */
@Composable
fun HLAlert(
    title: String,
    message: String?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    val colors = HandLiveTheme.colors
    val typography = HandLiveTheme.typography
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier =
                Modifier
                    .widthIn(max = AlertMaxWidth)
                    .clip(RoundedCornerShape(HandLiveTheme.radius.card))
                    .background(colors.secondarySystemBackground),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(HandLiveTheme.spacing.space20),
                verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space8),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    text = title,
                    style = typography.headline.copy(color = colors.label, textAlign = TextAlign.Center),
                    modifier = Modifier.semantics { heading() },
                )
                if (message != null) {
                    BasicText(
                        text = message,
                        style = typography.footnote.copy(color = colors.label, textAlign = TextAlign.Center),
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                AlertButton(dismissLabel, colors.accent, FontWeight.Normal, onDismiss, Modifier.weight(1f))
                AlertButton(
                    confirmLabel,
                    if (destructive) colors.destructiveText else colors.accent,
                    FontWeight.SemiBold,
                    onConfirm,
                    Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AlertButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    Box(
        modifier =
            modifier
                .heightIn(min = HandLiveTheme.sizes.hitAndroid)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(HandLiveTheme.spacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                HandLiveTheme.typography.body.copy(
                    color = color,
                    fontWeight = weight,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}
