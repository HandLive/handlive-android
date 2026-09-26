package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.clipboard.ClipboardChunkPlaintext
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.encoding.Base64Codecs
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Chunk arithmetic of CLIP-03 API 3–4 (`CHUNK_SIZE` = 64 KiB before encryption). */
object ChunkPlan {
    const val CHUNK_SIZE = ClipboardChunkPlaintext.CHUNK_SIZE

    fun chunkCount(size: Long): Int = ((size + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    /** API 3 rule 1 (the size limit apart): `chunk_size` = 64 KiB, `chunk_count` consistent, a 32-byte SHA-256. */
    fun isWellFormed(transfer: ClipboardTransfer): Boolean =
        transfer.size >= 1 &&
            transfer.chunkSize == CHUNK_SIZE &&
            transfer.chunkCount == chunkCount(transfer.size) &&
            runCatching { Base64Codecs.decodeB64u(transfer.sha256, SHA256_SIZE) }.isSuccess

    const val SHA256_SIZE = 32
}

/**
 * One incoming chunked transfer (CLIP-03 receiver): chunks are written straight to [file] and hashed on the fly, so
 * the whole image never sits in memory; indexes must grow by exactly 1 from 0 (API 4 rule 2).
 */
class IncomingTransfer(
    val transfer: ClipboardTransfer,
    val file: File,
    private val clock: () -> Long,
) {
    sealed interface Step {
        data object More : Step

        class Complete(
            val verified: Boolean,
        ) : Step

        /** Wrong index or chunk length: the transfer is broken (`BAD_REQUEST`). */
        data object Broken : Step
    }

    /** The expected SHA-256 (`transfer.sha256` decoded). */
    val sha256: ByteArray = Base64Codecs.decodeB64u(transfer.sha256, ChunkPlan.SHA256_SIZE)
    private val lastChunkSize = transfer.size - (transfer.chunkCount - 1).toLong() * transfer.chunkSize
    private val digest = MessageDigest.getInstance("SHA-256")
    private val out = FileOutputStream(file)
    private var nextIndex = 0

    @Volatile
    var lastChunkAt: Long = clock()
        private set

    /** Every chunk arrived; the file is closed and kept for the clipboard write. */
    val isComplete: Boolean get() = nextIndex == transfer.chunkCount

    /** Writes one chunk; throws `IOException` when storage is full (E9). */
    fun accept(
        index: Int,
        bytes: ByteArray,
    ): Step {
        lastChunkAt = clock()
        val last = transfer.chunkCount - 1
        val expected = if (index == last) lastChunkSize else transfer.chunkSize.toLong()
        if (index != nextIndex || index > last || bytes.size.toLong() != expected) return Step.Broken
        out.write(bytes)
        digest.update(bytes)
        nextIndex++
        return if (nextIndex <= last) Step.More else complete()
    }

    private fun complete(): Step {
        out.close()
        return Step.Complete(digest.digest().contentEquals(sha256))
    }

    /** Cancelled, broken or timed out: the temporary file goes away (E5–E8). */
    fun discard() {
        runCatching { out.close() }
        file.delete()
    }
}
