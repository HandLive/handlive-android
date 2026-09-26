package app.handlive.android.feature.clipboard.system

import android.content.ClipboardManager
import android.content.Context
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** CLIP-02 API 3 and CLIP-03 API 6: label "HandLive", `IS_SENSITIVE`, the `clipfiles` URI, the write trace. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SystemClipboardTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val clipboard = SystemClipboard(context)
    private val manager = context.getSystemService(ClipboardManager::class.java)

    @Test
    fun textIsWrittenWithTheHandLiveLabelAndTheSensitiveExtra() {
        val write = clipboard.writeText("clip-1", ByteArray(32), "secret", sensitive = true)
        val clip = checkNotNull(manager.primaryClip)
        assertEquals("secret", clip.getItemAt(0).text)
        assertEquals(SystemClipboard.LABEL, clip.description.label)
        assertTrue(SystemClipboard.isSensitive(clip.description))
        assertTrue(write.wallFrom <= write.wallTo)
        assertFalse(write.changed)
        assertEquals(SystemClipboard.LABEL, clipboard.describe()?.label)
        clipboard.writeText("clip-2", ByteArray(32), "plain", sensitive = false)
        assertFalse(SystemClipboard.isSensitive(manager.primaryClipDescription))
    }

    @Test
    fun filesArePastedThroughTheClipfilesProvider() {
        shadowOf(MimeTypeMap.getSingleton()).addExtensionMimeTypeMapping("png", "image/png")
        val file =
            File(context.cacheDir, "clip/clip-3.png")
                .apply {
                    parentFile?.mkdirs()
                }.apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val write = clipboard.writeFile("clip-3", ByteArray(32), file, sensitive = false)
        val uri = checkNotNull(manager.primaryClip).getItemAt(0).uri
        assertEquals("content", uri.scheme)
        assertEquals(SystemClipboard.authority(context), uri.authority)
        assertEquals("/clip/clip-3.png", uri.path)
        assertEquals(file, write.file)
    }

    @Test
    fun clearingEmptiesTheClipboard() {
        clipboard.writeText("clip-4", ByteArray(32), "x", sensitive = false)
        clipboard.clear()
        assertNull(manager.primaryClip)
    }
}
