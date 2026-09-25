package app.handlive.android.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme

private val IconCircle = 96.dp
private val IconSize = 48.dp

/**
 * A full-screen step of a flow (PermissionPrimer README and Onboarding): a large symbol in an `accent-tint` circle,
 * a bold title (a heading for TalkBack), one or two sentences, small print, and the buttons at the bottom. The text
 * scrolls, so nothing is cut at 200 % text size. For a permission primer [actions] holds exactly one "Continue".
 */
@Composable
fun HLStepScreen(
    symbol: HLSymbol,
    title: String,
    body: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    extra: @Composable () -> Unit = {},
    actions: @Composable () -> Unit,
) {
    val colors = HandLiveTheme.colors
    val spacing = HandLiveTheme.spacing
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(colors.systemBackground)
                .safeDrawingPadding()
                .padding(horizontal = spacing.space24, vertical = spacing.space16),
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(spacing.space16),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            StepHeading(symbol, title)
            body?.let {
                BasicText(
                    text = it,
                    style = HandLiveTheme.typography.body.copy(color = colors.label, textAlign = TextAlign.Center),
                )
            }
            extra()
            footer?.let {
                BasicText(
                    text = it,
                    style =
                        HandLiveTheme.typography.footnote.copy(
                            color = colors.secondaryLabel,
                            textAlign = TextAlign.Center,
                        ),
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = spacing.space16),
            verticalArrangement = Arrangement.spacedBy(spacing.space8),
        ) {
            actions()
        }
    }
}

@Composable
private fun StepHeading(
    symbol: HLSymbol,
    title: String,
) {
    val colors = HandLiveTheme.colors
    Box(
        modifier =
            Modifier
                .padding(top = HandLiveTheme.spacing.space40)
                .size(IconCircle)
                .clip(CircleShape)
                .background(colors.accentTint),
        contentAlignment = Alignment.Center,
    ) {
        HLIcon(symbol = symbol, contentDescription = null, tint = colors.accent, size = IconSize)
    }
    BasicText(
        text = title,
        style =
            HandLiveTheme.typography.title2.copy(
                color = colors.label,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
        modifier = Modifier.semantics { heading() },
    )
}
