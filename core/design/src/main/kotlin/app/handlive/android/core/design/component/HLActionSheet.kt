package app.handlive.android.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.handlive.android.core.design.theme.HandLiveTheme

/**
 * An action sheet as on the iPhone (PAIR-03 field 3, SET-02 field 28): the actions in a rounded group with the title
 * (a heading for TalkBack) and message above them, and "Cancel" in a separate group at the bottom. Back and a tap
 * outside cancel.
 */
@Composable
fun HLActionSheet(
    title: String?,
    message: String?,
    actions: List<HLSheetAction>,
    cancelLabel: String,
    onDismiss: () -> Unit,
) {
    val colors = HandLiveTheme.colors
    val shape = RoundedCornerShape(HandLiveTheme.radius.card)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(HandLiveTheme.spacing.space8),
                verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space8),
            ) {
                Column(modifier = Modifier.fillMaxWidth().clip(shape).background(colors.secondarySystemBackground)) {
                    if (title != null || message != null) SheetHeader(title, message)
                    actions.forEach { action ->
                        SheetButton(
                            label = action.label,
                            color = if (action.destructive) colors.destructiveText else colors.accent,
                            weight = FontWeight.Normal,
                            onClick = action.onClick,
                        )
                    }
                }
                Box(modifier = Modifier.fillMaxWidth().clip(shape).background(colors.secondarySystemBackground)) {
                    SheetButton(
                        label = cancelLabel,
                        color = colors.accent,
                        weight = FontWeight.SemiBold,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(
    title: String?,
    message: String?,
) {
    val colors = HandLiveTheme.colors
    val typography = HandLiveTheme.typography
    Column(
        modifier = Modifier.fillMaxWidth().padding(HandLiveTheme.spacing.space16),
        verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space4),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val centered = TextAlign.Center
        title?.let {
            BasicText(
                it,
                // TalkBack reads the question first, as the title of an alert (HLAlert).
                modifier = Modifier.semantics { heading() },
                style =
                    typography.footnote.copy(
                        color = colors.secondaryLabel,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = centered,
                    ),
            )
        }
        message?.let {
            BasicText(
                it,
                style = typography.footnote.copy(color = colors.secondaryLabel, textAlign = centered),
            )
        }
    }
}

@Composable
private fun SheetButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    weight: FontWeight,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = HandLiveTheme.sizes.hitAndroid)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(HandLiveTheme.spacing.space16),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = label,
            style =
                HandLiveTheme.typography.title3.copy(
                    color = color,
                    fontWeight = weight,
                    textAlign = TextAlign.Center,
                ),
        )
    }
}
