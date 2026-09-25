package app.handlive.android.ui.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.handlive.android.core.design.component.HLButton
import app.handlive.android.core.design.component.HLButtonStyle
import app.handlive.android.core.design.component.HLStepScreen
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.feature.pairing.exchange.PairingFailure

/** PAIR-01 on the phone, step 3: "Pair a Device" with "Scan QR Code" and "Enter PIN" (A3). */
@Composable
fun PairChoiceScreen(
    onScan: () -> Unit,
    onPin: () -> Unit,
    onCancel: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.QrCodeScanner,
        title = stringResource(R.string.pairing_pair_a_device),
        body = stringResource(R.string.pairing_scan_hint),
    ) {
        HLButton(stringResource(R.string.pairing_scan_qr), onScan, Modifier.fillMaxWidth())
        HLButton(
            stringResource(R.string.pairing_enter_pin),
            onPin,
            Modifier.fillMaxWidth(),
            style = HLButtonStyle.Glass,
        )
        HLButton(stringResource(R.string.common_cancel), onCancel, Modifier.fillMaxWidth(), style = HLButtonStyle.Plain)
    }
}

/** SET-01 part B for QR scanning: the PermissionPrimer before the camera dialog — one "Continue". */
@Composable
fun CameraPrimer(onContinue: () -> Unit) {
    HLStepScreen(
        symbol = HLSymbol.QrCodeScanner,
        title = stringResource(R.string.permission_camera_qr_primer_title),
        body = stringResource(R.string.permission_camera_qr_primer),
        footer = stringResource(R.string.permission_primer_footer),
    ) {
        HLButton(stringResource(R.string.common_continue), onContinue, Modifier.fillMaxWidth())
    }
}

/** Field 8 `connecting` / `verifying`: "Pairing…" with Cancel (E5). */
@Composable
fun PairingProgressScreen(onCancel: () -> Unit) {
    HLStepScreen(symbol = HLSymbol.Devices, title = stringResource(R.string.pairing_in_progress), body = null) {
        HLButton(stringResource(R.string.common_cancel), onCancel, Modifier.fillMaxWidth(), style = HLButtonStyle.Glass)
    }
}

/** Field 8 `done`: "Paired with <name>" and the Security Code both devices show (PAIR-02 field 10). */
@Composable
fun PairedScreen(
    peerName: String,
    securityCode: String,
    onDone: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.CheckCircle,
        title = stringResource(R.string.pairing_paired_with, peerName),
        body = null,
        extra = {
            BasicText(
                text = stringResource(R.string.pairing_security_code),
                style = HandLiveTheme.typography.footnote.copy(color = HandLiveTheme.colors.secondaryLabel),
            )
            BasicText(
                text =
                    app.handlive.android.ui.devices.SecurityCode
                        .grouped(securityCode),
                style = HandLiveTheme.typography.codePin.copy(color = HandLiveTheme.colors.label),
            )
        },
    ) {
        HLButton(stringResource(R.string.common_done), onDone, Modifier.fillMaxWidth())
    }
}

/** Field 10: the error of E1–E9, with a way on — the PIN when the camera is unavailable (E9). */
@Composable
fun PairingFailedScreen(
    failure: PairingFailure,
    onRetry: () -> Unit,
    onUsePin: () -> Unit,
    onClose: () -> Unit,
) {
    HLStepScreen(
        symbol = HLSymbol.Warning,
        title = stringResource(R.string.pairing_pair_a_device),
        body = stringResource(failure.message),
    ) {
        if (failure == PairingFailure.CAMERA_DENIED) {
            HLButton(stringResource(R.string.pairing_enter_pin), onUsePin, Modifier.fillMaxWidth())
        } else if (failure != PairingFailure.LIMIT_REACHED) {
            HLButton(stringResource(R.string.common_retry), onRetry, Modifier.fillMaxWidth())
        }
        HLButton(stringResource(R.string.common_cancel), onClose, Modifier.fillMaxWidth(), style = HLButtonStyle.Plain)
    }
}

/** Texts of PAIR-01 E1–E9 (catalog `error.*`, `pairing.*`). */
val PairingFailure.message: Int
    get() =
        when (this) {
            PairingFailure.QR_INVALID -> R.string.error_qr_invalid

            PairingFailure.AUTH_FAILED -> R.string.error_pairing_auth_failed

            PairingFailure.PIN_INVALID -> R.string.error_pin_invalid

            PairingFailure.LIMIT_REACHED -> R.string.pairing_limit_reached

            PairingFailure.CAMERA_DENIED -> R.string.pairing_camera_denied

            // A closed window, a client that left or an internal error all need a fresh code on the Mac.
            PairingFailure.PAIRING_CLOSED,
            PairingFailure.DISCONNECTED,
            PairingFailure.INTERNAL,
            -> R.string.error_pairing_closed
        }

/** PAIR-01 A3–A5: the 6-digit PIN, submitted when complete; attempts left after a wrong one (field 7). */
@Composable
fun PinEntryScreen(
    attemptsLeft: Int?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var pin by remember { mutableStateOf("") }
    val label = stringResource(R.string.pairing_enter_pin)
    HLStepScreen(
        symbol = HLSymbol.Pin,
        title = label,
        body = stringResource(R.string.pairing_pin_entry_hint),
        extra = {
            PinField(pin, label) { typed ->
                pin = typed
                if (typed.length == PIN_LENGTH) {
                    onSubmit(typed)
                    pin = ""
                }
            }
            attemptsLeft?.let {
                val warning =
                    HandLiveTheme.typography.subheadline.copy(
                        color = HandLiveTheme.colors.textOrange,
                        textAlign = TextAlign.Center,
                    )
                BasicText(text = stringResource(R.string.error_pin_invalid), style = warning)
                BasicText(text = pluralStringResource(R.plurals.pairing_pin_attempts_left, it, it), style = warning)
            }
        },
    ) {
        HLButton(stringResource(R.string.common_cancel), onCancel, Modifier.fillMaxWidth(), style = HLButtonStyle.Plain)
    }
}

@Composable
private fun PinField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
) {
    val colors = HandLiveTheme.colors
    Box(
        modifier =
            Modifier
                .border(1.dp, colors.separator, RoundedCornerShape(HandLiveTheme.radius.card))
                .background(colors.secondarySystemBackground, RoundedCornerShape(HandLiveTheme.radius.card))
                .padding(horizontal = HandLiveTheme.spacing.space24, vertical = HandLiveTheme.spacing.space12),
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = { typed -> onChange(typed.filter(Char::isDigit).take(PIN_LENGTH)) },
            textStyle = HandLiveTheme.typography.codePin.copy(color = colors.label, textAlign = TextAlign.Center),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            cursorBrush = SolidColor(colors.accent),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

private const val PIN_LENGTH = 6

/** The full-screen scanner (field 4): camera preview, the hint, the last error (E1) and Cancel. */
@Composable
fun ScannerFrame(
    error: String?,
    onCancel: () -> Unit,
    preview: @Composable () -> Unit,
) {
    val colors = HandLiveTheme.colors
    Box(modifier = Modifier.fillMaxSize().background(colors.videoBackground)) {
        preview()
        BasicText(
            text = stringResource(R.string.pairing_scan_qr),
            style = HandLiveTheme.typography.headline.copy(color = colors.onVideo, textAlign = TextAlign.Center),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(HandLiveTheme.spacing.space16)
                    .semantics { heading() },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(
                        Alignment.BottomCenter,
                    ).safeDrawingPadding()
                    .padding(HandLiveTheme.spacing.space16),
            verticalArrangement = Arrangement.spacedBy(HandLiveTheme.spacing.space12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = error ?: stringResource(R.string.pairing_scan_hint),
                style = HandLiveTheme.typography.body.copy(color = colors.onVideo, textAlign = TextAlign.Center),
            )
            HLButton(stringResource(R.string.common_cancel), onCancel, style = HLButtonStyle.Glass)
        }
    }
}
