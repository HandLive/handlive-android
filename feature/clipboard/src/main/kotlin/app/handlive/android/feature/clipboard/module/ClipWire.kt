package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ProtocolJson
import app.handlive.android.core.protocol.ack.Ack
import app.handlive.android.core.protocol.clipboard.ClipboardAckData
import app.handlive.android.core.protocol.clipboard.ClipboardCancelData
import app.handlive.android.core.protocol.clipboard.ClipboardConflictData
import app.handlive.android.core.protocol.clipboard.ClipboardOp
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.PlaintextCodec
import app.handlive.android.feature.clipboard.engine.ChunkPlan
import app.handlive.android.feature.clipboard.engine.Clip
import app.handlive.android.feature.clipboard.engine.ClipContent
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.engine.Rejection
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Plaintexts and `ack`s of `type = clipboard` (CLIP-01 API 5–6, CLIP-03 API 3–5). */
object ClipWire {
    /** The inline `clipboard/push` of a text clip, or `null` when its plaintext exceeds `CLIP_INLINE_MAX` (QC5). */
    fun inlineOrNull(clip: Clip): ByteArray? {
        val content = clip.content as? ClipContent.Text ?: return null
        return PlaintextCodec
            .encodeOp(ClipboardOp.PUSH, ClipboardPushData.serializer(), data(clip, content.text, null))
            .takeIf { it.size <= ClipLimits.INLINE_MAX_BYTES }
    }

    fun chunkedPush(
        clip: Clip,
        transferId: String,
    ): ByteArray {
        val content = clip.content
        val transfer =
            ClipboardTransfer(
                transferId = transferId,
                size = content.size,
                sha256 = Base64Codecs.encodeB64u(content.sha256),
                chunkSize = ChunkPlan.CHUNK_SIZE,
                chunkCount = ChunkPlan.chunkCount(content.size),
            )
        return PlaintextCodec.encodeOp(ClipboardOp.PUSH, ClipboardPushData.serializer(), data(clip, null, transfer))
    }

    fun cancel(
        transferId: String,
        reason: String,
    ): ByteArray =
        PlaintextCodec.encodeOp(
            ClipboardOp.CANCEL,
            ClipboardCancelData.serializer(),
            ClipboardCancelData(transferId, reason),
        )

    fun conflict(data: ClipboardConflictData): ByteArray =
        PlaintextCodec.encodeOp(ClipboardOp.CONFLICT, ClipboardConflictData.serializer(), data)

    fun applied(
        re: String,
        clipId: String,
    ): Ack = Ack.success(re, json(ClipboardAckData(clipId, ClipboardValues.STATUS_APPLIED)))

    fun ignored(
        re: String,
        clipId: String,
        reason: String,
    ): Ack = Ack.success(re, json(ClipboardAckData(clipId, ClipboardValues.STATUS_IGNORED, reason)))

    /** `ok = false` with `details = {clip_id, status: rejected[, transfer_id]}`. */
    fun rejected(
        re: String,
        clipId: String?,
        rejection: Rejection,
        transferId: String? = null,
    ): Ack =
        Ack.failure(
            re,
            rejection.code,
            rejection.message,
            clipId?.let { json(ClipboardAckData(it, ClipboardValues.STATUS_REJECTED, transferId = transferId)) },
        )

    /** `status` of a push `ack` for the bench log and the sender: `applied`, `ignored` or `rejected`. */
    fun status(ack: Ack): ClipboardAckData? =
        if (ack.ok) {
            ack.data?.let {
                runCatching {
                    ProtocolJson.decodeFromJsonElement(
                        ClipboardAckData.serializer(),
                        it,
                    )
                }.getOrNull()
            }
        } else {
            ClipboardAckData("", ClipboardValues.STATUS_REJECTED)
        }

    private fun data(
        clip: Clip,
        text: String?,
        transfer: ClipboardTransfer?,
    ): ClipboardPushData {
        val file = clip.content as? ClipContent.FileBacked
        return ClipboardPushData(
            clipId = clip.clipId,
            kind = clip.kind,
            mime = clip.content.mime,
            text = text,
            transfer = transfer,
            width = file?.width,
            height = file?.height,
            sensitive = clip.sensitive,
            originTs = clip.origin.originTs,
            source = clip.origin.source,
            originDeviceId = clip.origin.deviceId,
        )
    }

    private fun json(data: ClipboardAckData): JsonObject =
        ProtocolJson.encodeToJsonElement(ClipboardAckData.serializer(), data).jsonObject
}
