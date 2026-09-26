package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.protocol.id.UuidV7Generator
import app.handlive.android.feature.clipboard.ClipNotices
import app.handlive.android.feature.clipboard.TransferProgress
import app.handlive.android.feature.clipboard.engine.ChunkSource
import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import java.util.concurrent.ConcurrentHashMap

/**
 * Chunked sends of the phone (CLIP-03 API 3–5): `clipboard/push` with `transfer`, then the 64 KiB chunks in order;
 * the 10 s `ack` wait starts after the last chunk. One transfer per peer: a newer clip cancels the running one with
 * `clipboard/cancel superseded` (E5); the user's "Cancel" sends `user` (E6); a receiver's cancel stops the chunks.
 */
class OutgoingTransfers(
    private val notices: ClipNotices,
    wall: () -> Long,
) {
    private class Active(
        val transferId: String,
        val clip: Clip,
        val job: Job,
    )

    private val ids = UuidV7Generator(wall)
    private val active = ConcurrentHashMap<String, Active>()

    /** Sends [clip] to [session] in chunks and returns the push `ack`; [onPushSent] runs once the push is out. */
    suspend fun send(
        session: PeerSession,
        clip: Clip,
        onPushSent: () -> Unit,
    ): Ack {
        val transferId = ids.next()
        val entry = Active(transferId, clip, currentCoroutineContext().job)
        active[session.pairId] = entry
        val progress = progressOf(session, clip, transferId)
        return try {
            ChunkSource.open(clip.content).use { source ->
                session.request(MessageType.CLIPBOARD, ClipWire.chunkedPush(clip, transferId)) {
                    onPushSent()
                    for (index in 0 until source.chunkCount) {
                        session.send(
                            MessageType.CLIPBOARD,
                            ClipboardChunkPlaintext(transferId, index, source.next()).encode(),
                        )
                        progress?.let { notices.progress(it.copy(percent = percentOf(index + 1, source.chunkCount))) }
                    }
                }
            }
        } finally {
            active.remove(session.pairId, entry)
            progress?.let { notices.progress(it) }
        }
    }

    /** The user chose "Cancel" on the sending progress (E6): that clip is not replayed to this peer. */
    suspend fun cancelByUser(
        session: PeerSession,
        transferId: String,
    ) {
        val entry = active[session.pairId]?.takeIf { it.transferId == transferId } ?: return
        stop(session.pairId, entry)
        entry.clip.deliveredTo += session.pairId
        runCatching { session.send(MessageType.CLIPBOARD, ClipWire.cancel(transferId, ClipboardValues.CANCEL_USER)) }
    }

    /** The receiver cancelled (`user` or `timeout`, API 5 rule 2); `user` means the clip is not replayed to it. */
    fun onPeerCancel(
        pairId: String,
        transferId: String,
        reason: String,
    ) {
        val entry = active[pairId]?.takeIf { it.transferId == transferId } ?: return
        stop(pairId, entry)
        if (reason == ClipboardValues.CANCEL_USER) entry.clip.deliveredTo += pairId
    }

    /** E8: the session closed; the transfer stops, QC7 may replay the clip later. */
    fun onSessionClosed(pairId: String) {
        active[pairId]?.let { stop(pairId, it) }
    }

    /** E5: a newer clip for [session] cancels its running transfer with `clipboard/cancel superseded`. */
    suspend fun supersede(session: PeerSession) {
        val previous = active[session.pairId] ?: return
        stop(session.pairId, previous)
        runCatching {
            session.send(MessageType.CLIPBOARD, ClipWire.cancel(previous.transferId, ClipboardValues.CANCEL_SUPERSEDED))
        }
    }

    private fun stop(
        pairId: String,
        entry: Active,
    ) {
        active.remove(pairId, entry)
        entry.job.cancel()
    }

    private fun progressOf(
        session: PeerSession,
        clip: Clip,
        transferId: String,
    ): TransferProgress? =
        if (clip.kind == ClipboardValues.KIND_IMAGE && clip.content.size > ClipLimits.PROGRESS_MIN_BYTES) {
            TransferProgress(transferId, session.pairId, session.peerName, sending = true, percent = null)
        } else {
            null
        }
}

/** Progress in whole percent (CLIP-03 API 3 rule 6: chunks done / `chunk_count`). */
internal fun percentOf(
    done: Int,
    total: Int,
): Int = if (total == 0) 0 else done * PERCENT / total

private const val PERCENT = 100
