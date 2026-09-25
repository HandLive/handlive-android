package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.ClipNotices
import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import app.handlive.android.feature.connection.capability.LocalCapabilityBuilder
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sends clips to the connected clients (CLIP-01 API 5 sender logic, QC6, QC7): one delivery per session with
 * active clipboard, within the peer's `mimes` and limits (QC1), never back to the origin device or the pair the
 * clip came from. `applied`/`ignored` mark the pair as delivered (no replay); no `ack` in 10 s is not retried
 * (E8); `CLIP_CHECKSUM_MISMATCH` resends once (CLIP-03 E4); `FEATURE_DISABLED` pauses the pair until its next
 * `capability/update` (E10).
 */
class ClipSender(
    private val scope: CoroutineScope,
    private val sessions: StateFlow<Map<String, PeerSession>>,
    private val notices: ClipNotices,
    private val transfers: OutgoingTransfers,
) {
    private val disabledCapability = ConcurrentHashMap<String, Any>()

    /** QC6: sends [clip] to every eligible session; returns how many deliveries started (0 → E6). */
    fun distribute(
        clip: Clip,
        manual: Boolean,
    ): Int {
        val targets = sessions.value.values.filter { eligible(it, clip) }
        val reported = AtomicBoolean(!manual)
        targets.forEach { session -> scope.launch { deliver(session, clip, reported) } }
        return targets.size
    }

    /** QC7: a new session gets the latest clip if it is fresh, not yet delivered there and not from that device. */
    fun replay(
        session: PeerSession,
        latest: Clip?,
        fresh: Boolean,
    ) {
        val clip = latest?.takeIf { fresh && eligible(session, it) } ?: return
        clip.replayedTo += session.pairId
        scope.launch { deliver(session, clip, AtomicBoolean(true)) }
    }

    private fun eligible(
        session: PeerSession,
        clip: Clip,
    ): Boolean = active(session) && routable(session, clip) && fits(session, clip)

    /** Clipboard effective for the pair (QC1) and not paused by `FEATURE_DISABLED` (E10). */
    private fun active(session: PeerSession): Boolean =
        session.isEffective(Feature.CLIPBOARD) && disabledCapability[session.pairId] !== session.peerCapability.value

    /** Not back to where it came from, and not twice to the same pair (QC6, QC7). */
    private fun routable(
        session: PeerSession,
        clip: Clip,
    ): Boolean =
        session.pairId != clip.fromPairId &&
            session.peerDeviceId != clip.origin.deviceId &&
            session.pairId !in clip.deliveredTo

    /** The peer's `mimes` and the smaller of both limits (QC1). */
    private fun fits(
        session: PeerSession,
        clip: Clip,
    ): Boolean {
        val feature =
            session.peerCapability.value
                ?.features
                ?.clipboard
        val maxBytes =
            if (clip.kind == ClipboardValues.KIND_IMAGE) {
                minOf(ClipLimits.MAX_IMAGE_BYTES, feature?.maxImageBytes ?: ClipLimits.MAX_IMAGE_BYTES)
            } else {
                minOf(ClipLimits.MAX_TEXT_BYTES, feature?.maxTextBytes ?: ClipLimits.MAX_TEXT_BYTES)
            }
        return clip.content.mime in (feature?.mimes ?: LocalCapabilityBuilder.TEXT_MIMES) &&
            clip.content.size <= maxBytes
    }

    private suspend fun deliver(
        session: PeerSession,
        clip: Clip,
        reported: AtomicBoolean,
    ) {
        transfers.supersede(session)
        var ack = attempt(session, clip)
        if (ack?.error?.errorCode == ErrorCode.CLIP_CHECKSUM_MISMATCH) {
            ack = attempt(session, clip)
            if (ack?.error?.errorCode == ErrorCode.CLIP_CHECKSUM_MISMATCH) notices.show(ClipMessage.ImageSendFailed)
        }
        ack?.let { record(session, clip, it, reported) }
    }

    /** One push (inline or chunked); `null` when no `ack` came (E8) or the session went away. */
    private suspend fun attempt(
        session: PeerSession,
        clip: Clip,
    ): Ack? {
        val onSent = {
            BenchLog.event(BenchEvent.CLIP_SENT, "clip" to clip.clipId, "peer" to session.peerDeviceId.take(PEER_ID))
        }
        val result =
            runCatching {
                val inline = ClipWire.inlineOrNull(clip)
                if (inline != null) {
                    session.request(MessageType.CLIPBOARD, inline) { onSent() }
                } else {
                    transfers.send(session, clip, onSent)
                }
            }
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        return result.getOrNull()
    }

    private fun record(
        session: PeerSession,
        clip: Clip,
        ack: Ack,
        reported: AtomicBoolean,
    ) {
        val status = ClipWire.status(ack)?.status ?: ClipboardValues.STATUS_REJECTED
        BenchLog.event(
            BenchEvent.ACK_RECEIVED,
            "clip" to clip.clipId,
            "peer" to session.peerDeviceId.take(PEER_ID),
            "status" to status,
        )
        val failure = ack.error?.errorCode
        when {
            ack.ok && status == ClipboardValues.STATUS_APPLIED -> {
                clip.deliveredTo += session.pairId
                if (reported.compareAndSet(false, true)) notices.show(ClipMessage.SentTo(session.peerName))
            }

            ack.ok -> {
                if (ClipWire.status(ack)?.reason != ClipboardValues.REASON_CANCELLED) clip.deliveredTo += session.pairId
            }

            failure == ErrorCode.FEATURE_DISABLED -> {
                session.peerCapability.value?.let { disabledCapability[session.pairId] = it }
                if (reported.compareAndSet(false, true)) notices.show(ClipMessage.FeatureDisabled(session.peerName))
            }

            failure == ErrorCode.CLIP_TOO_LARGE -> {
                if (reported.compareAndSet(false, true)) notices.show(tooLarge(clip))
            }

            // CLIP-03 E4: resent once; a second mismatch is "Couldn't send the image" (see deliver).
            failure == ErrorCode.CLIP_CHECKSUM_MISMATCH -> {
                Unit
            }

            // CLIP-01 field 11: any other refusal of a manual send.
            reported.compareAndSet(false, true) -> {
                notices.show(ClipMessage.WriteFailedOnDevice(session.peerName))
            }
        }
    }

    private companion object {
        const val PEER_ID = 8
    }
}

private fun tooLarge(clip: Clip): ClipMessage =
    if (clip.kind == ClipboardValues.KIND_IMAGE) ClipMessage.ImageTooLarge else ClipMessage.TextTooLarge
