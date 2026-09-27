package app.handlive.android.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLStepScreen
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.strings.R

/**
 * SET-01 field 12 for calls: the PermissionPrimer before the system dialogs — why HandLive reads the phone state, the
 * call log and the contacts (and answers calls) — with one "Continue"; the user declines in the system dialog itself.
 */
@Composable
fun CallsPrimerScreen(onContinue: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.Smartphone,
        title = stringResource(R.string.settings_calls),
        body = stringResource(R.string.permission_calls_primer),
        footer = stringResource(R.string.permission_primer_footer),
    ) {
        HLButton(stringResource(R.string.common_continue), onContinue, Modifier.fillMaxWidth())
    }
}
