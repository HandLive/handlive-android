package app.handlive.android.feature.clipboard.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import android.os.SystemClock
import androidx.core.content.FileProvider
import java.io.File

/**
 * Trace of a clip HandLive wrote (QC4, CLIP-05): its SHA-256, the wall-clock interval around `setPrimaryClip` (the
 * system stamps `ClipDescription.getTimestamp()` inside it), the uptime of the write and its file, if any.
 */
class OwnWrite(
    val clipId: String,
    val sha256: ByteArray,
    val wallFrom: Long,
    val wallTo: Long,
    val elapsedAt: Long,
    val file: File?,
) {
    /** A sign that the user copied something else since (CLIP-05 step 5). */
    @Volatile
    var changed: Boolean = false
}

/** Writes, clears and identifies the system clipboard (CLIP-02 API 3, CLIP-03 API 6, CLIP-05 API 2). */
interface ClipboardWriter {
    /** Throws on failure (`SecurityException`, a transaction too large) → `ack INTERNAL`. */
    fun writeText(
        clipId: String,
        sha256: ByteArray,
        text: String,
        sensitive: Boolean,
    ): OwnWrite

    fun writeFile(
        clipId: String,
        sha256: ByteArray,
        file: File,
        sensitive: Boolean,
    ): OwnWrite

    fun clear()

    /** Label and timestamp of the current clip, or `null` without focus (no toast, content not read). */
    fun describe(): ClipLabel?
}

/** `ClipDescription.getLabel()` and `getTimestamp()` (wall clock, stamped by the system in `setPrimaryClip`). */
class ClipLabel(
    val label: CharSequence?,
    val timestamp: Long,
)

/** [ClipboardWriter] on `ClipboardManager`; files are shared through the `clipfiles` `FileProvider`. */
class SystemClipboard(
    context: Context,
) : ClipboardWriter {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(ClipboardManager::class.java)

    override fun writeText(
        clipId: String,
        sha256: ByteArray,
        text: String,
        sensitive: Boolean,
    ): OwnWrite = write(clipId, sha256, null, ClipData.newPlainText(LABEL, text), sensitive)

    override fun writeFile(
        clipId: String,
        sha256: ByteArray,
        file: File,
        sensitive: Boolean,
    ): OwnWrite {
        val uri = FileProvider.getUriForFile(appContext, authority(appContext), file)
        return write(clipId, sha256, file, ClipData.newUri(appContext.contentResolver, LABEL, uri), sensitive)
    }

    override fun clear() = manager.clearPrimaryClip()

    override fun describe(): ClipLabel? = manager.primaryClipDescription?.let { ClipLabel(it.label, it.timestamp) }

    private fun write(
        clipId: String,
        sha256: ByteArray,
        file: File?,
        clip: ClipData,
        sensitive: Boolean,
    ): OwnWrite {
        if (sensitive) clip.description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
        val wallFrom = System.currentTimeMillis()
        manager.setPrimaryClip(clip)
        return OwnWrite(clipId, sha256, wallFrom, System.currentTimeMillis(), SystemClock.elapsedRealtime(), file)
    }

    companion object {
        /** Label of every clip HandLive writes; with the timestamp it identifies HandLive's own clip (CLIP-05). */
        const val LABEL = "HandLive"

        /** `ClipDescription.EXTRA_IS_SENSITIVE` (API 33); the string works on every version (QC3). */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

        fun authority(context: Context) = "${context.packageName}.clipfiles"

        fun isSensitive(description: ClipDescription?): Boolean =
            description?.extras?.getBoolean(EXTRA_IS_SENSITIVE) == true
    }
}
