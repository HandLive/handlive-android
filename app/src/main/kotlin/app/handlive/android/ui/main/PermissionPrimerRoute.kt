package app.handlive.android.ui.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import app.handlive.android.feature.connection.capability.AndroidPermissions
import app.handlive.android.ui.system.FeatureAccessReader
import kotlinx.coroutines.launch

/**
 * SET-01 steps 10, 11 and 14 for one feature (SMS, calls): its [primer], then one `RequestMultiplePermissions` for
 * the feature's [permissions] still missing, remembered in `perm.requested`; afterwards the capability is computed
 * again, so connected clients get `capability/update` when something changed. Nothing missing → nothing to ask.
 */
@Composable
fun PermissionPrimerRoute(
    main: MainContext,
    permissions: List<String>,
    primer: @Composable (onContinue: () -> Unit) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requested = rememberSettings(main).permissionsRequested
    val missing = remember { FeatureAccessReader.read(context, permissions, requested).missing.toList() }
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            main.dependencies.runtime.refreshEnvironment()
            main.pop()
        }
    LaunchedEffect(missing) { if (missing.isEmpty()) main.pop() }
    primer {
        scope.launch {
            main.dependencies.data.settings
                .addRequestedPermissions(missing)
        }
        launcher.launch(missing.map(AndroidPermissions::fullName).toTypedArray())
    }
}
