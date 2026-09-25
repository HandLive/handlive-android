package app.handlive.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.design.component.HLCheckRow
import app.handlive.android.core.design.component.HLGroupedList
import app.handlive.android.core.design.component.HLScreenHeader
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.settings.AppLanguage

/** SET-02 field 6 / CLIP-05 field 1: Off, After 1 Minute, After 5 Minutes, with the footnote of field 2. */
@Composable
fun AutoClearScreen(
    seconds: Int,
    onSelect: (Int) -> Unit,
    onBack: () -> Unit,
) {
    val footer = stringResource(R.string.settings_auto_clear_footer)
    ChoiceScreen(title = stringResource(R.string.settings_auto_clear), onBack = onBack) {
        HLGroupedList(modifier = Modifier.weight(1f)) {
            section(footer = footer) {
                HandLiveSettings.AUTO_CLEAR_CHOICES.forEach { choice ->
                    row {
                        HLCheckRow(
                            stringResource(autoClearLabel(choice)),
                            selected = seconds == choice,
                        ) { onSelect(choice) }
                    }
                }
            }
        }
    }
}

/** SET-02 field 32 on Android 10–12: System Default, English, Tiếng Việt, each named in its own language. */
@Composable
fun LanguageScreen(
    current: AppLanguage,
    onSelect: (AppLanguage) -> Unit,
    onBack: () -> Unit,
) {
    ChoiceScreen(title = stringResource(R.string.settings_language), onBack = onBack) {
        HLGroupedList(modifier = Modifier.weight(1f)) {
            section {
                AppLanguage.entries.forEach { language ->
                    row {
                        HLCheckRow(
                            stringResource(language.label),
                            selected = current == language,
                        ) { onSelect(language) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChoiceScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        HLScreenHeader(title = title, backLabel = stringResource(R.string.settings_title), onBack = onBack)
        content()
    }
}
