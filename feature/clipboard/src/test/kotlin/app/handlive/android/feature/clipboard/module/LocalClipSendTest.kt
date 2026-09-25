package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.feature.clipboard.ClipMessage
import app.handlive.android.feature.clipboard.engine.ChunkPlan
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.testing.ClipboardHarness
import app.handlive.android.feature.clipboard.testing.PHONE_ID
import app.handlive.android.feature.clipboard.testing.clientCapability
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CLIP-01 on the phone (sender side): E1–E10, with QC1 targeting, QC5 chunking and QC7 replay. */
class LocalClipSendTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun test(body: suspend TestScope.(ClipboardHarness) -> Unit) =
        runTest {
            body(ClipboardHarness(this, folder.root))
        }

    @Test
    fun aCopiedTextGoesToEveryClientWithActiveClipboard() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.readText("Order number: HL-240917-0042")
            val (_, push) = h.mac.pushes().single()
            assertEquals(
                push.clipId,
                h.ipad
                    .pushes()
                    .single()
                    .second.clipId,
            )
            assertEquals(ClipboardValues.KIND_TEXT, push.kind)
            assertEquals("Order number: HL-240917-0042", push.text)
            assertEquals(ClipboardValues.SOURCE_AUTO, push.source)
            assertEquals(PHONE_ID, push.originDeviceId)
            assertEquals(false, push.sensitive)
            assertEquals(h.wall(), push.originTs)
        }

    @Test
    fun theManualPathSaysSentToTheFirstDeviceThatApplied() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.readText("hello", source = ClipboardValues.SOURCE_MANUAL)
            val (macId, push) = h.mac.pushes().single()
            h.ack(h.mac, macId, push.clipId)
            h.ack(
                h.ipad,
                h.ipad
                    .pushes()
                    .single()
                    .first,
                push.clipId,
            )
            val sent = h.notices.messages.filterIsInstance<ClipMessage.SentTo>()
            assertEquals(listOf("MacBook của Lan"), sent.map { it.deviceName })
        }

    @Test
    fun anEmptyOrUnreadableClipIsReportedOnlyOnTheManualPath() =
        test { h ->
            h.module.onLocalRead(LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, ClipboardValues.SOURCE_AUTO))
            h.module.onLocalRead(LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, ClipboardValues.SOURCE_MANUAL))
            h.module.onLocalRead(LocalRead.Failed(ReadFailure.PERMISSION_LOST, ClipboardValues.SOURCE_MANUAL))
            h.module.onLocalRead(LocalRead.Failed(ReadFailure.IMAGE_TOO_LARGE, ClipboardValues.SOURCE_AUTO))
            h.run()
            assertEquals(listOf(ClipMessage.EmptyOrNotText, ClipMessage.ImageTooLarge), h.notices.messages)
        }

    @Test
    fun sensitiveContentIsHeldUntilSendAnywayForAtMostTwoMinutes() =
        test { h ->
            h.connect(h.mac)
            h.readText("secret", sensitiveExtra = true)
            h.readText("Thẻ 4111 1111 1111 1111")
            assertTrue(h.mac.pushes().isEmpty())
            assertEquals(2, h.notices.sensitiveBlocked)
            h.module.sendAnyway()
            h.run()
            val push =
                h.mac
                    .pushes()
                    .single()
                    .second
            assertEquals("Thẻ 4111 1111 1111 1111", push.text)
            assertTrue(push.sensitive)
            h.readText("secret again", sensitiveExtra = true)
            testScheduler.advanceTimeBy(ClipLimits.STALE_AFTER_MILLIS + 1)
            h.module.sendAnyway()
            h.run()
            assertEquals(1, h.mac.pushes().size)
        }

    @Test
    fun blockSensitiveOffSendsCardNumbers() =
        test { h ->
            h.connect(h.mac)
            h.settings.value = h.settings.value.copy(clipBlockSensitive = false)
            h.readText("4111 1111 1111 1111")
            assertEquals(
                false,
                h.mac
                    .pushes()
                    .single()
                    .second.sensitive,
            )
        }

    @Test
    fun textOverOneMebibyteIsTooLarge() =
        test { h ->
            h.connect(h.mac)
            h.readText("a".repeat(ClipLimits.MAX_TEXT_BYTES.toInt() + 1), source = ClipboardValues.SOURCE_MANUAL)
            assertTrue(h.mac.pushes().isEmpty())
            assertEquals(listOf(ClipMessage.TextTooLarge), h.notices.messages)
        }

    @Test
    fun withoutClientsTheClipIsKeptAndReplayedWithinTwoMinutes() =
        test { h ->
            h.readText("offline copy", source = ClipboardValues.SOURCE_MANUAL)
            assertEquals(listOf(ClipMessage.NotConnected), h.notices.messages)
            testScheduler.advanceTimeBy(ClipLimits.STALE_AFTER_MILLIS - 1_000)
            h.connect(h.mac)
            assertEquals(
                "offline copy",
                h.mac
                    .pushes()
                    .single()
                    .second.text,
            )
        }

    @Test
    fun aStaleClipIsNotReplayed() =
        test { h ->
            h.readText("old copy")
            testScheduler.advanceTimeBy(ClipLimits.STALE_AFTER_MILLIS + 1)
            h.connect(h.mac)
            assertTrue(h.mac.pushes().isEmpty())
        }

    @Test
    fun noAckIsNotRetriedButReplayedOnTheNextSessionAckedClipsAreNot() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.readText("clip")
            val clipId =
                h.mac
                    .pushes()
                    .single()
                    .second.clipId
            h.ack(
                h.ipad,
                h.ipad
                    .pushes()
                    .single()
                    .first,
                clipId,
            )
            testScheduler.advanceTimeBy(10_001)
            h.run()
            assertEquals(1, h.mac.pushes().size)
            h.disconnect(h.mac)
            h.disconnect(h.ipad)
            h.connect(h.mac, h.ipad)
            assertEquals(listOf(clipId, clipId), h.mac.pushes().map { it.second.clipId })
            assertEquals(1, h.ipad.pushes().size)
        }

    @Test
    fun theAutomaticPathSkipsTheClipSentWithinFiveSecondsTheManualPathDoesNot() =
        test { h ->
            h.connect(h.mac)
            h.readText("same")
            h.readText("same")
            assertEquals(1, h.mac.pushes().size)
            h.readText("same", source = ClipboardValues.SOURCE_MANUAL)
            assertEquals(2, h.mac.pushes().size)
            testScheduler.advanceTimeBy(ClipLimits.LOOP_WINDOW_MILLIS + 1)
            h.readText("same")
            assertEquals(3, h.mac.pushes().size)
        }

    @Test
    fun theClipJustReceivedIsNotSentBack() =
        test { h ->
            h.connect(h.mac)
            h.push(h.mac, h.macText("from the Mac"))
            h.readText("from the Mac", source = ClipboardValues.SOURCE_MANUAL)
            assertTrue(h.mac.pushes().isEmpty())
        }

    @Test
    fun featureDisabledPausesThePairUntilItsNextCapabilityUpdate() =
        test { h ->
            h.connect(h.mac)
            h.readText("first", source = ClipboardValues.SOURCE_MANUAL)
            val (pushId, push) = h.mac.pushes().single()
            h.ackError(h.mac, pushId, push.clipId, ErrorCode.FEATURE_DISABLED)
            assertTrue(h.notices.messages.any { it is ClipMessage.FeatureDisabled })
            h.readText("second")
            assertEquals(1, h.mac.pushes().size)
            h.mac.capability.value = clientCapability(appVersion = "1.0.1 (2)")
            h.readText("third")
            assertEquals(
                "third",
                h.mac
                    .pushes()
                    .last()
                    .second.text,
            )
        }

    @Test
    fun peerMimesAndLimitsChooseTheTargets() =
        test { h ->
            h.mac.capability.value = clientCapability(mimes = listOf(ClipboardValues.MIME_TEXT), maxTextBytes = 10)
            h.connect(h.mac, h.ipad)
            h.readText("longer than ten bytes")
            h.readImage(ByteArray(1_000) { it.toByte() })
            assertTrue(h.mac.pushes().isEmpty())
            assertEquals(2, h.ipad.pushes().size)
        }

    @Test
    fun textAboveTheInlineLimitGoesInChunksAndTheAckWaitStartsAfterTheLastOne() =
        test { h ->
            h.connect(h.mac)
            val text = "x".repeat(200 * 1024)
            h.readText(text)
            val (pushId, push) = h.mac.pushes().single()
            val transfer = checkNotNull(push.transfer)
            assertEquals(null, push.text)
            assertEquals(ChunkPlan.chunkCount(text.length.toLong()), transfer.chunkCount)
            val chunks = h.mac.chunks()
            assertEquals((0 until transfer.chunkCount).toList(), chunks.map { it.index })
            assertTrue(chunks.all { it.transferId == transfer.transferId })
            assertEquals(text, chunks.map { it.chunk }.reduce(ByteArray::plus).toString(Charsets.UTF_8))
            h.ack(h.mac, pushId, push.clipId)
            h.disconnect(h.mac)
            h.connect(h.mac)
            assertEquals(1, h.mac.pushes().size)
        }

    @Test
    fun sendAgainResendsTheSameContentAsANewClip() =
        test { h ->
            h.connect(h.mac)
            h.readText("mine")
            val first =
                h.mac
                    .pushes()
                    .single()
                    .second
            h.conflict(h.mac, conflictFor(first.clipId))
            assertEquals(listOf("MacBook của Lan" to first.clipId), h.notices.conflicts)
            h.module.sendAgain(first.clipId)
            h.run()
            val again =
                h.mac
                    .pushes()
                    .last()
                    .second
            assertEquals("mine", again.text)
            assertTrue(again.clipId != first.clipId)
        }

    @Test
    fun aConflictAboutAReplayedClipIsNotShown() =
        test { h ->
            h.readText("offline")
            h.connect(h.mac)
            val replayed =
                h.mac
                    .pushes()
                    .single()
                    .second
            h.conflict(h.mac, conflictFor(replayed.clipId))
            assertTrue(h.notices.conflicts.isEmpty())
        }

    private fun conflictFor(clipId: String) =
        app.handlive.android.core.protocol.clipboard.ClipboardConflictData(
            clipId = clipId,
            originDeviceId = PHONE_ID,
            deviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
            deviceName = "MacBook của Lan",
        )
}
