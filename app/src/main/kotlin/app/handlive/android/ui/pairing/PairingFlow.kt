package app.handlive.android.ui.pairing

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.design.component.HLAlert
import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.exchange.PairingCoordinator
import app.handlive.android.feature.pairing.exchange.PairingState
import app.handlive.android.feature.pairing.scan.QrImageAnalyzer
import app.handlive.android.feature.pairing.scan.QrScanner
import app.handlive.android.ui.system.SystemPages
import kotlinx.coroutines.launch

/** Where the phone's part of PAIR-01 is before the coordinator takes over. */
private enum class PairingEntry { CHOOSE, CAMERA_PRIMER, SCANNING }

/**
 * PAIR-01 on screen: choose QR or PIN, camera primer and scanner (CameraX + ZXing), "Pair with <name>?",
 * "Pairing…", the PIN entry with attempts left, and the result. Leaving cancels the window (E5); the result
 * reports back through [onPaired].
 */
@Composable
fun PairingFlow(
    coordinator: PairingCoordinator,
    onPaired: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by coordinator.state.collectAsStateWithLifecycle()
    var entry by remember { mutableStateOf(PairingEntry.CHOOSE) }
    val close = {
        coordinator.cancel()
        onClose()
    }
    val camera =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) entry = PairingEntry.SCANNING else coordinator.onCameraDenied()
        }
    val startScan = {
        val granted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        entry = if (granted) PairingEntry.SCANNING else PairingEntry.CAMERA_PRIMER
    }
    val startPin: () -> Unit = { scope.launch { coordinator.startPin() } }
    DisposableEffect(Unit) { onDispose { coordinator.reset() } }
    BackHandler(onBack = close)
    when (val current = state) {
        PairingState.Idle, is PairingState.Confirm -> {
            IdleContent(entry, onScan = startScan, onPin = startPin, onPrimer = {
                camera.launch(Manifest.permission.CAMERA)
            }, onClose = close) {
                scope.launch { coordinator.onScanned(it) }
            }
            if (current is PairingState.Confirm) ConfirmAlert(current.clientName, coordinator::confirm, close)
        }

        is PairingState.Waiting, PairingState.Verifying -> {
            PairingProgressScreen(close)
        }

        is PairingState.EnterPin -> {
            PinEntryScreen(current.attemptsLeft, coordinator::submitPin, close)
        }

        is PairingState.Paired -> {
            PairedScreen(current.peerName, current.safetyCode) { onPaired(current.peerName) }
        }

        is PairingState.Failed -> {
            PairingFailedScreen(
                failure = current.failure,
                onRetry = {
                    coordinator.cancel()
                    entry = PairingEntry.CHOOSE
                },
                onUsePin = startPin,
                onClose = close,
                onOpenSettings = { SystemPages.open(context, SystemPages.appDetails(context)) },
            )
        }
    }
}

@Composable
private fun IdleContent(
    entry: PairingEntry,
    onScan: () -> Unit,
    onPin: () -> Unit,
    onPrimer: () -> Unit,
    onClose: () -> Unit,
    onCode: (String) -> Unit,
) {
    when (entry) {
        PairingEntry.CHOOSE -> {
            PairChoiceScreen(onScan = onScan, onPin = onPin, onCancel = onClose)
        }

        PairingEntry.CAMERA_PRIMER -> {
            CameraPrimer(onPrimer)
        }

        PairingEntry.SCANNING -> {
            val analyzer = remember { QrImageAnalyzer(onCode) }
            ScannerFrame(error = null, onCancel = onClose) { QrScanner(analyzer) }
        }
    }
}

/** Field 5: "Pair with <name>?" — "Pair" is the default, "Cancel" on the left. */
@Composable
private fun ConfirmAlert(
    clientName: String,
    onPair: () -> Unit,
    onCancel: () -> Unit,
) {
    HLAlert(
        title = stringResource(R.string.pairing_confirm_title, clientName),
        message = null,
        confirmLabel = stringResource(R.string.pairing_pair),
        onConfirm = onPair,
        dismissLabel = stringResource(R.string.common_cancel),
        onDismiss = onCancel,
    )
}
