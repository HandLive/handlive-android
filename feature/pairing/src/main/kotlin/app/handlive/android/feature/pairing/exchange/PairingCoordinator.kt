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
        /** The phone had no pair before this one: the app goes on to the feature list (SET-01 step 8). */
        val firstPair: Boolean,
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

    /** Orders a client taking the window against the PIN being confirmed, so "Pairing…" starts exactly once. */
    private val pinLock = Any()

    /** A client holds the window; in PIN mode it may be waiting for the PIN (A3–A4). Guarded by [pinLock]. */
    private var clientWaiting = false

    /**
     * The phone had no pair when this pairing began, read with the limit check of step 4 or A3: before the exchange
     * stores its pair, which replaces any older pair of the same client (SET-01 step 8).
     */
    @Volatile
    private var noPairBefore = false

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
        if (invite == null) {
            stateFlow.value = PairingState.Failed(PairingFailure.QR_INVALID)
            return
        }
        val existing = pairs.activeCount()
        if (existing >= PairStore.MAX_ACTIVE_PAIRS) {
            stateFlow.value = PairingState.Failed(PairingFailure.LIMIT_REACHED)
            return
        }
        scanned = invite
        noPairBefore = existing == 0
        stateFlow.value = PairingState.Confirm(invite.clientName)
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

    /** "Enter PIN" (A3): the PIN field; the window opens only once the PIN is confirmed (A4). */
    suspend fun startPin() {
        val existing = pairs.activeCount()
        if (existing >= PairStore.MAX_ACTIVE_PAIRS) {
            stateFlow.value = PairingState.Failed(PairingFailure.LIMIT_REACHED)
            return
        }
        closeWindow()
        noPairBefore = existing == 0
        stateFlow.value = PairingState.EnterPin(attemptsLeft = null)
    }

    /**
     * The user confirmed the PIN (end of A3). The first PIN opens the 120 s window with TXT `pm = 1` (A4); after
     * `PIN_INVALID` the window is still open and takes the new PIN.
     */
    fun submitPin(pin: String) {
        val current =
            window as? PairingWindow.Pin
                ?: PairingWindow.Pin(clock() + PairingWindow.DURATION_MILLIS).also {
                    open(it, PairingAdvert(pinMode = true))
                }
        synchronized(pinLock) {
            current.submit(pin)
            // A client that connected while the PIN was typed has been waiting for it: checking starts now.
            stateFlow.value = if (clientWaiting) PairingState.Verifying else PairingState.Waiting(pinMode = true)
        }
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
        var claimed = false
        val outcome =
            try {
                // A client took the window. With the key material at hand the check starts (`verifying`); a PIN
                // window whose PIN is still being typed keeps the PIN entry open, the client waits for submitPin.
                PairingExchange(device, current, pairs, clock) {
                    claimed = true
                    synchronized(pinLock) {
                        clientWaiting = true
                        if (current.hasSecret) stateFlow.value = PairingState.Verifying
                    }
                }.run(socket)
            } finally {
                if (claimed) synchronized(pinLock) { clientWaiting = false }
            }
        val failure = (outcome as? PairingOutcome.Failed)?.failure
        // A connection that never took the window (a second client, API 2 rule 4, a wrong or malformed pair/hello,
        // one that left before pair/hello) changes nothing, whatever its failure (API 2 logic 4): only its own socket
        // was refused; the client holding the window, or the PIN being typed, carries on.
        if (window !== current || !claimed) return
        val pinUsedUp = current is PairingWindow.Pin && current.offersLeft == 0
        stateFlow.value =
            when {
                outcome is PairingOutcome.Paired -> {
                    closeWindow()
                    onPaired(outcome.pairId)
                    PairingState.Paired(outcome.peerName, outcome.safetyCode, firstPair = noPairBefore)
                }

                // A4, E2: the third offer for this PIN ended without pair/done; the user makes a new PIN.
                pinUsedUp -> {
                    closeWindow()
                    PairingState.Failed(PairingFailure.PIN_EXPIRED)
                }

                failure == PairingFailure.PIN_INVALID && current is PairingWindow.Pin -> {
                    current.reset()
                    // Field 7 shows the phone's own count; the client's `attempts_left` is ignored (API 6).
                    PairingState.EnterPin(current.offersLeft)
                }

                // A client that dropped may come back while the window is open; a PIN being typed stays on screen.
                failure == PairingFailure.DISCONNECTED && current.isOpen(clock()) -> {
                    current.waitingAgain(shown = stateFlow.value)
                }

                else -> {
                    closeWindow()
                    PairingState.Failed(current.closedAs(failure ?: PairingFailure.INTERNAL))
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
                    stateFlow.value = PairingState.Failed(next.closedAs(PairingFailure.PAIRING_CLOSED))
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

/** E2: a closed PIN window says the PIN expired, not that a QR code changed. */
private fun PairingWindow.closedAs(failure: PairingFailure): PairingFailure =
    if (failure == PairingFailure.PAIRING_CLOSED && this is PairingWindow.Pin) PairingFailure.PIN_EXPIRED else failure

/** After a lost client: waiting for the next one, or the PIN entry [shown] while the PIN is still being typed. */
private fun PairingWindow.waitingAgain(shown: PairingState): PairingState =
    if (hasSecret) PairingState.Waiting(pinMode = this is PairingWindow.Pin) else shown
