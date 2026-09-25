package app.handlive.android.feature.pairing.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CameraX analyzer: decodes the Y plane of each frame (`YUV_420_888`) and reports the first QR text once; later
 * frames are dropped until [rearm] (after an invalid code, E1).
 */
class QrImageAnalyzer(
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val decoder = QrCodeDecoder()
    private val reported = AtomicBoolean(false)
    private var buffer = ByteArray(0)

    fun rearm() = reported.set(false)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (reported.get()) return
            val plane = it.planes.first()
            val data = plane.buffer
            if (buffer.size != data.remaining()) buffer = ByteArray(data.remaining())
            data.get(buffer)
            val text = decoder.decode(buffer, it.width, it.height, plane.rowStride)
            if (text != null && reported.compareAndSet(false, true)) onCode(text)
        }
    }
}
