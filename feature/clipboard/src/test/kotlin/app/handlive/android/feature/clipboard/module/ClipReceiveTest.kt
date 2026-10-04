package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.clipboard.ClipboardConflictData
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.encoding.Base64Codecs
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.testing.ClipboardHarness
import app.handlive.android.feature.clipboard.testing.HTML_MIMES
import app.handlive.android.feature.clipboard.testing.IPAD_ID
import app.handlive.android.feature.clipboard.testing.MAC_ID
import app.handlive.android.feature.clipboard.testing.PHONE_ID
import app.handlive.android.feature.clipboard.testing.PHONE_NAME
import app.handlive.android.feature.clipboard.testing.clientCapability
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** CLIP-02 on the phone: checks, writes, `ack`s, forwarding (QC6) and conflicts (QC8, CLIP-01 API 6). */
class ClipReceiveTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun test(body: suspend TestScope.(ClipboardHarness) -> Unit) =
        runTest {
            body(ClipboardHarness(this, folder.root))
        }

    @Test
    fun aMacClipIsWrittenAppliedAndForwardedUnchanged() =
        test { h ->
            h.connect(h.mac, h.ipad)
            val push = h.macText("https://example.com/docs/q3-report").copy(sensitive = true)
            val pushId = h.push(h.mac, push)
            val written = h.writer.writes.single()
            assertEquals("https://example.com/docs/q3-report", written.text)
            assertTrue(written.sensitive)
            val ack = h.mac.acks().single()
            assertEquals(pushId, ack.re)
            assertEquals(
                ClipboardValues.STATUS_APPLIED,
                h.mac
                    .ackData()
                    .single()
                    .status,
            )
            assertEquals(listOf(push), h.ipad.pushes().map { it.second })
            assertTrue(h.mac.pushes().isEmpty())
        }

    @Test
    fun anHtmlPushIsSanitizedAgainWrittenWithItAndForwardedPerPeer() =
        test { h ->
            h.ipad.capability.value = clientCapability(mimes = HTML_MIMES)
            h.connect(h.mac, h.ipad)
            val push = h.macText("Hi").copy(html = "<p onclick=\"x\">Hi<script>y</script></p>")
            h.push(h.mac, push)
            val written = h.writer.writes.single()
            assertEquals("Hi", written.text)
            assertEquals("<p>Hi</p>", written.html)
            assertEquals(
                ClipboardValues.STATUS_APPLIED,
                h.mac
                    .ackData()
                    .single()
                    .status,
            )
            assertEquals(
                "<p>Hi</p>",
                h.ipad
                    .pushes()
                    .single()
                    .second.html,
            )
        }

    @Test
    fun forwardingDropsHtmlForAPeerWithoutTextHtml() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.push(h.mac, h.macText("Hi").copy(html = "<p>Hi</p>"))
            assertNull(
                h.ipad
                    .pushes()
                    .single()
                    .second.html,
            )
        }

    @Test
    fun htmlThatGrowsPastTheLimitWhenSanitizedIsDroppedAndTheTextIsStillApplied() =
        test { h ->
            h.connect(h.mac)
            // Under the limit as sent; every `"` in the href becomes `&quot;`, which takes it far over.
            val quotes = "\"".repeat(ClipLimits.MAX_HTML_BYTES / 2)
            val html = "<a href='https://e.com/$quotes'>t</a>"
            assertTrue(html.length < ClipLimits.MAX_HTML_BYTES)
            h.push(h.mac, h.macText("Hi").copy(html = html))
            val written = h.writer.writes.single()
            assertEquals("Hi", written.text)
            assertNull(written.html)
            val ack = h.mac.ackData().single()
            assertEquals(ClipboardValues.STATUS_APPLIED, ack.status)
        }

    @Test
    fun htmlWithATransferOrAnImageOrTooLongIsBadRequest() =
        test { h ->
            h.connect(h.mac)
            val transfer = ClipboardTransfer(h.newId(), 10, Base64Codecs.encodeB64u(ByteArray(32)), 10, 1)
            val withTransfer = h.macText("x").copy(text = null, transfer = transfer, html = "<p>x</p>")
            val asImage =
                withTransfer.copy(kind = ClipboardValues.KIND_IMAGE, mime = ClipboardValues.MIME_PNG)
            val tooLong = h.macText("x").copy(html = "y".repeat(ClipLimits.MAX_HTML_BYTES + 1))
            listOf(withTransfer, asImage, tooLong).forEach { h.push(h.mac, it) }
            assertEquals(
                List(3) { ErrorCode.BAD_REQUEST },
                h.mac.acks().map { it.error?.errorCode },
            )
            assertTrue(h.writer.writes.isEmpty())
        }

    @Test
    fun theSameContentAgainWithinFiveSecondsIsAppliedWithoutASecondWrite() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.push(h.mac, h.macText("https://example.com/echo"))
            h.push(h.mac, h.macText("https://example.com/echo"))
            assertEquals(1, h.writer.writes.size)
            assertEquals(
                listOf(ClipboardValues.STATUS_APPLIED, ClipboardValues.STATUS_APPLIED),
                h.mac.ackData().map { it.status },
            )
            assertEquals(1, h.ipad.pushes().size)
            advanceTimeBy(ClipLimits.LOOP_WINDOW_MILLIS + 1)
            h.push(h.mac, h.macText("https://example.com/echo"))
            assertEquals(2, h.writer.writes.size)
        }

    @Test
    fun aDuplicateClipIdIsIgnoredAndNotWrittenAgain() =
        test { h ->
            h.connect(h.mac)
            val push = h.macText("once")
            h.push(h.mac, push)
            h.push(h.mac, push)
            assertEquals(1, h.writer.writes.size)
            assertEquals(
                listOf("applied" to null, "ignored" to "duplicate"),
                h.mac.ackData().map {
                    it.status to
                        it.reason
                },
            )
        }

    @Test
    fun refusedPushesGetRejectedAcksAndAreNotForwarded() =
        test { h ->
            h.connect(h.mac, h.ipad)
            h.push(h.mac, h.macText("bad").copy(mime = ClipboardValues.MIME_PNG))
            h.push(h.mac, h.macText("a".repeat(1_048_577)))
            h.settings.value = h.settings.value.copy(clipSendImages = false)
            val image =
                h
                    .macText(
                        "x",
                    ).copy(
                        kind = ClipboardValues.KIND_IMAGE,
                        mime = ClipboardValues.MIME_PNG,
                        text = null,
                        transfer = transfer(100),
                    )
            h.push(h.mac, image)
            h.mac.effective.value = emptySet()
            h.push(h.mac, h.macText("off"))
            val codes = h.mac.acks().map { it.error?.errorCode }
            assertEquals(
                listOf(
                    ErrorCode.BAD_REQUEST,
                    ErrorCode.CLIP_TOO_LARGE,
                    ErrorCode.CLIP_UNSUPPORTED_MIME,
                    ErrorCode.FEATURE_DISABLED,
                ),
                codes,
            )
            assertTrue(h.mac.ackData().all { it.status == ClipboardValues.STATUS_REJECTED })
            assertTrue(h.writer.writes.isEmpty())
            assertTrue(h.ipad.pushes().isEmpty())
        }

    @Test
    fun aMalformedPushIsBadRequest() =
        test { h ->
            h.connect(h.mac)
            h.deliver(h.mac, MessageType.CLIPBOARD, """{"op":"push","data":{"clip_id":1}}""".toByteArray())
            assertEquals(
                ErrorCode.BAD_REQUEST,
                h.mac
                    .acks()
                    .single()
                    .error
                    ?.errorCode,
            )
        }

    @Test
    fun aFailedWriteIsInternalAndTheSameClipMayComeAgain() =
        test { h ->
            h.connect(h.mac, h.ipad)
            val push = h.macText("retry me")
            h.writer.failWrites = true
            h.push(h.mac, push)
            assertEquals(
                ErrorCode.INTERNAL,
                h.mac
                    .acks()
                    .single()
                    .error
                    ?.errorCode,
            )
            assertTrue(h.ipad.pushes().isEmpty())
            h.writer.failWrites = false
            h.push(h.mac, push)
            assertEquals(
                ClipboardValues.STATUS_APPLIED,
                h.mac
                    .ackData()
                    .last()
                    .status,
            )
        }

    @Test
    fun aLocalChangeWithinFiveHundredMillisecondsWinsAndTheSenderIsTold() =
        test { h ->
            h.connect(h.mac)
            h.readText("copied on the phone")
            testScheduler.advanceTimeBy(200)
            val push = h.macText("copied on the Mac")
            h.push(h.mac, push)
            assertTrue(h.writer.writes.isEmpty())
            assertEquals(
                "ignored" to "conflict",
                h.mac
                    .ackData()
                    .single()
                    .let { it.status to it.reason },
            )
            val conflict = h.mac.conflicts().single()
            assertEquals(ClipboardConflictData(push.clipId, MAC_ID, PHONE_ID, PHONE_NAME), conflict)
        }

    @Test
    fun crossingClipsKeepTheNewerOriginTsWithoutAConflictMessage() =
        test { h ->
            h.connect(h.mac)
            h.readText("phone clip")
            val phoneTs =
                h.mac
                    .pushes()
                    .single()
                    .second.originTs
            testScheduler.advanceTimeBy(2_000)
            h.push(h.mac, h.macText("older Mac clip", originTs = phoneTs - 1))
            assertTrue(h.writer.writes.isEmpty())
            assertTrue(h.mac.conflicts().isEmpty())
            h.push(h.mac, h.macText("newer Mac clip", originTs = phoneTs + 1))
            assertEquals(
                "newer Mac clip",
                h.writer.writes
                    .single()
                    .text,
            )
        }

    @Test
    fun aLocalChangeTheSenderAlreadyAcknowledgedIsNoConflict() =
        test { h ->
            h.connect(h.mac)
            h.readText("phone clip")
            val (pushId, push) = h.mac.pushes().single()
            h.ack(h.mac, pushId, push.clipId)
            h.push(h.mac, h.macText("Mac clip right after"))
            assertEquals(
                "Mac clip right after",
                h.writer.writes
                    .single()
                    .text,
            )
        }

    @Test
    fun aConflictAboutAForwardedClipGoesBackToItsOrigin() =
        test { h ->
            h.connect(h.mac, h.ipad)
            val push = h.macText("from the Mac")
            h.push(h.mac, push)
            val conflict = ClipboardConflictData(push.clipId, MAC_ID, IPAD_ID, "iPad của Lan")
            h.conflict(h.ipad, conflict)
            assertEquals(listOf(conflict), h.mac.conflicts())
            assertTrue(h.notices.conflicts.isEmpty())
        }

    @Test
    fun aConflictAboutAReplayedForwardedClipIsDropped() =
        test { h ->
            h.connect(h.mac)
            val push = h.macText("from the Mac")
            h.push(h.mac, push)
            h.connect(h.ipad)
            assertEquals(
                push.clipId,
                h.ipad
                    .pushes()
                    .single()
                    .second.clipId,
            )
            h.conflict(h.ipad, ClipboardConflictData(push.clipId, MAC_ID, IPAD_ID, "iPad của Lan"))
            assertTrue(h.mac.conflicts().isEmpty())
        }

    @Test
    fun aReceivedClipIsNotReplayedToItsOriginOrWhereItWasDelivered() =
        test { h ->
            h.connect(h.mac, h.ipad)
            val push = h.macText("from the Mac")
            h.push(h.mac, push)
            h.ack(
                h.ipad,
                h.ipad
                    .pushes()
                    .single()
                    .first,
                push.clipId,
            )
            h.disconnect(h.mac)
            h.disconnect(h.ipad)
            h.connect(h.mac, h.ipad)
            assertTrue(h.mac.pushes().isEmpty())
            assertEquals(1, h.ipad.pushes().size)
            assertTrue(
                h.mac.effective.value
                    .contains(Feature.CLIPBOARD),
            )
        }

    private fun transfer(size: Long) =
        app.handlive.android.core.protocol.clipboard.ClipboardTransfer(
            transferId = "0192f3f1-2c3e-7a10-9b20-c30d40e50f60",
            size = size,
            sha256 = "n4bQgYhMfWWaL-qgxVrQFaO_TxsrC4Is0V1sFbDwCgg",
            chunkSize = 65_536,
            chunkCount = 1,
        )
}
