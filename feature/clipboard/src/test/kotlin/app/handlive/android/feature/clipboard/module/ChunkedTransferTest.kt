package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.engine.ChunkPlan
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.testing.ClipboardHarness
import app.handlive.android.feature.clipboard.testing.FakeClient
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/** CLIP-03 on the phone, both directions: chunks, SHA-256, progress, E4–E9. */
class ChunkedTransferTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun test(body: suspend TestScope.(ClipboardHarness) -> Unit) =
        runTest {
            body(ClipboardHarness(this, folder.root))
        }

    private fun imagePush(
        h: ClipboardHarness,
        bytes: ByteArray,
        sha256: ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes),
        clipId: String = h.newId(),
    ) = ClipboardPushData(
        clipId = clipId,
        kind = ClipboardValues.KIND_IMAGE,
        mime = ClipboardValues.MIME_PNG,
        transfer =
            ClipboardTransfer(
                transferId = h.newId(),
                size = bytes.size.toLong(),
                sha256 = Base64Codecs.encodeB64u(sha256),
                chunkSize = ChunkPlan.CHUNK_SIZE,
                chunkCount = ChunkPlan.chunkCount(bytes.size.toLong()),
            ),
        width = 2880,
        height = 1800,
        sensitive = false,
        originTs = h.wall(),
        source = "mac",
        originDeviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
    )

    private suspend fun sendChunks(
        h: ClipboardHarness,
        client: FakeClient,
        push: ClipboardPushData,
        bytes: ByteArray,
        count: Int = ChunkPlan.chunkCount(bytes.size.toLong()),
    ) {
        val transferId = checkNotNull(push.transfer).transferId
        (0 until count).forEach { index ->
            val from = index * ChunkPlan.CHUNK_SIZE
            h.chunk(client, transferId, index, bytes.copyOfRange(from, minOf(bytes.size, from + ChunkPlan.CHUNK_SIZE)))
        }
    }

    @Test
    fun aChunkedImageIsWrittenFromItsFileAndForwardedAsANewTransfer() =
        test { h ->
            h.connect(h.mac, h.ipad)
            val bytes = Random(1).nextBytes(1_500_000)
            val push = imagePush(h, bytes)
            val pushId = h.push(h.mac, push)
            assertTrue(h.mac.acks().isEmpty())
            sendChunks(h, h.mac, push, bytes)
            val written =
                checkNotNull(
                    h.writer.writes
                        .single()
                        .file,
                )
            assertEquals("${push.clipId}.png", written.name)
            assertArrayEquals(bytes, written.readBytes())
            assertEquals(
                pushId,
                h.mac
                    .acks()
                    .single()
                    .re,
            )
            assertEquals(
                ClipboardValues.STATUS_APPLIED,
                h.mac
                    .ackData()
                    .single()
                    .status,
            )
            // Progress for images over 1 MiB, removed at the end.
            val progress = h.notices.progress.filter { !it.sending }
            assertEquals(0, progress.first().percent)
            assertEquals(null, progress.last().percent)
            // Forwarded with the same clip and a new transfer_id; chunks read back from the verified file.
            val forwarded =
                h.ipad
                    .pushes()
                    .single()
                    .second
            assertEquals(push.clipId, forwarded.clipId)
            assertNotEquals(push.transfer?.transferId, forwarded.transfer?.transferId)
            assertArrayEquals(
                bytes,
                h.ipad
                    .chunks()
                    .map { it.chunk }
                    .reduce(ByteArray::plus),
            )
        }

    @Test
    fun aWrongHashIsReportedWithTheTransferAndTheFileIsDeleted() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(2).nextBytes(70_000)
            val push = imagePush(h, bytes, sha256 = ByteArray(32))
            h.push(h.mac, push)
            sendChunks(h, h.mac, push, bytes)
            val ack = h.mac.acks().single()
            assertEquals(ErrorCode.CLIP_CHECKSUM_MISMATCH, ack.error?.errorCode)
            assertEquals(
                push.transfer?.transferId,
                h.mac
                    .ackData()
                    .single()
                    .transferId,
            )
            assertTrue(h.writer.writes.isEmpty())
            assertTrue(
                h.clipDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
            // The same clip_id with a new transfer is accepted (CLIP-01 API 5 logic 6).
            val again = imagePush(h, bytes, clipId = push.clipId)
            h.push(h.mac, again)
            sendChunks(h, h.mac, again, bytes)
            assertEquals(1, h.writer.writes.size)
        }

    @Test
    fun theSenderResendsOnceAfterAMismatchThenGivesUp() =
        test { h ->
            h.connect(h.mac)
            h.readImage(Random(3).nextBytes(100_000))
            val (firstId, first) = h.mac.pushes().single()
            h.ackError(h.mac, firstId, first.clipId, ErrorCode.CLIP_CHECKSUM_MISMATCH, first.transfer?.transferId)
            val (secondId, second) = h.mac.pushes().last()
            assertEquals(first.clipId, second.clipId)
            assertNotEquals(first.transfer?.transferId, second.transfer?.transferId)
            h.ackError(h.mac, secondId, second.clipId, ErrorCode.CLIP_CHECKSUM_MISMATCH, second.transfer?.transferId)
            assertEquals(2, h.mac.pushes().size)
            assertEquals(listOf(ClipMessage.ImageSendFailed), h.notices.messages)
        }

    @Test
    fun aNewerClipCancelsTheRunningTransferAsSuperseded() =
        test { h ->
            h.connect(h.mac)
            h.readImage(Random(4).nextBytes(100_000))
            val transferId =
                checkNotNull(
                    h.mac
                        .pushes()
                        .single()
                        .second.transfer,
                ).transferId
            h.readText("newer text")
            assertEquals(
                listOf(transferId to ClipboardValues.CANCEL_SUPERSEDED),
                h.mac.cancels().map {
                    it.transferId to
                        it.reason
                },
            )
            assertEquals(
                "newer text",
                h.mac
                    .pushes()
                    .last()
                    .second.text,
            )
        }

    @Test
    fun cancelOnEitherSideStopsTheTransfer() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(5).nextBytes(200_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            val transferId = checkNotNull(push.transfer).transferId
            h.chunk(h.mac, transferId, 0, bytes.copyOfRange(0, ChunkPlan.CHUNK_SIZE))
            h.module.cancelTransfer("pair-mac", transferId, sending = false)
            h.run()
            assertEquals(
                ClipboardValues.CANCEL_USER,
                h.mac
                    .cancels()
                    .single()
                    .reason,
            )
            assertEquals(
                "ignored" to "cancelled",
                h.mac
                    .ackData()
                    .single()
                    .let { it.status to it.reason },
            )
            assertTrue(
                h.clipDir
                    .listFiles()
                    .orEmpty()
                    .none { it.name.endsWith(".part") },
            )
            // The sender cancels: the running transfer is dropped with ignored/cancelled.
            val next = imagePush(h, bytes)
            h.push(h.mac, next)
            h.cancel(h.mac, checkNotNull(next.transfer).transferId, ClipboardValues.CANCEL_SUPERSEDED)
            assertEquals(2, h.mac.ackData().count { it.reason == "cancelled" })
            assertTrue(h.writer.writes.isEmpty())
        }

    @Test
    fun cancelOnTheSendingProgressStopsTheTransferAndTheClipIsNotReplayed() =
        test { h ->
            h.connect(h.mac)
            h.readImage(Random(11).nextBytes(100_000))
            val transferId =
                checkNotNull(
                    h.mac
                        .pushes()
                        .single()
                        .second.transfer,
                ).transferId
            h.module.cancelTransfer("pair-mac", transferId, sending = true)
            h.run()
            assertEquals(
                listOf(transferId to ClipboardValues.CANCEL_USER),
                h.mac.cancels().map {
                    it.transferId to
                        it.reason
                },
            )
            h.disconnect(h.mac)
            h.connect(h.mac)
            assertEquals(1, h.mac.pushes().size)
        }

    @Test
    fun theReceiverCancelsAfterThirtySecondsWithoutAChunk() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(6).nextBytes(200_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            sendChunks(h, h.mac, push, bytes, count = 1)
            testScheduler.advanceTimeBy(ClipLimits.TRANSFER_IDLE_MILLIS - 1)
            h.run()
            assertTrue(h.mac.cancels().isEmpty())
            testScheduler.advanceTimeBy(2)
            h.run()
            assertEquals(
                ClipboardValues.CANCEL_TIMEOUT,
                h.mac
                    .cancels()
                    .single()
                    .reason,
            )
            assertEquals(
                "cancelled",
                h.mac
                    .ackData()
                    .single()
                    .reason,
            )
            assertTrue(
                h.clipDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun aClosedSessionDropsTheTransferWithoutAnAck() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(7).nextBytes(200_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            sendChunks(h, h.mac, push, bytes, count = 1)
            h.disconnect(h.mac)
            assertTrue(h.mac.acks().isEmpty())
            assertTrue(
                h.clipDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun chunksOutOfOrderAreBadRequest() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(8).nextBytes(200_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            h.chunk(h.mac, checkNotNull(push.transfer).transferId, 1, bytes.copyOfRange(0, ChunkPlan.CHUNK_SIZE))
            assertEquals(
                ErrorCode.BAD_REQUEST,
                h.mac
                    .acks()
                    .single()
                    .error
                    ?.errorCode,
            )
            assertTrue(
                h.clipDir
                    .listFiles()
                    .orEmpty()
                    .isEmpty(),
            )
        }

    @Test
    fun noStorageIsInternalWithTheNoSpaceMessage() =
        test { h ->
            h.connect(h.mac)
            h.freeSpace = 1_000
            h.push(h.mac, imagePush(h, Random(9).nextBytes(200_000)))
            assertEquals(
                ErrorCode.INTERNAL,
                h.mac
                    .acks()
                    .single()
                    .error
                    ?.errorCode,
            )
            assertEquals(listOf(ClipMessage.ImageNoSpace), h.notices.messages)
        }

    @Test
    fun chunkedTextIsWrittenAsATextFileAndInvalidUtf8IsRefused() =
        test { h ->
            h.connect(h.mac)
            val text = "Bảng nhớ tạm ".repeat(16_000).toByteArray()
            val push =
                imagePush(
                    h,
                    text,
                ).copy(kind = ClipboardValues.KIND_TEXT, mime = ClipboardValues.MIME_TEXT, width = null, height = null)
            h.push(h.mac, push)
            sendChunks(h, h.mac, push, text)
            val file =
                checkNotNull(
                    h.writer.writes
                        .single()
                        .file,
                )
            assertEquals("${push.clipId}.txt", file.name)
            val broken = byteArrayOf(0xC3.toByte(), 0x28) + ByteArray(200_000) { 'a'.code.toByte() }
            val bad =
                imagePush(
                    h,
                    broken,
                ).copy(kind = ClipboardValues.KIND_TEXT, mime = ClipboardValues.MIME_TEXT, width = null, height = null)
            h.push(h.mac, bad)
            sendChunks(h, h.mac, bad, broken)
            assertEquals(
                ErrorCode.BAD_REQUEST,
                h.mac
                    .acks()
                    .last()
                    .error
                    ?.errorCode,
            )
            assertFalse(h.clipDir.resolve("${bad.clipId}.txt").exists())
        }

    @Test
    fun aNewPushFromTheSamePeerReplacesItsRunningTransfer() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(10).nextBytes(200_000)
            val first = imagePush(h, bytes)
            h.push(h.mac, first)
            val second = imagePush(h, bytes)
            h.push(h.mac, second)
            assertEquals(
                "cancelled",
                h.mac
                    .ackData()
                    .single()
                    .reason,
            )
            sendChunks(h, h.mac, second, bytes)
            assertEquals(
                second.clipId,
                h.writer.writes
                    .single()
                    .clipId,
            )
        }
}
