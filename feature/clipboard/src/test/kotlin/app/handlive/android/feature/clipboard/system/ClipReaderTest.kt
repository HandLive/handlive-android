package app.handlive.android.feature.clipboard.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ProviderInfo
import android.net.Uri
import android.os.PersistableBundle
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.clipboard.engine.ClipLimits
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import app.handlive.android.feature.connection.bench.BenchLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
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

    @Before
    fun registerExternalGallery() {
        registerProvider(GALLERY_AUTHORITY, "com.example.photos")
    }

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
    fun anHtmlItemKeepsItsSanitizedHtmlBesideTheText() {
        val html = "<p onclick=\"x\">Hello <b>world</b></p><script>y</script>"
        val read = read(ClipData.newHtmlText("web", "Hello world", html)) as LocalRead.Text
        assertEquals("Hello world", read.text)
        assertEquals("<p>Hello <b>world</b></p>", read.html)
        assertNull((read(ClipData.newPlainText("note", "plain")) as LocalRead.Text).html)
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
    fun aRevokedGrantIsAPermissionLostFailure() {
        val stream =
            object : InputStream() {
                override fun read(): Int = throw SecurityException("grant revoked")
            }
        shadowOf(context.contentResolver).registerInputStream(imageUri, stream)
        assertEquals(ReadFailure.PERMISSION_LOST, (read(imageClip()) as LocalRead.Failed).reason)
    }

    @Test
    fun anImageWithTextBesideItIsStillTheImage() {
        // Browsers and OEM galleries add the image's URL, its alt text or an empty string next to the image URI
        // (CLIP-03 API 1 logic 1 wins over CLIP-01 API 2's text rule).
        val bytes = ByteArray(1_000) { it.toByte() }
        for (text in listOf("", "https://example.com/photo.png", "A photo")) {
            shadowOf(context.contentResolver).registerInputStream(imageUri, bytes.inputStream())
            val clip =
                ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(text, null, null, imageUri))
            val image = read(clip) as LocalRead.Image
            assertEquals(text, "image/png", image.mime)
            assertArrayEquals(bytes, image.file.readBytes())
        }
    }

    @Test
    fun anImageWithHtmlBesideItIsStillTheImage() {
        val bytes = ByteArray(1_000) { it.toByte() }
        shadowOf(context.contentResolver).registerInputStream(imageUri, bytes.inputStream())
        val clip =
            ClipData(
                ClipDescription("photo", arrayOf("text/html", "image/png")),
                ClipData.Item("", "<img src=\"https://example.com/photo.png\">", null, imageUri),
            )
        assertEquals("image/png", (read(clip) as LocalRead.Image).mime)
    }

    @Test
    fun aTextItemWithANonImageUriStaysText() {
        val clip =
            ClipData(
                ClipDescription("doc", arrayOf("text/plain", "application/pdf")),
                ClipData.Item("Order number: HL-240917-0042", null, null, Uri.parse("content://com.example/doc.pdf")),
            )
        assertEquals("Order number: HL-240917-0042", (read(clip) as LocalRead.Text).text)
    }

    @Test
    fun anExternalContentProviderImageIsCopied() {
        val bytes = byteArrayOf(9, 8, 7)
        shadowOf(context.contentResolver).registerInputStream(imageUri, bytes.inputStream())
        val image = read(imageClip()) as LocalRead.Image
        assertEquals("image/png", image.mime)
        assertArrayEquals(bytes, image.file.readBytes())
    }

    @Test
    fun aNonContentImageUriIsNotOpened() {
        val emptyScheme =
            Uri
                .Builder()
                .authority(GALLERY_AUTHORITY)
                .path("/private-clip-7f3a.png")
                .build()
        assertNull(emptyScheme.scheme)
        listOf(
            Uri.parse("file://$GALLERY_AUTHORITY/private-clip-7f3a.png"),
            Uri.parse("android.resource://$GALLERY_AUTHORITY/private-res-7f3a"),
            emptyScheme,
        ).forEach { assertImageRefused(it) }
    }

    @Test
    fun theAppsOwnProviderIsNotOpened() {
        val authority = "app.handlive.clipboard.own"
        registerProvider(authority, context.packageName)
        assertImageRefused(Uri.parse("content://$authority/clip.png"))
    }

    @Test
    fun anUnresolvedContentAuthorityIsNotOpened() {
        assertImageRefused(Uri.parse("content://com.missing.photos/clip.png"))
    }

    @Test
    fun aFileTextUriIsNotReadAsItsPath() {
        val uri = Uri.parse("file://$GALLERY_AUTHORITY/note.txt")
        val clip = ClipData(ClipDescription("note", arrayOf("text/plain")), ClipData.Item(uri))
        assertEquals(ReadFailure.EMPTY_OR_NOT_TEXT, (read(clip) as LocalRead.Failed).reason)
    }

    private fun imageClip() = ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(imageUri))

    private fun registerProvider(
        authority: String,
        packageName: String,
    ) {
        shadowOf(context.packageManager).addOrUpdateProvider(
            ProviderInfo().apply {
                this.authority = authority
                name = "$packageName.FakeProvider"
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
                enabled = true
                exported = true
            },
        )
    }

    /** A registered stream would be copied if the URI check were skipped. */
    private fun assertImageRefused(uri: Uri) {
        val bytes = ByteArray(8) { it.toByte() }
        shadowOf(context.contentResolver).registerInputStream(uri, bytes.inputStream())
        val before = clipNames()
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        BenchLog.install(context)
        ShadowLog.clear()
        val failed =
            read(ClipData(ClipDescription("photo", arrayOf("image/png")), ClipData.Item(uri))) as LocalRead.Failed
        assertEquals(ReadFailure.IMAGE_UNREADABLE, failed.reason)
        assertEquals(before, clipNames())
        val line = ShadowLog.getLogs().filter { it.tag == BenchLog.TAG }.joinToString("\n") { it.msg }
        assertTrue(line, line.contains("why=uri_not_allowed"))
        uri.path?.let { path -> assertFalse(line, line.contains(path)) }
    }

    private fun clipNames() =
        dir
            .listFiles()
            ?.map { it.name }
            ?.toSet()
            .orEmpty()

    private companion object {
        const val GALLERY_AUTHORITY = "com.example.photos"
    }
}
