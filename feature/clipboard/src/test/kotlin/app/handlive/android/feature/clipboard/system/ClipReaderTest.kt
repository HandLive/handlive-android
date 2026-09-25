package app.handlive.android.feature.clipboard.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/** CLIP-01 API 2 logic 2 and CLIP-03 API 1: what item 0 of a clip becomes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipReaderTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "clip")
    private val files = ClipFiles(dir, System::currentTimeMillis)
    private val imageUri = Uri.parse("content://com.example.photos/image/1")

    private fun read(clip: ClipData?) = ClipReader.read(context, clip, "manual", files)

    @Test
    fun textAndHtmlBecomePlainText() {
        assertEquals(
            "Order number: HL-240917-0042",
            (read(ClipData.newPlainText("note", "Order number: HL-240917-0042")) as LocalRead.Text).text,
        )
        assertEquals("bold", (read(ClipData.newHtmlText("web", "bold", "<b>bold</b>")) as LocalRead.Text).text)
    }

    @Test
    fun theSensitiveExtraIsReadOnEveryVersion() {
        val clip = ClipData.newPlainText("password", "hunter2")
        clip.description.extras = PersistableBundle().apply { putBoolean(SystemClipboard.EXTRA_IS_SENSITIVE, true) }
        assertTrue((read(clip) as LocalRead.Text).sensitiveExtra)
        assertFalse((read(ClipData.newPlainText("note", "x")) as LocalRead.Text).sensitiveExtra)
    }

    @Test
    fun emptyClipsAndOtherTypesAreE3() {
        assertEquals(ReadFailure.EMPTY_OR_NOT_TEXT, (read(null) as LocalRead.Failed).reason)
        assertEquals(
            ReadFailure.EMPTY_OR_NOT_TEXT,
            (read(ClipData.newPlainText("note", "")) as LocalRead.Failed).reason,
        )
        val pdf =
            ClipData(
                ClipDescription("doc", arrayOf("application/pdf")),
                ClipData.Item(Uri.parse("content://com.example/doc.pdf")),
            )
        assertEquals(ReadFailure.EMPTY_OR_NOT_TEXT, (read(pdf) as LocalRead.Failed).reason)
    }

    @Test
    fun anImageIsCopiedIntoTheCacheRightAway() {
        val bytes = ByteArray(200_000) { it.toByte() }
        shadowOf(context.contentResolver).registerInputStream(imageUri, bytes.inputStream())
        val image = read(imageClip()) as LocalRead.Image
        assertEquals("image/png", image.mime)
        assertEquals(dir, image.file.parentFile)
        assertArrayEquals(bytes, image.file.readBytes())
    }

    @Test
    fun anImageOverTenMebibytesIsTooLargeAndLeavesNoFile() {
        val stream =
            object : InputStream() {
                private var left = ClipLimits.MAX_IMAGE_BYTES + 100

                override fun read(): Int = if (left-- > 0) 0 else -1
            }
        shadowOf(context.contentResolver).registerInputStream(imageUri, stream)
        assertEquals(ReadFailure.IMAGE_TOO_LARGE, (read(imageClip()) as LocalRead.Failed).reason)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aRevokedGrantIsSkippedSilently() {
        val stream =
            object : InputStream() {
                override fun read(): Int = throw SecurityException("grant revoked")
            }
        shadowOf(context.contentResolver).registerInputStream(imageUri, stream)
        assertEquals(ReadFailure.PERMISSION_LOST, (read(imageClip()) as LocalRead.Failed).reason)
    }

    private fun imageClip() = ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(imageUri))
}
