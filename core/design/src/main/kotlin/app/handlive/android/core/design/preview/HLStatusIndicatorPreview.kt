package app.handlive.android.core.design.preview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import app.handlive.android.core.design.component.HLConnectionStatus
import app.handlive.android.core.design.component.HLStatusIndicator
import app.handlive.android.core.design.component.HLStatusIndicatorVariant
import app.handlive.android.core.design.theme.HandLiveAppearance
import app.handlive.android.core.design.theme.HandLiveTheme

/** Every state the phone shows (StatusIndicator/README.md) and the pill; the device name is sample user content. */
@Composable
internal fun HLStatusIndicatorPreviewGallery() {
    Column(verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space8)) {
        HLConnectionStatus.entries.forEach { HLStatusIndicator(status = it, deviceName = SAMPLE_DEVICE_NAME) }
        HLStatusIndicator(
            status = HLConnectionStatus.ConnectedWiFi,
            deviceName = SAMPLE_DEVICE_NAME,
            variant = HLStatusIndicatorVariant.Pill,
        )
    }
}

@Preview(widthDp = 360)
@Composable
private fun HLStatusIndicatorPreview(
    @PreviewParameter(HandLiveAppearancePreviewProvider::class) appearance: HandLiveAppearance,
) {
    HandLivePreviewSurface(appearance) { HLStatusIndicatorPreviewGallery() }
}
