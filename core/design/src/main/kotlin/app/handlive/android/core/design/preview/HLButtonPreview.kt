package app.handlive.android.core.design.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLButtonStyle
import app.handlive.android.core.design.theme.HandLiveAppearance
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R

/** The five button styles with catalog labels. */
@Composable
internal fun HLButtonPreviewGallery() {
    Column(verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space12)) {
        HLButton(text = stringResource(R.string.pairing_pair), onClick = {}, modifier = Modifier.fillMaxWidth())
        HLButton(text = stringResource(R.string.clipboard_send), onClick = {}, style = HLButtonStyle.Glass)
        HLButton(text = stringResource(R.string.common_open_settings), onClick = {}, style = HLButtonStyle.Tinted)
        HLButton(text = stringResource(R.string.common_skip), onClick = {}, style = HLButtonStyle.Plain)
        HLButton(text = stringResource(R.string.pairing_unpair), onClick = {}, style = HLButtonStyle.Destructive)
        HLButton(text = stringResource(R.string.pairing_pair), onClick = {}, enabled = false)
    }
}

@Preview(widthDp = 360)
@Composable
private fun HLButtonPreview(
    @PreviewParameter(HandLiveAppearancePreviewProvider::class) appearance: HandLiveAppearance,
) {
    HandLivePreviewSurface(appearance) { HLButtonPreviewGallery() }
}
