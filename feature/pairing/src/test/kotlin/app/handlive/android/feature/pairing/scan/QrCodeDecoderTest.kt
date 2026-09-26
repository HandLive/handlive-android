package app.handlive.android.feature.pairing.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** ZXing decoding of camera-like luminance planes: padded rows, inverted codes, frames without a code. */
class QrCodeDecoderTest {
    private val decoder = QrCodeDecoder()

    @Test
    fun decodesAPairingCodeFromAPaddedLuminancePlane() {
        val plane = render(TEXT, padding = 16, invert = false)
        assertEquals(TEXT, decoder.decode(plane.bytes, plane.width, plane.height, plane.stride))
    }

    @Test
    fun decodesAnInvertedCode() {
        val plane = render(TEXT, padding = 0, invert = true)
        assertEquals(TEXT, decoder.decode(plane.bytes, plane.width, plane.height, plane.stride))
    }

    @Test
    fun frameWithoutACodeGivesNull() {
        assertNull(decoder.decode(ByteArray(320 * 240) { 0x7f }, 320, 240))
    }

    private class Plane(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
        val stride: Int,
    )

    /** Error correction M as the Mac draws it (PAIR-01 field 1). */
    private fun render(
        text: String,
        padding: Int,
        invert: Boolean,
    ): Plane {
        val matrix =
            QRCodeWriter().encode(
                text,
                BarcodeFormat.QR_CODE,
                SIZE,
                SIZE,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        val stride = SIZE + padding
        val bytes = ByteArray(stride * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val dark = matrix[x, y] != invert
                bytes[y * stride + x] = if (dark) 0 else 0xff.toByte()
            }
        }
        return Plane(bytes, SIZE, SIZE, stride)
    }

    private companion object {
        const val SIZE = 400
        const val TEXT =
            "handlive://pair?v=1&pk=q83vEjRWeJC7zN3u_wARIjNEVWZ3iJmqu8zd7v8AESI" +
                "&ps=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8&d=MacBook%20c%E1%BB%A7a%20Lan"
    }
}
