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
 * SET-01 field 19 (CALL-05): the explanation before the system Notification access page — HandLive reads only call
 * notifications of calling apps, the rest never leaves the phone — with one "Continue" that opens that page; the user
 * turns HandLive on there by hand (step N2).
 */
@Composable
fun NotificationAccessPrimerScreen(onContinue: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.Notifications,
        title = stringResource(R.string.permission_notification_access_title),
        body = stringResource(R.string.permission_notification_access_body),
        footer = stringResource(R.string.permission_primer_footer),
    ) {
        HLButton(stringResource(R.string.common_continue), onContinue, Modifier.fillMaxWidth())
    }
}
