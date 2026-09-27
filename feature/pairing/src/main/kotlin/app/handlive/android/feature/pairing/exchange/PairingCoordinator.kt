package app.handlive.android.feature.pairing.exchange

import app.handlive.android.core.data.pairing.PairStore
import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.transport.server.PairingEndpoint
import app.handlive.android.core.transport.server.TextMessageSocket
import app.handlive.android.feature.connection.PairingAdvert
import app.handlive.android.feature.pairing.invite.PairingInvite
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the pairing screens show (PAIR-01 field 8 and the steps around it). */
sealed interface PairingState {
    data object Idle : PairingState

    /** A valid code was scanned: "Pair with <name>?" with "Pair" and "Cancel" (field 5). */
    data class Confirm(
        val clientName: String,
    ) : PairingState

    /** The window is open and the phone waits for the Mac or iPhone (`connecting`). */
    data class Waiting(
        val pinMode: Boolean,
    ) : PairingState

    /** PIN window (A3): type the code shown on the Mac; [attemptsLeft] after a wrong PIN (field 7). */
    data class EnterPin(
        val attemptsLeft: Int?,
    ) : PairingState

    /** A client is connected and the keys are being checked (`verifying`). */
    data object Verifying : PairingState

    data class Paired(
        val peerName: String,
        val safetyCode: String,
    ) : PairingState

    data class Failed(
        val failure: PairingFailure,
    ) : PairingState
}

/** Joins and leaves the relay rendezvous of a QR code with `rv` (PAIR-01 step 6, API 7); the relay feature's. */
interface RendezvousConnector {
    fun join(
        rvId: String,
        endpoint: PairingEndpoint,
    )

    fun leave(rvId: String)
}

/**
 * Drives PAIR-01 on the phone: validates the scanned code (E1, E6), opens a 120 s window on confirmation (step 6,
 * TXT `pr`) or for a PIN (A4, TXT `pm = 1`), serves `/v1/pair` through [PairingExchange], and closes the window on
 * success, failure, expiry (E2) or cancel (E5). Only one window exists at a time.
 */
class PairingCoordinator(
    private val local: suspend () -> LocalPairingDevice?,
    private val pairs: PairStore,
    private val advertise: (PairingAdvert) -> Unit,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : PairingEndpoint {
    private val stateFlow = MutableStateFlow<PairingState>(PairingState.Idle)
    private val wire = PairingWire(clock)

    @Volatile
    private var scanned: PairingInvite? = null

    @Volatile
    private var window: PairingWindow? = null
    private var expiry: Job? = null
    private var rendezvousId: String? = null

    /** The relay rendezvous, when the relay is available (Phase 2); without it a code with `rv` pairs on the LAN. */
    @Volatile
    var rendezvous: RendezvousConnector? = null

    /** A pair was stored (step 11): the relay feature registers it (`POST /v1/pairs`, PAIR-01 API 8). */
    @Volatile
    var onPaired: (pairId: String) -> Unit = {}

    val state: StateFlow<PairingState> = stateFlow.asStateFlow()

    /** PAIR-01 step 4: E1 for a code that is not HandLive's, E6 when 8 pairs exist already. */
    suspend fun onScanned(text: String) {
        val invite = PairingInvite.parse(text)
        stateFlow.value =
            when {
                invite == null -> PairingState.Failed(PairingFailure.QR_INVALID)
                pairs.activeCount() >= PairStore.MAX_ACTIVE_PAIRS -> PairingState.Failed(PairingFailure.LIMIT_REACHED)
                else -> PairingState.Confirm(invite.clientName).also { scanned = invite }
            }
    }

    /** "Pair" in the confirmation (step 5 → 6). */
    fun confirm() {
        val invite = scanned ?: return
        open(
            PairingWindow.Qr(invite, clock() + PairingWindow.DURATION_MILLIS),
            PairingAdvert(invite.pairingRequestHint),
        )
        // Step 6: with `rv`, the phone also waits for the client in the relay rendezvous; the LAN wins if both work.
        invite.rendezvous?.let { rv ->
            val id = Base64Codecs.encodeB64u(rv)
            rendezvousId = id
            rendezvous?.join(id, this)
        }
        stateFlow.value = PairingState.Waiting(pinMode = false)
    }

    /** "Enter PIN" (A3): the window opens in PIN mode before the PIN is typed. */
    suspend fun startPin() {
        if (pairs.activeCount() >= PairStore.MAX_ACTIVE_PAIRS) {
            stateFlow.value = PairingState.Failed(PairingFailure.LIMIT_REACHED)
            return
        }
        open(PairingWindow.Pin(clock() + PairingWindow.DURATION_MILLIS), PairingAdvert(pinMode = true))
        stateFlow.value = PairingState.EnterPin(attemptsLeft = null)
    }

    fun submitPin(pin: String) {
        val current = window as? PairingWindow.Pin ?: return
        current.submit(pin)
        stateFlow.value = PairingState.Waiting(pinMode = true)
    }

    /** "Cancel" (E5) or leaving the screen: nothing is stored, the secret is dropped. */
    fun cancel() {
        closeWindow()
        stateFlow.value = PairingState.Idle
    }

    /** E9: no camera permission → the screen offers the PIN. */
    fun onCameraDenied() {
        stateFlow.value = PairingState.Failed(PairingFailure.CAMERA_DENIED)
    }

    /** Back to the start after the result was shown. */
    fun reset() {
        if (window == null) stateFlow.value = PairingState.Idle
    }

    override suspend fun handle(socket: TextMessageSocket) {
        val current = window
        val device = local()
        if (current == null || device == null || !current.isOpen(clock())) {
            // API 2 rule 1: outside a window → `pair/error PAIRING_CLOSED` and close.
            wire.refuse(socket, ErrorCode.PAIRING_CLOSED)
            return
        }
        stateFlow.value = PairingState.Verifying
        val outcome = PairingExchange(device, current, pairs, clock).run(socket)
        if (window !== current) return
        stateFlow.value =
            when {
                outcome is PairingOutcome.Paired -> {
                    closeWindow()
                    onPaired(outcome.pairId)
                    PairingState.Paired(outcome.peerName, outcome.safetyCode)
                }

                outcome is PairingOutcome.Failed && outcome.failure == PairingFailure.PIN_INVALID -> {
                    (current as? PairingWindow.Pin)?.reset()
                    PairingState.EnterPin(outcome.attemptsLeft)
                }

                // A client that dropped may come back while the window is open.
                outcome is PairingOutcome.Failed && outcome.failure == PairingFailure.DISCONNECTED &&
                    current.isOpen(clock()) -> {
                    PairingState.Waiting(pinMode = current is PairingWindow.Pin)
                }

                else -> {
                    closeWindow()
                    PairingState.Failed((outcome as? PairingOutcome.Failed)?.failure ?: PairingFailure.INTERNAL)
                }
            }
    }

    private fun open(
        next: PairingWindow,
        advert: PairingAdvert,
    ) {
        closeWindow()
        window = next
        advertise(advert)
        expiry =
            scope.launch {
                delay(next.expiresAt - clock())
                if (window === next) {
                    closeWindow()
                    stateFlow.value = PairingState.Failed(PairingFailure.PAIRING_CLOSED)
                }
            }
    }

    private fun closeWindow() {
        expiry?.cancel()
        expiry = null
        window = null
        scanned = null
        rendezvousId?.let { rendezvous?.leave(it) }
        rendezvousId = null
        advertise(PairingAdvert.NONE)
    }
}
