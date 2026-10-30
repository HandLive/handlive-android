package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.transport.EnvelopeLane
import app.handlive.android.feature.clipboard.engine.ChunkPlan
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.testing.ClipboardHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 0.4.7 "Bulk lane on the Bluetooth link": the clipboard engine picks `EnvelopeLane` on every send path (bulk rule
 * 2). `ClipboardHarness` runs on `PeerSession.Channel.LAN` where the lane argument is ignored on the wire (bulk
 * rule 1), but the engine must still choose the right one so a real Bluetooth session carries it correctly.
 */
class BulkLaneContractTest {
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
        push: ClipboardPushData,
        bytes: ByteArray,
    ) {
        val transferId = checkNotNull(push.transfer).transferId
        val count = ChunkPlan.chunkCount(bytes.size.toLong())
        (0 until count).forEach { index ->
            val from = index * ChunkPlan.CHUNK_SIZE
            h.chunk(h.mac, transferId, index, bytes.copyOfRange(from, minOf(bytes.size, from + ChunkPlan.CHUNK_SIZE)))
        }
    }

    @Test
    fun outgoingPushWithTransferAndItsChunksAreBulk() =
        test { h ->
            // Bulk rule 1: `clipboard/push` with `transfer`, and every `clipboard/chunk`.
            h.connect(h.mac)
            h.readImage(Random(1).nextBytes(1_500_000))
            val (pushId, _) = h.mac.pushes().single()
            assertEquals(EnvelopeLane.BULK, h.mac.laneOf(pushId))
            assertTrue(h.mac.chunkLanes().isNotEmpty())
            assertTrue(h.mac.chunkLanes().all { it == EnvelopeLane.BULK })
        }

    @Test
    fun outgoingInlineTextPushIsControl() =
        test { h ->
            // Bulk rule 1: an inline `clipboard/push` stays on control.
            h.connect(h.mac)
            h.readText("hello there")
            val (pushId, _) = h.mac.pushes().single()
            assertEquals(EnvelopeLane.CONTROL, h.mac.laneOf(pushId))
        }

    @Test
    fun aSupersededCancelOfARunningTransferIsBulk() =
        test { h ->
            h.connect(h.mac)
            h.readImage(Random(2).nextBytes(100_000))
            h.readText("newer text")
            assertEquals(listOf(EnvelopeLane.BULK), h.mac.cancelLanes())
        }

    @Test
    fun aUserCancelOfASendingTransferIsBulk() =
        test { h ->
            h.connect(h.mac)
            h.readImage(Random(3).nextBytes(100_000))
            val transferId = checkNotNull(h.mac.pushes().single().second.transfer).transferId
            h.module.cancelTransfer("pair-mac", transferId, sending = true)
            h.run()
            assertEquals(listOf(EnvelopeLane.BULK), h.mac.cancelLanes())
        }

    @Test
    fun aReceiverTimeoutCancelOfAnIncomingTransferIsBulk() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(4).nextBytes(200_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            testScheduler.advanceTimeBy(ClipLimits.TRANSFER_IDLE_MILLIS + 1)
            h.run()
            assertEquals(listOf(EnvelopeLane.BULK), h.mac.cancelLanes())
        }

    @Test
    fun ackOfAPushWithTransferIsBulkWhetherAppliedOrRejected() =
        test { h ->
            h.connect(h.mac)
            val bytes = Random(5).nextBytes(100_000)
            val push = imagePush(h, bytes)
            h.push(h.mac, push)
            sendChunks(h, push, bytes)
            assertEquals(listOf(EnvelopeLane.BULK), h.mac.ackLanes())

            val bad = imagePush(h, bytes, sha256 = ByteArray(32))
            h.push(h.mac, bad)
            sendChunks(h, bad, bytes)
            assertEquals(listOf(EnvelopeLane.BULK, EnvelopeLane.BULK), h.mac.ackLanes())
        }

    @Test
    fun ackOfAnInlinePushIsControl() =
        test { h ->
            h.connect(h.mac)
            h.push(h.mac, h.macText("hi"))
            assertEquals(listOf(EnvelopeLane.CONTROL), h.mac.ackLanes())
        }
}
