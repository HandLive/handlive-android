package app.handlive.android.feature.pairing.scan

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.handlive.android.core.design.theme.HandLiveTheme
import java.util.concurrent.Executors

/** Viewfinder size relative to the shorter side of the screen. */
private const val FRAME_FRACTION = 0.7f

/** Length of each corner arm relative to the viewfinder side. */
private const val ARM_FRACTION = 1f / 6

/** Dimming outside the viewfinder (PairingCard: "a dark layer outside the frame"). */
private const val SCRIM_ALPHA = 0.55f

/**
 * Full-screen camera preview with the four-corner viewfinder of PairingCard (the scanning side), decoding QR codes
 * on the device with CameraX + ZXing. The camera permission must already be granted (the screen asks just in time,
 * SET-01 part B). [onCode] gets the text of the first code found; call [QrImageAnalyzer.rearm] through
 * [analyzer] to scan again.
 */
@Composable
fun QrScanner(
    analyzer: QrImageAnalyzer,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor, analyzer) }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        Viewfinder(HandLiveTheme.colors.onVideo)
    }
}

@Composable
private fun Viewfinder(corner: Color) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val side = size.minDimension * FRAME_FRACTION
        val frame = Rect(Offset((size.width - side) / 2, (size.height - side) / 2), Size(side, side))
        val scrim =
            Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addRect(frame)
            }
        drawPath(scrim, Color.Black.copy(alpha = SCRIM_ALPHA))
        val arm = side * ARM_FRACTION
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        listOf(
            frame.topLeft to Offset(1f, 1f),
            frame.topRight to Offset(-1f, 1f),
            frame.bottomLeft to Offset(1f, -1f),
            frame.bottomRight to Offset(-1f, -1f),
        ).forEach { (point, direction) ->
            val path =
                Path().apply {
                    moveTo(point.x + direction.x * arm, point.y)
                    lineTo(point.x, point.y)
                    lineTo(point.x, point.y + direction.y * arm)
                }
            drawPath(path, corner, style = stroke)
        }
    }
}
