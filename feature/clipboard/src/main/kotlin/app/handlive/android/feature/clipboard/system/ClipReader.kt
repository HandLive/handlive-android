package app.handlive.android.feature.clipboard.system

import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.net.Uri
import app.handlive.android.core.protocol.clipboard.HtmlClipSanitizer
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.LocalRead
import app.handlive.android.feature.clipboard.module.ReadFailure
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog

/**
 * CLIP-01 API 2 logic 2 and CLIP-03 API 1: item 0 of a clip is an image copied into `cache/clip/` right away when its
 * URI is an image — the clipboard's URI grant ends when the clip changes — else plain text (`coerceToText`: HTML gives
 * its text, a text URI is read by the system) with the sanitized `htmlText` beside it when the item has one. The
 * image wins over a text beside it: browsers and OEM galleries put the image's URL, its alt text or an empty string
 * next to the URI, and the user copied the picture, not that text.
 * A URI is opened only after [ClipUriPolicy.isReadable] (CLIP-01 API 2 logic 7). Anything else is E3. Runs off the
 * main thread; the content and the URI path are never logged.
 */
object ClipReader {
    fun read(
        context: Context,
        clip: ClipData?,
        source: String,
        files: ClipFiles,
    ): LocalRead {
        if (clip == null || clip.itemCount == 0) {
            return unsupported(clip, source, if (clip == null) "no_clip" else "no_item")
        }
        return fromItem(context, clip, clip.getItemAt(0), source, files)
    }

    private fun fromItem(
        context: Context,
        clip: ClipData,
        item: ClipData.Item,
        source: String,
        files: ClipFiles,
    ): LocalRead {
        val sensitive = SystemClipboard.isSensitive(clip.description)
        val refused = refusedUri(context, clip, item, source)
        val uri = item.uri
        val image =
            if (refused == null && uri != null && ClipUriPolicy.isReadable(context, uri)) {
                ClipImageCopier.copyIfImage(
                    context,
                    clip.description,
                    uri,
                    ClipOrigin(sensitive, source),
                    files,
                )
            } else {
                null
            }
        val textual =
            if (refused == null && image == null) {
                textIfAny(context, clip.description, item, sensitive, source)
            } else {
                null
            }
        return refused ?: image ?: textual ?: unsupported(clip, source, "neither_text_nor_image")
    }

    /** A refused URI that the next step would open, or null when the item can still be read. */
    private fun refusedUri(
        context: Context,
        clip: ClipData,
        item: ClipData.Item,
        source: String,
    ): LocalRead? {
        val uri = item.uri
        if (uri == null || ClipUriPolicy.isReadable(context, uri) || !wouldOpenUri(clip.description, item)) {
            return null
        }
        val failure =
            if (ClipImageCopier.describedMime(clip.description) != null) {
                ReadFailure.IMAGE_UNREADABLE
            } else {
                ReadFailure.EMPTY_OR_NOT_TEXT
            }
        return rejectUri(uri, source, failure)
    }

    private fun textIfAny(
        context: Context,
        description: ClipDescription,
        item: ClipData.Item,
        sensitive: Boolean,
        source: String,
    ): LocalRead? {
        val uri = item.uri
        val textual =
            item.text != null ||
                item.htmlText != null ||
                (uri != null && description.hasMimeType(TEXT_ANY))
        if (!textual) return null
        // `htmlText` is the string already on the item. `coerceToHtmlText` is not used: with no text and no
        // HTML it opens the URI through `openTypedAssetFileDescriptor`.
        return text(plainText(context, item), sensitive, source, item.htmlText)
    }

    /**
     * E3 with its reason in debug builds (HLBENCH `clip_read_failed`): the MIME types the description declares and
     * which parts item 0 carries, never the content. This is what tells a silent copy on an OEM build apart.
     */
    private fun unsupported(
        clip: ClipData?,
        source: String,
        why: String,
    ): LocalRead {
        val description = clip?.description
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val mimeCount = description?.mimeTypeCount ?: 0
        val mimes = (0 until mimeCount).joinToString(",") { description?.getMimeType(it) ?: "" }
        val parts =
            listOfNotNull(
                "text".takeIf { item?.text != null },
                "html".takeIf { item?.htmlText != null },
                "uri".takeIf { item?.uri != null },
                "intent".takeIf { item?.intent != null },
            ).joinToString("+")
        BenchLog.event(
            BenchEvent.CLIP_READ_FAILED,
            "reason" to ReadFailure.EMPTY_OR_NOT_TEXT.name.lowercase(),
            "stage" to "read",
            "why" to why,
            "mimes" to mimes.ifEmpty { "-" },
            "parts" to parts.ifEmpty { "-" },
            "authority" to (item?.uri?.let(ClipUriPolicy::logAuthority) ?: "-"),
            "source" to source,
        )
        return LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, source)
    }

    private fun text(
        text: CharSequence?,
        sensitive: Boolean,
        source: String,
        html: String? = null,
    ): LocalRead =
        text?.toString()?.takeIf { it.isNotEmpty() }?.let {
            LocalRead.Text(it, sensitive, source, HtmlClipSanitizer.sanitizeOrNull(html))
        }
            ?: LocalRead.Failed(ReadFailure.EMPTY_OR_NOT_TEXT, source)

    /**
     * True when the next step would open the item URI: an image MIME on the description, or `coerceToText` /
     * `coerceToHtmlText` because `text` is absent (both open a content URI).
     */
    private fun wouldOpenUri(
        description: ClipDescription,
        item: ClipData.Item,
    ): Boolean =
        ClipImageCopier.describedMime(description) != null ||
            (item.text == null && (item.htmlText != null || description.hasMimeType(TEXT_ANY)))

    /**
     * `coerceToText` opens a URI when `text` is absent. A refused URI is never passed to it: the text
     * already on the item is returned as-is, otherwise nothing. HTML stays the item's own `htmlText`.
     */
    private fun plainText(
        context: Context,
        item: ClipData.Item,
    ): CharSequence? {
        val uri = item.uri
        if (uri != null && !ClipUriPolicy.isReadable(context, uri)) return item.text
        return item.coerceToText(context)
    }

    private fun rejectUri(
        uri: Uri,
        source: String,
        failure: ReadFailure,
    ): LocalRead {
        BenchLog.event(
            BenchEvent.CLIP_READ_FAILED,
            "reason" to failure.name.lowercase(),
            "stage" to "read",
            "why" to "uri_not_allowed",
            "authority" to ClipUriPolicy.logAuthority(uri),
            "source" to source,
        )
        return LocalRead.Failed(failure, source)
    }

    private const val TEXT_ANY = "text/*"
}
