package app.handlive.android.feature.pairing.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * Finds a QR code in a camera frame's luminance plane with ZXing core (Apache 2.0) — on the device, without ML Kit
 * or Play Services (plan decision I8). The Mac shows the code dark on light in every appearance (PairingCard), but
 * inverted codes are tried too.
 */
class QrCodeDecoder {
    private val reader = QRCodeReader()
    private val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )

    /** Text of the code in the frame, or `null`. [rowStride] ≥ [width] (camera planes are often padded). */
    fun decode(
        luminance: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
    ): String? {
        val source = PlanarYUVLuminanceSource(luminance, rowStride, height, 0, 0, width, height, false)
        return decode(source) ?: decode(source.invert())
    }

    private fun decode(source: LuminanceSource): String? =
        try {
            reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
}
