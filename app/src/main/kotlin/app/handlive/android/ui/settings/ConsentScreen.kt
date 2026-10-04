package app.handlive.android.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLButtonStyle
import app.handlive.android.core.design.component.HLIcon
import app.handlive.android.core.design.component.HLStepScreen
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R

/** The four statements of the disclosure (CLIP-01 field 2), each with its symbol. */
private val ConsentLines =
    listOf(
        HLSymbol.ContentCopy to R.string.clipboard_consent_purpose,
        HLSymbol.Accessibility to R.string.clipboard_consent_events,
        HLSymbol.Info to R.string.clipboard_consent_toast,
        HLSymbol.Settings to R.string.clipboard_consent_manual,
    )

/**
 * ConsentSheet for "Auto-Send on Copy" (CLIP-01 fields 2–3, SET-01 field 13), shown full screen: the feature's
 * name, the four statements, then "Agree" full width at the bottom and "Send Manually". Nothing is preselected.
 */
@Composable
fun ConsentScreen(
    onAgree: () -> Unit,
    onSendManually: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.Accessibility,
        title = stringResource(R.string.settings_auto_send),
        body = null,
        extra = {
            Column(verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space16)) {
                ConsentLines.forEach { (symbol, text) -> ConsentLine(symbol, text) }
            }
        },
    ) {
        HLButton(stringResource(R.string.common_agree), onAgree, Modifier.fillMaxWidth())
        HLButton(
            stringResource(R.string.clipboard_consent_send_manually),
            onSendManually,
            Modifier.fillMaxWidth(),
            style = HLButtonStyle.Plain,
        )
    }
}

@Composable
private fun ConsentLine(
    symbol: HLSymbol,
    text: Int,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space12),
        verticalAlignment = Alignment.Top,
    ) {
        HLIcon(symbol = symbol, contentDescription = null, tint = HandLiveTheme.colors.accent)
        BasicText(
            text = stringResource(text),
            style = HandLiveTheme.typography.body.copy(color = HandLiveTheme.colors.label),
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * SET-01 field 14 (Android 13+, not installed from Google Play): before the Accessibility settings open, or the
 * Notification access page with the [title] of its primer. Its single [button] is "Continue" to the page, or "Open
 * Settings" to App info when the user came back without turning HandLive on (Android may have blocked it).
 */
@Composable
fun RestrictedSettingScreen(
    @StringRes title: Int = R.string.settings_auto_send,
    @StringRes body: Int = R.string.setup_restricted_settings_help,
    @StringRes button: Int = R.string.common_continue,
    onContinue: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.Lock,
        title = stringResource(title),
        body = stringResource(body),
    ) {
        HLButton(stringResource(button), onContinue, Modifier.fillMaxWidth())
    }
}
