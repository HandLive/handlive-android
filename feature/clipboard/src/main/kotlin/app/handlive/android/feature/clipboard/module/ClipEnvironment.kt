package app.handlive.android.feature.clipboard.module

import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.feature.clipboard.ClipNotices
import app.handlive.android.feature.clipboard.system.ClipboardWriter
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** The phone as the clipboard feature needs it: `device_id` (origin of local clips) and name (`clipboard/conflict`). */
class LocalDevice(
    val deviceId: String,
    val name: String,
)

/** Wall clock in ms (`origin_ts`, QC8 a, QC7) and a monotonic clock in ms that counts sleep (QC4, CLIP-05). */
class ClipClock(
    val wall: () -> Long,
    val elapsed: () -> Long,
)

/** An image ready to send (CLIP-03 step 4). */
class NormalizedImage(
    val file: File,
    val mime: String,
    val width: Int,
    val height: Int,
)

/** CLIP-03 API 1: PNG and JPEG keep their bytes; other formats are decoded (first frame) and re-encoded as PNG. */
fun interface ImageNormalizer {
    /** `null` when the image cannot be decoded (E3); [target] receives a converted image. */
    fun normalize(
        source: File,
        mime: String,
        target: File,
    ): NormalizedImage?
}

/** Focus facts CLIP-05 needs to verify HandLive's clip (step 7). */
interface FocusProbe {
    /** A-UI has focus: the clipboard description can be read directly. */
    val appFocused: Boolean

    /** `ClipboardAccessibilityService` is bound: `ClipboardReadActivity` may take focus from the background. */
    val accessibilityRunning: Boolean

    /** Opens `ClipboardReadActivity` in verification mode; `false` when it could not be started. */
    fun startVerification(): Boolean
}

/** The Android pieces, behind interfaces so the clipboard logic runs in JVM tests; [io] runs file work. */
class ClipPlatform(
    val writer: ClipboardWriter,
    val notices: ClipNotices,
    val files: ClipFiles,
    val images: ImageNormalizer,
    val focus: FocusProbe,
    val io: CoroutineDispatcher,
)

/** The clients as the clipboard sees them: open sessions by `pair_id`, and this phone. */
class ClipNetwork(
    val sessions: StateFlow<Map<String, PeerSession>>,
    val local: suspend () -> LocalDevice,
)

/** What every part of the clipboard module shares; [scope] runs on one serial dispatcher. */
class ClipContext(
    val scope: CoroutineScope,
    val clock: ClipClock,
    val state: ClipState,
    val platform: ClipPlatform,
    val settings: StateFlow<HandLiveSettings>,
    val network: ClipNetwork,
)
