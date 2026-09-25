package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.clipboard.ClipboardCancelData
import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardConflictData
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.clipboard.engine.Acceptance
import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipContent
import app.handlive.android.feature.clipboard.engine.ClipOrigin
import app.handlive.android.feature.clipboard.engine.ConflictDecision
import app.handlive.android.feature.clipboard.engine.ConflictPolicy
import app.handlive.android.feature.clipboard.engine.PushValidator
import app.handlive.android.feature.clipboard.engine.Rejection
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.capability.LocalCapabilityBuilder
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.serialization.json.JsonObject

/**
 * The receiving side of `clipboard/push` on the phone (CLIP-01 API 5 receiver logic, CLIP-02 API 3–5, CLIP-03
 * steps 8–11): checks, de-duplication (QC6), conflicts (QC8), the clipboard write with its QC4 trace and CLIP-05
 * timer, the `ack`, then forwarding to the other clients. Also routes `clipboard/cancel` and `clipboard/conflict`.
 */
class ClipReceiver(
    private val context: ClipContext,
    private val trace: ClipboardTrace,
    private val sender: ClipSender,
    private val outgoing: OutgoingTransfers,
) {
    private val state = context.state
    val incoming = IncomingTransfers(context, ::reply, ::onVerified)

    suspend fun onPush(
        session: PeerSession,
        pushId: String,
        data: JsonObject,
    ) {
        val push = runCatching { ProtocolJson.decodeFromJsonElement(ClipboardPushData.serializer(), data) }.getOrNull()
        if (push == null) {
            reply(session, ClipWire.rejected(pushId, null, Rejection(ErrorCode.BAD_REQUEST, "malformed push")), null)
            return
        }
        BenchLog.event(
            BenchEvent.CLIP_RECEIVED,
            "clip" to push.clipId,
            "peer" to session.peerDeviceId.take(PEER_ID),
            "kind" to push.kind,
            "bytes" to PushValidator.size(push),
        )
        val rejection = PushValidator.check(push, acceptance(session))
        when {
            rejection != null -> {
                state.ledger.recordRejected(push.clipId)
                reply(session, ClipWire.rejected(pushId, push.clipId, rejection), push.clipId)
            }

            state.ledger.isDuplicate(push.clipId) -> {
                reply(session, ClipWire.ignored(pushId, push.clipId, ClipboardValues.REASON_DUPLICATE), push.clipId)
            }

            else -> {
                val text = push.text
                if (text == null) {
                    incoming.start(session, pushId, push)
                } else {
                    apply(session, pushId, push, ClipContent.Text(text))
                }
            }
        }
    }

    suspend fun onChunk(
        session: PeerSession,
        chunk: ClipboardChunkPlaintext,
    ) = incoming.onChunk(session, chunk)

    /** API 5: the transfer is either one this phone receives or one it sends; unknown ids are ignored (rule 3). */
    suspend fun onCancel(
        session: PeerSession,
        cancel: ClipboardCancelData,
    ) {
        if (!incoming.onPeerCancel(
                session,
                cancel.transferId,
            )
        ) {
            outgoing.onPeerCancel(session.pairId, cancel.transferId, cancel.reason)
        }
    }

    /**
     * CLIP-01 API 6 logic 2–3: a conflict about this phone's clip shows "Send Again"; one about another client's clip
     * goes back to that client if it is connected. Neither happens for a clip replayed under QC7.
     */
    suspend fun onConflict(
        session: PeerSession,
        conflict: ClipboardConflictData,
    ) {
        val clip = state.find(conflict.clipId)
        if (clip == null || session.pairId in clip.replayedTo) return
        if (conflict.originDeviceId == context.network.local().deviceId) {
            state.keepForSendAgain(clip.clipId)
            context.platform.notices.conflict(conflict.deviceName, clip.clipId)
        } else {
            context.network.sessions.value.values
                .firstOrNull { it.peerDeviceId == conflict.originDeviceId && it.pairId != session.pairId }
                ?.let { origin -> runCatching { origin.send(MessageType.CLIPBOARD, ClipWire.conflict(conflict)) } }
        }
    }

    private fun acceptance(session: PeerSession): Acceptance {
        val settings = context.settings.value
        return Acceptance(
            active = settings.clipboardEnabled && session.isEffective(Feature.CLIPBOARD),
            mimes =
                if (settings.clipSendImages) LocalCapabilityBuilder.ALL_MIMES else LocalCapabilityBuilder.TEXT_MIMES,
        )
    }

    /** A chunked clip whose SHA-256 matched: text is checked as UTF-8, then it is applied like inline text. */
    private suspend fun onVerified(done: Incoming) {
        val push = done.push
        val file = context.platform.files.clip(push.clipId, push.mime)
        val content =
            runCatching {
                check(done.transfer.file.renameTo(file))
                if (push.kind ==
                    ClipboardValues.KIND_TEXT
                ) {
                    Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(file.readBytes()))
                }
                ClipContent.FileBacked(file, push.mime, file.length(), done.transfer.sha256, push.width, push.height)
            }.getOrNull()
        if (content == null) {
            file.delete()
            done.transfer.discard()
            state.ledger.recordRejected(push.clipId)
            reply(
                done.session,
                ClipWire.rejected(
                    done.pushId,
                    push.clipId,
                    Rejection(ErrorCode.BAD_REQUEST, "text is not valid UTF-8"),
                ),
                push.clipId,
            )
        } else {
            apply(done.session, done.pushId, push, content)
        }
    }

    /** Steps 10–11: QC8, then the write, the QC4 trace, CLIP-05, `ack` `applied` and forwarding (QC6). */
    private suspend fun apply(
        session: PeerSession,
        pushId: String,
        push: ClipboardPushData,
        content: ClipContent,
    ) {
        val now = context.clock.wall()
        val decision =
            ConflictPolicy.decide(
                push.originTs,
                push.originDeviceId,
                session.pairId,
                state.latest?.asLocalChange(),
                now,
            )
        if (decision != ConflictDecision.APPLY) return keepLocal(session, pushId, push, content, decision)
        val written =
            runCatching {
                val writer = context.platform.writer
                when (content) {
                    is ClipContent.Text -> {
                        writer.writeText(push.clipId, content.sha256, content.text, push.sensitive)
                    }

                    is ClipContent.FileBacked -> {
                        writer.writeFile(
                            push.clipId,
                            content.sha256,
                            content.file,
                            push.sensitive,
                        )
                    }
                }
            }.getOrNull()
        if (written == null) {
            state.ledger.recordRejected(push.clipId)
            (content as? ClipContent.FileBacked)?.file?.delete()
            reply(
                session,
                ClipWire.rejected(pushId, push.clipId, Rejection(ErrorCode.INTERNAL, "clipboard write failed")),
                push.clipId,
            )
        } else {
            BenchLog.event(BenchEvent.CLIP_APPLIED, "clip" to push.clipId)
            state.loopGuard.onOwnWrite(content.sha256)
            trace.onWrite(written)
            val clip =
                Clip(
                    push.clipId,
                    content,
                    push.sensitive,
                    ClipOrigin(push.originDeviceId, push.source, push.originTs),
                    session.pairId,
                    now,
                )
            state.remember(clip)
            state.ledger.recordFinal(push.clipId)
            reply(session, ClipWire.applied(pushId, push.clipId), push.clipId)
            sender.distribute(clip, manual = false)
        }
    }

    /** QC8: `ignored`/`conflict`; case (a) also tells the sender which device kept its content (API 6 logic 1). */
    private suspend fun keepLocal(
        session: PeerSession,
        pushId: String,
        push: ClipboardPushData,
        content: ClipContent,
        decision: ConflictDecision,
    ) {
        (content as? ClipContent.FileBacked)?.file?.delete()
        state.ledger.recordFinal(push.clipId)
        reply(session, ClipWire.ignored(pushId, push.clipId, ClipboardValues.REASON_CONFLICT), push.clipId)
        if (decision == ConflictDecision.KEEP_LOCAL_AND_NOTIFY) {
            val local = context.network.local()
            val conflict = ClipboardConflictData(push.clipId, push.originDeviceId, local.deviceId, local.name)
            runCatching { session.send(MessageType.CLIPBOARD, ClipWire.conflict(conflict)) }
        }
    }

    /** Sends the `ack` of a push (and keeps it for duplicates); a closed session is not an error here. */
    private suspend fun reply(
        session: PeerSession,
        ack: Ack,
        clipId: String?,
    ) {
        runCatching { session.sendAck(ack) }
        BenchLog.event(
            BenchEvent.ACK_SENT,
            "clip" to (clipId ?: "-"),
            "peer" to session.peerDeviceId.take(PEER_ID),
            "status" to (ClipWire.status(ack)?.status ?: ClipboardValues.STATUS_REJECTED),
        )
    }

    private companion object {
        const val PEER_ID = 8
    }
}
