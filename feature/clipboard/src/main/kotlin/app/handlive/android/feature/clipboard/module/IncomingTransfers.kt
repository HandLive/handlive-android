package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.TransferProgress
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.engine.IncomingTransfer
import app.handlive.android.feature.clipboard.engine.Rejection
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/** One chunked clip being received: the push to answer, its file and its progress (CLIP-03 step 8). */
class Incoming(
    val session: PeerSession,
    val pushId: String,
    val push: ClipboardPushData,
    val transfer: IncomingTransfer,
    val progress: TransferProgress?,
) {
    internal var watchdog: Job? = null
}

/**
 * The receiving side of CLIP-03 (API 3–5): at most one transfer per peer, chunks written straight to
 * `cache/clip/<transfer_id>.part` with an incremental SHA-256, a 30 s idle watchdog (E7), cancellation from either
 * side (E5, E6), `BAD_REQUEST` on a chunk out of order, `CLIP_CHECKSUM_MISMATCH` on a wrong hash (E4), `INTERNAL`
 * without storage (E9). A verified transfer goes to [onVerified].
 */
class IncomingTransfers(
    private val context: ClipContext,
    private val reply: suspend (PeerSession, Ack, String) -> Unit,
    private val onVerified: suspend (Incoming) -> Unit,
) {
    private val byPair = HashMap<String, Incoming>()

    /** A push with `transfer` passed the checks: a running transfer from the same peer is replaced (rule 2). */
    suspend fun start(
        session: PeerSession,
        pushId: String,
        push: ClipboardPushData,
    ) {
        byPair[session.pairId]?.let { abort(it, cancel = null, reason = ClipboardValues.REASON_CANCELLED) }
        val transfer = checkNotNull(push.transfer)
        val file = context.platform.files.part(transfer.transferId)
        val opened =
            runCatching {
                if (!context.platform.files.hasRoomFor(transfer.size)) throw IOException("no space")
                IncomingTransfer(transfer, file, context.clock.elapsed)
            }
        val incoming =
            opened.getOrNull()?.let { Incoming(session, pushId, push, it, progressOf(session, push)) }
                ?: return fail(session, pushId, push, Rejection(ErrorCode.INTERNAL, "no storage for the transfer"))
        byPair[session.pairId] = incoming
        incoming.watchdog = context.scope.launch { watch(incoming) }
        incoming.progress?.let { context.platform.notices.progress(it.copy(percent = 0)) }
    }

    /** API 4: a chunk for a transfer that is no longer running is dropped silently (rule 2). */
    suspend fun onChunk(
        session: PeerSession,
        chunk: ClipboardChunkPlaintext,
    ) {
        val incoming = byPair[session.pairId]?.takeIf { it.transfer.transfer.transferId == chunk.transferId } ?: return
        when (val step = runCatching { incoming.transfer.accept(chunk.index, chunk.chunk) }.getOrNull()) {
            IncomingTransfer.Step.More -> {
                incoming.progress?.let {
                    context.platform.notices.progress(
                        it.copy(percent = percentOf(chunk.index + 1, incoming.transfer.transfer.chunkCount)),
                    )
                }
            }

            is IncomingTransfer.Step.Complete -> {
                finish(incoming)
                if (step.verified) {
                    onVerified(incoming)
                } else {
                    incoming.transfer.discard()
                    val mismatch = Rejection(ErrorCode.CLIP_CHECKSUM_MISMATCH, "SHA-256 mismatch")
                    fail(session, incoming.pushId, incoming.push, mismatch, chunk.transferId)
                }
            }

            IncomingTransfer.Step.Broken -> {
                finish(incoming)
                fail(session, incoming.pushId, incoming.push, Rejection(ErrorCode.BAD_REQUEST, "chunk out of order"))
            }

            null -> {
                finish(incoming)
                fail(
                    session,
                    incoming.pushId,
                    incoming.push,
                    Rejection(ErrorCode.INTERNAL, "no storage for the transfer"),
                )
            }
        }
    }

    /** The sender cancelled (API 5 rule 1); `false` when [transferId] is not an incoming transfer. */
    suspend fun onPeerCancel(
        session: PeerSession,
        transferId: String,
    ): Boolean {
        val incoming = byPair[session.pairId]?.takeIf { it.transfer.transfer.transferId == transferId } ?: return false
        abort(incoming, cancel = null, reason = ClipboardValues.REASON_CANCELLED)
        return true
    }

    /** The user chose "Cancel" on the receiving progress (E6). */
    suspend fun cancelByUser(
        pairId: String,
        transferId: String,
    ) {
        val incoming = byPair[pairId]?.takeIf { it.transfer.transfer.transferId == transferId } ?: return
        abort(incoming, cancel = ClipboardValues.CANCEL_USER, reason = ClipboardValues.REASON_CANCELLED)
    }

    /** E8: the connection is gone; the file is deleted and nothing is answered. */
    fun onSessionClosed(pairId: String) {
        byPair[pairId]?.let(::finish)
    }

    private suspend fun watch(incoming: Incoming) {
        while (true) {
            val left = incoming.transfer.lastChunkAt + ClipLimits.TRANSFER_IDLE_MILLIS - context.clock.elapsed()
            if (left <= 0) break
            delay(left)
        }
        incoming.watchdog = null
        abort(incoming, cancel = ClipboardValues.CANCEL_TIMEOUT, reason = ClipboardValues.REASON_CANCELLED)
    }

    /** `clipboard/cancel` when this side cancels (`user`, `timeout`), then `ack` `ignored`/`cancelled` (rule 2). */
    private suspend fun abort(
        incoming: Incoming,
        cancel: String?,
        reason: String,
    ) {
        finish(incoming)
        val transferId = incoming.transfer.transfer.transferId
        cancel?.let { runCatching { incoming.session.send(MessageType.CLIPBOARD, ClipWire.cancel(transferId, it)) } }
        // A cancelled clip may come again (a timeout is "not received", QC7), so it is not a final outcome.
        context.state.ledger.recordRejected(incoming.push.clipId)
        reply(incoming.session, ClipWire.ignored(incoming.pushId, incoming.push.clipId, reason), incoming.push.clipId)
    }

    /** Error `ack` (`rejected`): the same clip may be sent again (resend after a checksum mismatch). */
    private suspend fun fail(
        session: PeerSession,
        pushId: String,
        push: ClipboardPushData,
        rejection: Rejection,
        transferId: String? = null,
    ) {
        if (rejection.code == ErrorCode.INTERNAL) context.platform.notices.show(ClipMessage.ImageNoSpace)
        context.state.ledger.recordRejected(push.clipId)
        reply(session, ClipWire.rejected(pushId, push.clipId, rejection, transferId), push.clipId)
    }

    /** Stops the transfer: no longer current, watchdog stopped, progress removed, an unfinished file deleted. */
    private fun finish(incoming: Incoming) {
        if (byPair[incoming.session.pairId] === incoming) byPair.remove(incoming.session.pairId)
        incoming.watchdog?.cancel()
        incoming.progress?.let { context.platform.notices.progress(it) }
        if (!incoming.transfer.isComplete) incoming.transfer.discard()
    }

    private fun progressOf(
        session: PeerSession,
        push: ClipboardPushData,
    ): TransferProgress? {
        val transfer = checkNotNull(push.transfer)
        return if (push.kind == ClipboardValues.KIND_IMAGE && transfer.size > ClipLimits.PROGRESS_MIN_BYTES) {
            TransferProgress(transfer.transferId, session.pairId, session.peerName, sending = false, percent = null)
        } else {
            null
        }
    }
}
