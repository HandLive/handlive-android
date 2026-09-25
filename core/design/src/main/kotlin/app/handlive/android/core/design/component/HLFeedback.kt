package app.handlive.android.core.design.component

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.theme.HandLiveTheme
import kotlinx.coroutines.delay

/** What the HUD shows: a short result ("Paired", "Unpaired") and its symbol. */
class HLFeedback(
    val message: String,
    val symbol: HLSymbol = HLSymbol.CheckCircle,
)

/** Holds the current [HLFeedback]; a new one replaces the one on screen. */
@Stable
class HLFeedbackState {
    var current by mutableStateOf<HLFeedback?>(null)
        private set

    fun show(feedback: HLFeedback) {
        current = feedback
    }

    internal fun dismiss(feedback: HLFeedback) {
        if (current === feedback) current = null
    }
}

private const val HUD_MILLIS = 1_500L
private val HudShadow = 8.dp

/**
 * The `Feedback` HUD of 03-android.md: glass at the top of the screen for about 1.5 s with a confirmation haptic,
 * announced by TalkBack as a polite live region. Without animations it appears and disappears at once.
 */
@Composable
fun HLFeedbackHost(
    state: HLFeedbackState,
    modifier: Modifier = Modifier,
) {
    val feedback = state.current
    val view = LocalView.current
    LaunchedEffect(feedback) {
        if (feedback != null) {
            val haptic =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.CONTEXT_CLICK
                }
            view.performHapticFeedback(haptic)
            delay(HUD_MILLIS)
            state.dismiss(feedback)
        }
    }
    val animate = rememberAnimationsEnabled()
    AnimatedVisibility(
        visible = feedback != null,
        modifier = modifier.statusBarsPadding(),
        enter = if (animate) fadeIn() else fadeIn(snapSpec()),
        exit = if (animate) fadeOut() else fadeOut(snapSpec()),
    ) {
        feedback?.let { HudCapsule(it) }
    }
}

@Composable
private fun HudCapsule(feedback: HLFeedback) {
    val colors = HandLiveTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier =
            Modifier
                .padding(top = HandLiveTheme.spacing.space8)
                .shadow(HudShadow, shape)
                .clip(shape)
                .background(
                    if (HandLiveTheme.appearance.isHighContrast) colors.secondarySystemBackground else colors.glassFill,
                ).border(1.dp, colors.glassStroke, shape)
                .padding(horizontal = HandLiveTheme.spacing.space20, vertical = HandLiveTheme.spacing.space12)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HLIcon(symbol = feedback.symbol, contentDescription = null, tint = colors.accent, filled = true)
        BasicText(text = feedback.message, style = HandLiveTheme.typography.headline.copy(color = colors.label))
    }
}

private fun snapSpec() =
    androidx.compose.animation.core
        .snap<Float>()
