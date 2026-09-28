package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.protocol.clipboard.ClipboardValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Every file `ClipFiles` builds stays inside `cache/clip/`, whatever id a peer sent. */
class ClipFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dir by lazy { File(folder.root, "clip") }
    private val files by lazy { ClipFiles(dir, System::currentTimeMillis) }

    @Test
    fun filesForValidIdsLandInTheClipDirectory() {
        val id = "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e"
        assertEquals(dir.canonicalFile, files.part(id).canonicalFile.parentFile)
        assertEquals(dir.canonicalFile, files.clip(id, ClipboardValues.MIME_PNG).canonicalFile.parentFile)
        assertEquals(dir.canonicalFile, files.source(id).canonicalFile.parentFile)
        assertEquals(dir.canonicalFile, files.converted(id).canonicalFile.parentFile)
    }

    @Test
    fun idsThatEscapeTheDirectoryAreRefused() {
        listOf("../escape", "../../files/x", "a/b", "../").forEach { id ->
            assertThrows(id, IllegalArgumentException::class.java) { files.part(id) }
            assertThrows(id, IllegalArgumentException::class.java) { files.clip(id, ClipboardValues.MIME_TEXT) }
            assertThrows(id, IllegalArgumentException::class.java) { files.source(id) }
        }
    }
}
