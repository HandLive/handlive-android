package app.handlive.android.feature.clipboard.engine

import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream

/**
 * The chunks of one clip in order (CLIP-03 API 4 rule 4): from memory for text, from the clip's file otherwise, so
 * a 10 MiB image is never held in memory at once. Every chunk but the last is `CHUNK_SIZE` long.
 */
class ChunkSource private constructor(
    val size: Long,
    private val input: DataInputStream,
) : Closeable {
    val chunkCount: Int = ChunkPlan.chunkCount(size)
    private var sent = 0L

    fun next(): ByteArray {
        val length = minOf(ChunkPlan.CHUNK_SIZE.toLong(), size - sent).toInt()
        check(length > 0) { "no chunk left" }
        return ByteArray(length).also {
            input.readFully(it)
            sent += length
        }
    }

    override fun close() = input.close()

    companion object {
        /** Opens [content]; a file deleted since (auto-clear, cleanup) throws `IOException`. */
        fun open(content: ClipContent): ChunkSource =
            when (content) {
                is ClipContent.Text -> ChunkSource(content.size, DataInputStream(content.bytes.inputStream()))
                is ClipContent.FileBacked -> ChunkSource(content.size, DataInputStream(FileInputStream(content.file)))
            }

        fun sha256Of(file: File): ByteArray {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(ChunkPlan.CHUNK_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest()
        }
    }
}
