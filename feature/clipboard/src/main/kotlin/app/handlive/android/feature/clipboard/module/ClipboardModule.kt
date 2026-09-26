package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.clipboard.ClipboardCancelData
import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardConflictData
import app.handlive.android.core.protocol.clipboard.ClipboardOp
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.core.transport.server.InboundEnvelope
import app.handlive.android.feature.clipboard.system.ClipLabel
import app.handlive.android.feature.connection.session.EnvelopeHandler
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A-CLIP's engine (`ClipboardModule` of 04-clipboard), one per process: local clips go out through
 * [LocalClipIntake], `type = clipboard` envelopes come in through [handler], and [trace] runs CLIP-05. Everything
 * runs on one serial [dispatcher], so the state needs no locks.
 */
class ClipboardModule(
    private val dispatcher: CoroutineDispatcher,
    private val sessions: StateFlow<Map<String, PeerSession>>,
    private val settings: StateFlow<HandLiveSettings>,
    platform: ClipPlatform,
    local: suspend () -> LocalDevice,
    clock: ClipClock,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val context = ClipContext(scope, clock, ClipState(clock), platform, settings, ClipNetwork(sessions, local))
    private val transfers = OutgoingTransfers(platform.notices, clock.wall)
    private val sender = ClipSender(scope, sessions, platform.notices, transfers)
    val trace = ClipboardTrace(context)
    private val receiver = ClipReceiver(context, trace, sender, transfers)
    private val intake = LocalClipIntake(context, trace, sender)

    /** Registered with the connection runtime's router for `MessageType.CLIPBOARD`. */
    val handler = EnvelopeHandler { session, envelope -> withContext(dispatcher) { dispatch(session, envelope) } }

    /** QC7 replay and E8 cleanup follow the sessions; CLIP-05 E5 follows the setting; old files go (CLIP-03 API 6). */
    fun start() {
        var previous = emptyMap<String, PeerSession>()
        sessions
            .onEach { current ->
                previous.filter { (pairId, session) -> current[pairId] !== session }.keys.forEach { pairId ->
                    receiver.incoming.onSessionClosed(pairId)
                    transfers.onSessionClosed(pairId)
                }
                val latest = context.state.latest
                current.filter { (pairId, session) -> previous[pairId] !== session }.values.forEach { session ->
                    sender.replay(session, latest, latest != null && context.state.isFresh(latest))
                }
                previous = current
            }.launchIn(scope)
        settings
            .map { it.clipAutoClearSeconds }
            .distinctUntilChanged()
            .drop(1)
            .onEach(trace::onAutoClearChanged)
            .launchIn(scope)
        scope.launch(context.platform.io) {
            context.platform.files.cleanUp()
            // Loads the identity early so the first clip does not wait for the Keystore; a failure shows up later.
            runCatching { context.network.local() }
        }
    }

    fun onLocalRead(read: LocalRead) {
        scope.launch { intake.onRead(read) }
    }

    /**
     * An Accessibility copy signal (CLIP-01 API 1): ignored for 1 s after HandLive's own write (QC4); otherwise it is
     * a sign of change for CLIP-05, and `true` tells the service to read the clipboard after its debounce.
     */
    fun acceptCopySignal(): Boolean {
        if (context.state.loopGuard.ignoresSignals()) return false
        scope.launch { trace.markChanged() }
        return true
    }

    /**
     * The in-app listener (A-UI has focus, CLIP-01 API 2 logic 6): a change that is not HandLive's own write is a
     * sign of change for CLIP-05, and `true` tells the caller to read it.
     */
    fun onClipboardChanged(label: ClipLabel?): Boolean {
        if (trace.isOwnClip(label)) return false
        scope.launch { trace.markChanged() }
        return true
    }

    fun sendAnyway() {
        scope.launch { intake.sendAnyway() }
    }

    fun sendAgain(clipId: String) {
        scope.launch { intake.sendAgain(clipId) }
    }

    /** "Cancel" on a progress notification (CLIP-03 E6), sending or receiving side. */
    fun cancelTransfer(
        pairId: String,
        transferId: String,
        sending: Boolean,
    ) {
        scope.launch {
            val session = sessions.value[pairId]
            if (sending) {
                session?.let {
                    transfers.cancelByUser(it, transferId)
                }
            } else {
                receiver.incoming.cancelByUser(pairId, transferId)
            }
        }
    }

    /** A-UI got focus: CLIP-05 checks a postponed clear (E3). */
    fun onAppFocused() {
        scope.launch { trace.onAppFocused() }
    }

    private suspend fun dispatch(
        session: PeerSession,
        envelope: InboundEnvelope,
    ) {
        val plaintext = envelope.plaintext
        // A chunk's binary plaintext starts with the high byte of hdr_len, always 0x00; JSON starts with '{'.
        if (plaintext.firstOrNull() == 0.toByte()) {
            runCatching { ClipboardChunkPlaintext.decode(plaintext) }.getOrNull()?.let { receiver.onChunk(session, it) }
            return
        }
        val payload = runCatching { PlaintextCodec.decodePayload(plaintext) }.getOrNull() ?: return
        when (payload.op) {
            ClipboardOp.PUSH -> {
                receiver.onPush(session, envelope.id, payload.data)
            }

            ClipboardOp.CANCEL -> {
                runCatching { ProtocolJson.decodeFromJsonElement(ClipboardCancelData.serializer(), payload.data) }
                    .getOrNull()
                    ?.let { receiver.onCancel(session, it) }
            }

            ClipboardOp.CONFLICT -> {
                runCatching { ProtocolJson.decodeFromJsonElement(ClipboardConflictData.serializer(), payload.data) }
                    .getOrNull()
                    ?.let { receiver.onConflict(session, it) }
            }
        }
    }
}
