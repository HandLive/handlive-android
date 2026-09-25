package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.encoding.Base64Codecs
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/** CLIP-03 API 3–4: 64 KiB chunking, in-order writes with an incremental SHA-256, and chunk reading. */
class TransfersTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val chunk = ChunkPlan.CHUNK_SIZE

    private fun transfer(
        bytes: ByteArray,
        sha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes),
    ) = ClipboardTransfer(
        "t-1",
        bytes.size.toLong(),
        Base64Codecs.encodeB64u(sha256),
        chunk,
        ChunkPlan.chunkCount(bytes.size.toLong()),
    )

    @Test
    fun chunkCountRoundsUp() {
        assertEquals(1, ChunkPlan.chunkCount(1))
        assertEquals(1, ChunkPlan.chunkCount(chunk.toLong()))
        assertEquals(2, ChunkPlan.chunkCount(chunk + 1L))
        // A 5 MiB image is 80 chunks (CLIP-03 performance note).
        assertEquals(80, ChunkPlan.chunkCount(5L * 1024 * 1024))
    }

    @Test
    fun wellFormedTransfersNeedTheSpecChunkSizeCountAndHash() {
        val good = transfer(ByteArray(chunk + 1))
        assertTrue(ChunkPlan.isWellFormed(good))
        assertFalse(ChunkPlan.isWellFormed(good.copy(chunkSize = 32_768)))
        assertFalse(ChunkPlan.isWellFormed(good.copy(chunkCount = 3)))
        assertFalse(ChunkPlan.isWellFormed(good.copy(sha256 = "AAAA")))
        assertFalse(ChunkPlan.isWellFormed(good.copy(size = 0, chunkCount = 0)))
    }

    @Test
    fun chunksInOrderRebuildTheFileAndVerifyTheHash() {
        val bytes = Random(7).nextBytes(2 * chunk + 100)
        val file = folder.newFile("t-1.part")
        val incoming = IncomingTransfer(transfer(bytes), file) { 0L }
        assertEquals(IncomingTransfer.Step.More, incoming.accept(0, bytes.copyOfRange(0, chunk)))
        assertEquals(IncomingTransfer.Step.More, incoming.accept(1, bytes.copyOfRange(chunk, 2 * chunk)))
        val done = incoming.accept(2, bytes.copyOfRange(2 * chunk, bytes.size))
        assertTrue((done as IncomingTransfer.Step.Complete).verified)
        assertTrue(incoming.isComplete)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun aWrongHashIsReportedAfterTheLastChunk() {
        val bytes = Random(8).nextBytes(100)
        val incoming = IncomingTransfer(transfer(bytes, sha256 = ByteArray(32)), folder.newFile("t-2.part")) { 0L }
        assertFalse((incoming.accept(0, bytes) as IncomingTransfer.Step.Complete).verified)
    }

    @Test
    fun chunksOutOfOrderOrOfTheWrongLengthBreakTheTransfer() {
        val bytes = Random(9).nextBytes(chunk + 10)
        val skipped = IncomingTransfer(transfer(bytes), folder.newFile("a.part")) { 0L }
        assertEquals(IncomingTransfer.Step.Broken, skipped.accept(1, bytes.copyOfRange(chunk, bytes.size)))
        val short = IncomingTransfer(transfer(bytes), folder.newFile("b.part")) { 0L }
        assertEquals(IncomingTransfer.Step.Broken, short.accept(0, bytes.copyOfRange(0, chunk - 1)))
        val file = folder.newFile("c.part")
        IncomingTransfer(transfer(bytes), file) { 0L }.discard()
        assertFalse(file.exists())
    }

    @Test
    fun chunkSourceSplitsTextAndFilesAlike() {
        val text = "Bảng nhớ tạm ".repeat(12_000)
        val content = ClipContent.Text(text)
        val fromText = ChunkSource.open(content).use { source -> List(source.chunkCount) { source.next() } }
        assertEquals(ChunkPlan.chunkCount(content.size), fromText.size)
        assertTrue(fromText.dropLast(1).all { it.size == chunk })
        assertArrayEquals(content.bytes, fromText.reduce(ByteArray::plus))
        val file = folder.newFile("clip.txt").apply { writeBytes(content.bytes) }
        assertArrayEquals(content.sha256, ChunkSource.sha256Of(file))
        val fileContent = ClipContent.FileBacked(file, ClipContent.MIME_TEXT, file.length(), content.sha256)
        val fromFile = ChunkSource.open(fileContent).use { source -> List(source.chunkCount) { source.next() } }
        assertArrayEquals(content.bytes, fromFile.reduce(ByteArray::plus))
    }
}
