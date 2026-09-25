package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.feature.clipboard.engine.ClipLimits
import java.io.File

/**
 * `cache/clip/` (CLIP-03): `<transfer_id>.part` while receiving, `<id>.src` for a copied local image, and
 * `<clip_id>.png|.jpg|.txt` for the clip HandLive put on the clipboard. The only directory the `clipfiles`
 * `FileProvider` exposes; nothing here survives as history (QC2).
 */
class ClipFiles(
    private val dir: File,
    private val wall: () -> Long,
) {
    fun part(transferId: String): File = File(ready(), "$transferId$PART")

    fun source(id: String): File = File(ready(), "$id.src")

    fun converted(id: String): File = File(ready(), "$id.png")

    /** The file a received clip is written from (CLIP-03 API 6); the extension gives `FileProvider` its MIME type. */
    fun clip(
        clipId: String,
        mime: String,
    ): File = File(ready(), "$clipId.${extension(mime)}")

    /** E9: room for [bytes] more in the cache directory. */
    fun hasRoomFor(bytes: Long): Boolean = ready().usableSpace > bytes

    /** At A-SVC start: no transfer survives a restart, and files older than 1 hour go (Android 13+ drops them too). */
    fun cleanUp() {
        val oldest = wall() - ClipLimits.FILE_MAX_AGE_MILLIS
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(PART) || file.lastModified() < oldest) file.delete()
        }
    }

    private fun ready(): File = dir.also { it.mkdirs() }

    private fun extension(mime: String) =
        when (mime) {
            ClipboardValues.MIME_PNG -> "png"
            ClipboardValues.MIME_JPEG -> "jpg"
            else -> "txt"
        }

    private companion object {
        const val PART = ".part"
    }
}
