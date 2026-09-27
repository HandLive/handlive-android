package app.handlive.android.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.handlive.android.core.design.component.HLActionSheet
import app.handlive.android.core.design.component.HLAlert
import app.handlive.android.core.design.component.HLSheetAction
import app.handlive.android.core.strings.R

/**
 * SET-02 fields 28–29 as an action sheet — the question as its title, the warning, the red button that names the
 * action, "Cancel" — and E7 as an alert with "Delete" and "Cancel" (design system Alert: SET-02 field 28).
 */
@Composable
fun DataActionDialogs(
    step: DataStep,
    onConfirm: () -> Unit,
    onDeleteAnyway: () -> Unit,
    onCancel: () -> Unit,
) {
    when (step) {
        is DataStep.Confirm -> {
            val remove = step.action == DataAction.REMOVE_FROM_SERVER
            val title =
                if (remove) R.string.settings_remove_from_server_title else R.string.settings_delete_all_data_title
            val warning =
                if (remove) R.string.settings_remove_from_server_warning else R.string.settings_delete_all_data_warning
            val confirm =
                if (remove) R.string.settings_remove_from_server_confirm else R.string.settings_delete_all_confirm
            HLActionSheet(
                title = stringResource(title),
                message = stringResource(warning),
                actions = listOf(HLSheetAction(stringResource(confirm), destructive = true, onClick = onConfirm)),
                cancelLabel = stringResource(R.string.common_cancel),
                onDismiss = onCancel,
            )
        }

        DataStep.DeleteOffline -> {
            HLAlert(
                title = stringResource(R.string.settings_delete_all_offline_confirm),
                message = null,
                confirmLabel = stringResource(R.string.common_delete),
                onConfirm = onDeleteAnyway,
                dismissLabel = stringResource(R.string.common_cancel),
                onDismiss = onCancel,
                destructive = true,
            )
        }

        DataStep.Idle, DataStep.Working -> {
            Unit
        }
    }
}
