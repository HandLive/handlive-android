package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.feature.clipboard.system.ClipLabel
import app.handlive.android.feature.clipboard.system.SystemClipboard
import app.handlive.android.feature.clipboard.testing.ClipboardHarness
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * CLIP-05 on Android and decision C17: a received clip is cleared at its deadline only when the clipboard provably
 * still holds HandLive's write; the user's own content is never cleared, whatever the timing or the signals.
 */
class AutoClearTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val minute = 60_000L

    private fun test(body: suspend TestScope.(ClipboardHarness) -> Unit) =
        runTest {
            val h = ClipboardHarness(this, folder.root)
            h.connect(h.mac)
            body(h)
        }

    private suspend fun TestScope.receive(
        h: ClipboardHarness,
        text: String = "from the Mac",
    ) {
        h.push(h.mac, h.macText(text))
        testScheduler.runCurrent()
    }

    private fun TestScope.advance(millis: Long) {
        testScheduler.advanceTimeBy(millis)
        testScheduler.runCurrent()
    }

    @Test
    fun anUnchangedClipIsClearedAtTheDeadlineWhenTheAppHasFocus() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            advance(minute - 1)
            assertEquals(0, h.writer.cleared)
            advance(1)
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun theUsersOwnCopyIsNeverCleared() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            // The user copies elsewhere with no signal reaching HandLive: the description gives it away.
            advance(10_000)
            h.writer.userCopies()
            advance(minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun aHandLiveLabelWithAnotherTimestampIsNotHandLivesClip() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            h.writer.current = ClipLabel(SystemClipboard.LABEL, h.wall() + 5)
            advance(minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun aCopySignalAfterTheFirstSecondDropsTheTimer() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            advance(1_000)
            assertTrue(h.module.acceptCopySignal())
            advance(minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun theOverlaySignalOfHandLivesOwnWriteIsIgnored() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            advance(500)
            assertFalse(h.module.acceptCopySignal())
            advance(minute)
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun sendingALocalClipOrReadingOtherContentIsASignOfChange() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            advance(6_000)
            h.readText("copied on the phone", source = ClipboardValues.SOURCE_MANUAL)
            advance(minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun readingTheSameContentBackIsNotASignOfChange() =
        test { h ->
            h.focus.appFocused = true
            receive(h, "same")
            h.readText("same", source = ClipboardValues.SOURCE_MANUAL)
            advance(minute)
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun withoutFocusOrAccessibilityTheCheckWaitsForTheApp() =
        test { h ->
            receive(h)
            advance(minute)
            assertEquals(0, h.writer.cleared)
            assertEquals(0, h.focus.verificationsStarted)
            h.focus.appFocused = true
            h.module.onAppFocused()
            h.run()
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun aPostponedCheckFindsTheUsersCopyAndLeavesIt() =
        test { h ->
            receive(h)
            advance(minute)
            h.writer.userCopies()
            h.focus.appFocused = true
            h.module.onAppFocused()
            h.run()
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun withAccessibilityTheActivityVerifiesTheDescription() =
        test { h ->
            h.focus.accessibilityRunning = true
            receive(h)
            advance(minute)
            assertEquals(1, h.focus.verificationsStarted)
            h.module.trace.deliverVerification(h.writer.describe())
            h.run()
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun anActivityWithoutFocusPostponesTheCheck() =
        test { h ->
            h.focus.accessibilityRunning = true
            receive(h)
            advance(minute)
            h.module.trace.deliverVerification(null)
            h.run()
            assertEquals(0, h.writer.cleared)
            h.focus.appFocused = true
            h.module.onAppFocused()
            h.run()
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun aNewClipReplacesTheTimer() =
        test { h ->
            h.focus.appFocused = true
            receive(h, "first")
            advance(30_000)
            receive(h, "second")
            advance(30_000)
            assertEquals(0, h.writer.cleared)
            advance(30_000)
            assertEquals(1, h.writer.cleared)
        }

    @Test
    fun changingTheSettingRecomputesTheDeadlineAndOffCancelsIt() =
        test { h ->
            h.focus.appFocused = true
            receive(h)
            h.settings.value = h.settings.value.copy(clipAutoClearSeconds = 300)
            advance(minute)
            assertEquals(0, h.writer.cleared)
            h.settings.value = h.settings.value.copy(clipAutoClearSeconds = 0)
            advance(5 * minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun theInAppListenerIgnoresHandLivesWriteButNotTheUsers() =
        test { h ->
            receive(h)
            assertFalse(h.module.onClipboardChanged(h.writer.describe()))
            h.writer.userCopies()
            assertTrue(h.module.onClipboardChanged(h.writer.describe()))
            h.run()
            h.focus.appFocused = true
            advance(minute)
            assertEquals(0, h.writer.cleared)
        }

    @Test
    fun aClearedImageLosesItsFileAndIsNotReplayed() =
        test { h ->
            h.focus.appFocused = true
            val image =
                h.macText("x").copy(
                    kind = ClipboardValues.KIND_IMAGE,
                    mime = ClipboardValues.MIME_PNG,
                    text = null,
                    transfer =
                        app.handlive.android.core.protocol.clipboard.ClipboardTransfer(
                            transferId = h.newId(),
                            size = 3,
                            sha256 = "A5BYxvLAy0ksUzsKTRTvd8wPeKvMztUofYShogEc-4E",
                            chunkSize = 65_536,
                            chunkCount = 1,
                        ),
                )
            h.push(h.mac, image)
            h.chunk(h.mac, checkNotNull(image.transfer).transferId, 0, byteArrayOf(1, 2, 3))
            val file =
                checkNotNull(
                    h.writer.writes
                        .single()
                        .file,
                )
            assertTrue(file.exists())
            advance(minute)
            assertEquals(1, h.writer.cleared)
            assertFalse(file.exists())
            h.connect(h.ipad)
            assertTrue(h.ipad.pushes().isEmpty())
        }
}
