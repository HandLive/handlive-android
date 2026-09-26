package app.handlive.android.feature.clipboard.system

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.feature.clipboard.module.ImageNormalizer
import app.handlive.android.feature.clipboard.module.NormalizedImage
import java.io.File

/**
 * CLIP-03 API 1 step 4 with the platform decoders: PNG and JPEG keep their bytes (only the bounds are decoded);
 * TIFF, HEIC, WebP, GIF… are decoded with `ImageDecoder` (first frame of an animation) and re-encoded as PNG.
 * A picture too large to decode in memory counts as unreadable (E3) instead of risking the process.
 */
class AndroidImageNormalizer : ImageNormalizer {
    override fun normalize(
        source: File,
        mime: String,
        target: File,
    ): NormalizedImage? =
        runCatching {
            if (mime == ClipboardValues.MIME_PNG ||
                mime == ClipboardValues.MIME_JPEG
            ) {
                keep(source, mime)
            } else {
                convert(source, target)
            }
        }.getOrNull()

    private fun keep(
        source: File,
        mime: String,
    ): NormalizedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "not an image" }
        return NormalizedImage(source, mime, bounds.outWidth, bounds.outHeight)
    }

    private fun convert(
        source: File,
        target: File,
    ): NormalizedImage {
        val bitmap =
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                check(info.size.width.toLong() * info.size.height <= MAX_PIXELS) { "image too large to decode" }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        try {
            target.outputStream().use {
                check(
                    bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it),
                ) { "encode failed" }
            }
            return NormalizedImage(target, ClipboardValues.MIME_PNG, bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        /** About 160 MB of ARGB pixels; the PNG of a larger picture would exceed `CLIP_MAX_IMAGE` anyway. */
        const val MAX_PIXELS = 40_000_000L

        /** PNG is lossless; the quality argument is ignored. */
        const val PNG_QUALITY = 100
    }
}
