package app.handlive.android.feature.clipboard.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import java.io.File
import java.io.InputStream
import java.util.UUID

/**
 * CLIP-01 API 2 logic 2 and CLIP-03 API 1: item 0 of a clip becomes plain text (`coerceToText`: HTML gives its
 * text, a text URI is read by the system) or an image copied into `cache/clip/` right away — the clipboard's URI
 * grant ends when the clip changes. Anything else is E3. Runs off the main thread; the content is never logged.
 */
object ClipReader {
    fun read(
        context: Context,
        clip: ClipData?,
        source: String,
        files: ClipFiles,
    ): LocalRead {
        val item =
            clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                ?: return LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, source)
        val sensitive = SystemClipboard.isSensitive(clip.description)
        val uri = item.uri
        val imageMime = uri?.let { imageMimeOf(context, clip.description, it) }
        return when {
            item.text != null || item.htmlText != null -> {
                text(item.coerceToText(context), sensitive, source)
            }

            uri != null && imageMime != null -> {
                val target = files.source(UUID.randomUUID().toString())
                copyImage(context, uri, target)?.let { LocalRead.Failed(it, source) }
                    ?: LocalRead.Image(target, imageMime, sensitive, source)
            }

            uri != null && clip.description.hasMimeType(TEXT_ANY) -> {
                text(item.coerceToText(context), sensitive, source)
            }

            else -> {
                LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, source)
            }
        }
    }

    private fun text(
        text: CharSequence?,
        sensitive: Boolean,
        source: String,
    ): LocalRead =
        text?.toString()?.takeIf { it.isNotEmpty() }?.let { LocalRead.Text(it, sensitive, source) }
            ?: LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, source)

    /** Logic 1: an image when the description or the provider gives an `image` MIME type. */
    private fun imageMimeOf(
        context: Context,
        description: ClipDescription,
        uri: Uri,
    ): String? {
        val provided = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        val described =
            (0 until description.mimeTypeCount)
                .map(
                    description::getMimeType,
                ).firstOrNull { it.startsWith(IMAGE) }
        return provided?.takeIf { it.startsWith(IMAGE) } ?: described
    }

    /** Copies the image into [target]; a failure deletes it (E2, E3, E10). */
    private fun copyImage(
        context: Context,
        uri: Uri,
        target: File,
    ): ReadFailure? {
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

    private const val IMAGE = "image/"
    private const val TEXT_ANY = "text/*"
    private const val BUFFER = 64 * 1024
}
