package app.handlive.android.feature.clipboard.engine

import app.handlive.android.core.protocol.ErrorCode
import app.handlive.android.core.protocol.clipboard.ClipboardPushData
import app.handlive.android.core.protocol.clipboard.ClipboardTransfer
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.encoding.Base64Codecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Receiver checks of CLIP-01 API 5 logic 5 and CLIP-03 API 3 rule 1, in their order. */
class PushValidatorTest {
    private val all = Acceptance(active = true, mimes = listOf("text/plain", "image/png", "image/jpeg"))
    private val textOnly = Acceptance(active = true, mimes = listOf("text/plain"))
    private val text =
        ClipboardPushData(
            clipId = "0192f3e0-5a21-7b3c-9d4e-1f2a3b4c5d6e",
            kind = ClipboardValues.KIND_TEXT,
            mime = ClipboardValues.MIME_TEXT,
            text = "https://example.com/docs/q3-report",
            sensitive = false,
            originTs = 1_727_150_160_456,
            source = "mac",
            originDeviceId = "5b1f8c2e-9a4d-8e6f-a1b2-c3d4e5f60718",
        )
    private val transfer =
        ClipboardTransfer(
            "0192f3f1-2c3e-7a10-9b20-c30d40e50f60",
            5_242_880,
            Base64Codecs.encodeB64u(ByteArray(32)),
            65_536,
            80,
        )
    private val image =
        text.copy(
            kind = ClipboardValues.KIND_IMAGE,
            mime = ClipboardValues.MIME_PNG,
            text = null,
            transfer = transfer,
            width = 2880,
            height = 1800,
        )

    private fun code(
        push: ClipboardPushData,
        acceptance: Acceptance = all,
    ) = PushValidator.check(push, acceptance)?.code

    @Test
    fun validInlineTextAndChunkedImagesPass() {
        assertNull(code(text))
        assertNull(code(image))
    }

    @Test
    fun structureProblemsAreBadRequest() {
        assertEquals(ErrorCode.BAD_REQUEST, code(text.copy(mime = ClipboardValues.MIME_PNG)))
        assertEquals(ErrorCode.BAD_REQUEST, code(image.copy(mime = ClipboardValues.MIME_TEXT)))
        assertEquals(ErrorCode.BAD_REQUEST, code(text.copy(kind = "file")))
        assertEquals(ErrorCode.BAD_REQUEST, code(text.copy(transfer = transfer)))
        assertEquals(ErrorCode.BAD_REQUEST, code(text.copy(text = null)))
        assertEquals(ErrorCode.BAD_REQUEST, code(image.copy(text = "inline image")))
        assertEquals(ErrorCode.BAD_REQUEST, code(text.copy(text = "broken \uD800 surrogate")))
        assertEquals(ErrorCode.BAD_REQUEST, code(image.copy(transfer = transfer.copy(chunkCount = 79))))
    }

    @Test
    fun activityLimitsAndMimeFollowInThatOrder() {
        assertEquals(ErrorCode.FEATURE_DISABLED, code(text, Acceptance(active = false, mimes = all.mimes)))
        val big = "a".repeat(ClipLimits.MAX_TEXT_BYTES.toInt() + 1)
        assertEquals(ErrorCode.CLIP_TOO_LARGE, code(text.copy(text = big)))
        val hugeImage = image.copy(transfer = transfer.copy(size = ClipLimits.MAX_IMAGE_BYTES + 1, chunkCount = 161))
        assertEquals(ErrorCode.CLIP_TOO_LARGE, code(hugeImage))
        assertEquals(ErrorCode.CLIP_UNSUPPORTED_MIME, code(image, textOnly))
        assertEquals(ErrorCode.CLIP_UNSUPPORTED_MIME, code(image.copy(mime = "image/gif")))
    }
}
