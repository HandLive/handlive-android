package app.handlive.android.core.design.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import app.handlive.android.core.design.theme.HandLiveAppearance
import app.handlive.android.core.design.theme.HandLiveColors
import app.handlive.android.core.design.theme.HandLiveTheme

/** The four appearances for `@PreviewParameter`; the names label previews in Android Studio only. */
class HandLiveAppearancePreviewProvider : PreviewParameterProvider<HandLiveAppearance> {
    override val values: Sequence<HandLiveAppearance> = HandLiveAppearance.entries.asSequence()

    override fun getDisplayName(index: Int): String = appearanceDisplayNames[index]
}

/** Developer-facing preview labels (tokens.json `color.themes[].id`), not user interface text. */
private val appearanceDisplayNames = listOf("light", "dark", "light-hc", "dark-hc")

/** Sample user content (a device name is never translated), used by the previews. */
internal const val SAMPLE_DEVICE_NAME = "Pixel 8"

/**
 * Khung preview: ép giao diện (preview không đổi được mức tương phản hệ thống) và tô nền theo token,
 * để màu có alpha được xem trên đúng nền của nó.
 */
@Composable
internal fun HandLivePreviewSurface(
    appearance: HandLiveAppearance,
    background: (HandLiveColors) -> Color = { it.systemBackground },
    content: @Composable () -> Unit,
) {
    HandLiveTheme(darkTheme = appearance.isDark, highContrast = appearance.isHighContrast) {
        Box(modifier = Modifier.background(background(HandLiveTheme.colors)).padding(HandLiveTheme.spacing.space16)) {
            content()
        }
    }
}
