package app.handlive.android.feature.clipboard.system

import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Sensitive flag and read source (`auto` or `manual`) of one local clip. */
internal data class ClipOrigin(
    val sensitive: Boolean,
    val source: String,
)

/** CLIP-03 API 1: copy item 0's image into `cache/clip/` before the clipboard URI grant ends. */
internal object ClipImageCopier {
    fun describedMime(description: ClipDescription): String? =
        (0 until description.mimeTypeCount)
            .map(description::getMimeType)
            .firstOrNull { it.startsWith(IMAGE) }

    /**
     * The image, or null when [uri] is not one. `getType` and `openInputStream` run only after
     * [ClipUriPolicy.isReadable].
     */
    fun copyIfImage(
        context: Context,
        description: ClipDescription,
        uri: Uri,
        origin: ClipOrigin,
        files: ClipFiles,
    ): LocalRead? {
        val mime = imageMimeOf(context, description, uri) ?: return null
        val target = files.source(UUID.randomUUID().toString())
        val failure = copyImage(context, uri, target)
        if (failure != null) logCopyFailure(uri, origin.source, failure)
        return if (failure == null) {
            LocalRead.Image(target, mime, origin.sensitive, origin.source)
        } else {
            LocalRead.Failed(failure, origin.source)
        }
    }

    private fun imageMimeOf(
        context: Context,
        description: ClipDescription,
        uri: Uri,
    ): String? {
        if (!ClipUriPolicy.isReadable(context, uri)) return null
        val provided = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return provided?.takeIf { it.startsWith(IMAGE) } ?: describedMime(description)
    }

    /** Copies the image into [target]; a failure deletes it (E2, E3, E10). */
    private fun copyImage(
        context: Context,
        uri: Uri,
        target: File,
    ): ReadFailure? {
        if (!ClipUriPolicy.isReadable(context, uri)) {
            target.delete()
            return ReadFailure.IMAGE_UNREADABLE
        }
        val copied = runCatching { context.contentResolver.openInputStream(uri)?.use { copyLimited(it, target) } }
        val size = copied.getOrNull()
        val failure =
            when {
                copied.exceptionOrNull() is SecurityException -> ReadFailure.PERMISSION_LOST
                size == null -> ReadFailure.IMAGE_UNREADABLE
                size > ClipLimits.MAX_IMAGE_BYTES -> ReadFailure.IMAGE_TOO_LARGE
                else -> null
            }
        if (failure != null) target.delete()
        return failure
    }

    /** Copies at most `CLIP_MAX_IMAGE` + 1 bytes (logic 3: one byte more tells "too large"). */
    private fun copyLimited(
        input: InputStream,
        target: File,
    ): Long {
        val limit = ClipLimits.MAX_IMAGE_BYTES + 1
        var total = 0L
        target.outputStream().use { out ->
            val buffer = ByteArray(BUFFER)
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(BUFFER.toLong(), limit - total).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                total += read
            }
        }
        return total
    }

    private fun logCopyFailure(
        uri: Uri,
        source: String,
        failure: ReadFailure,
    ) {
        BenchLog.event(
            BenchEvent.CLIP_READ_FAILED,
            "reason" to failure.name.lowercase(),
            "stage" to "copy",
            "authority" to ClipUriPolicy.logAuthority(uri),
            "source" to source,
        )
    }

    private const val IMAGE = "image/"
    private const val BUFFER = 64 * 1024
}
