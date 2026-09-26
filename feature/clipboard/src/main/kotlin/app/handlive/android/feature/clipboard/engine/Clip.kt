package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.clipboard.ClipboardValues
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** What a clip carries (QC2: in memory, or in a temporary file under `cache/clip/`, never in a database). */
sealed interface ClipContent {
    val mime: String
    val size: Long
    val sha256: ByteArray

    class Text(
        val text: String,
    ) : ClipContent {
        val bytes: ByteArray = text.toByteArray(Charsets.UTF_8)
        override val mime = MIME_TEXT
        override val size = bytes.size.toLong()
        override val sha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }

    /** An image, or text too large for the binder, stored in a file (CLIP-03). */
    class FileBacked(
        val file: File,
        override val mime: String,
        override val size: Long,
        override val sha256: ByteArray,
        val width: Int? = null,
        val height: Int? = null,
    ) : ClipContent

    companion object {
        const val MIME_TEXT = ClipboardValues.MIME_TEXT
    }
}

/** Where a clip was created: `origin_device_id`, `source` and `origin_ts` of the push (origin device's clock). */
class ClipOrigin(
    val deviceId: String,
    val source: String,
    val originTs: Long,
)

/** A clip known to this phone: read locally or received from a client (QC6, QC7). */
class Clip(
    val clipId: String,
    val content: ClipContent,
    val sensitive: Boolean,
    val origin: ClipOrigin,
    /** Pair the clip came from, `null` when it was read on this phone. */
    val fromPairId: String?,
    /** When this phone learned about it, its own clock (QC8 a, QC7 staleness). */
    val changedAtMillis: Long,
) {
    /** Pairs that acknowledged it (`applied` or `ignored`); QC7 does not replay to them. */
    val deliveredTo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** QC7 replays suppress the conflict notification (CLIP-01 API 6 rule 3). */
    val replayedTo: MutableSet<String> = ConcurrentHashMap.newKeySet()

    val kind: String get() =
        if (content.mime ==
            ClipContent.MIME_TEXT
        ) {
            ClipboardValues.KIND_TEXT
        } else {
            ClipboardValues.KIND_IMAGE
        }

    /** Same content under a new `clip_id`, read again now, for "Send Again" (CLIP-01 API 6 logic 3). */
    fun resend(
        clipId: String,
        now: Long,
    ) = Clip(clipId, content, sensitive, ClipOrigin(origin.deviceId, origin.source, now), null, now)

    fun asLocalChange() =
        LocalChange(
            clipId = clipId,
            changedAtMillis = changedAtMillis,
            originTs = origin.originTs,
            originDeviceId = origin.deviceId,
            acknowledgedBy = deliveredTo + listOfNotNull(fromPairId),
        )
}
