package app.handlive.android.feature.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.protocol.clipboard.ClipboardValues
import app.handlive.android.core.protocol.envelope.MessageType
import app.handlive.android.feature.clipboard.component.ClipNotifier
import app.handlive.android.feature.clipboard.component.ClipboardReadActivity
import app.handlive.android.feature.clipboard.module.ClipClock
import app.handlive.android.feature.clipboard.module.ClipFiles
import app.handlive.android.feature.clipboard.module.ClipPlatform
import app.handlive.android.feature.clipboard.module.ClipboardModule
import app.handlive.android.feature.clipboard.module.FocusProbe
import app.handlive.android.feature.clipboard.module.LocalDevice
import app.handlive.android.feature.clipboard.system.AndroidImageNormalizer
import app.handlive.android.feature.clipboard.system.ClipReader
import app.handlive.android.feature.clipboard.system.SystemClipboard
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.LocalDeviceName
import app.handlive.android.feature.connection.ServiceHooks
import app.handlive.android.feature.connection.bench.BenchEvent
import app.handlive.android.feature.connection.bench.BenchLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The clipboard feature of this process (A-CLIP): builds [ClipboardModule] with the Android pieces, registers it for
 * `type = clipboard`, installs the notification's "Send Clipboard" button, and serves the Android components
 * (Accessibility service, `ClipboardReadActivity`, tile, notification actions) and A-UI's focus hooks.
 */
class ClipboardFeature private constructor(
    context: Context,
) : FocusProbe {
    private val appContext = context.applicationContext
    private val data = HandLiveData.get(appContext)
    private val runtime = ConnectionRuntime.get(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val files = ClipFiles(File(appContext.cacheDir, CLIP_DIR), System::currentTimeMillis)
    private val localLock = Mutex()
    private var local: LocalDevice? = null
    private val manager = appContext.getSystemService(ClipboardManager::class.java)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener { onClipChangedWhileFocused() }

    val clipboard = SystemClipboard(appContext)

    val settings: StateFlow<HandLiveSettings> =
        data.settings.settings.stateIn(scope, SharingStarted.Eagerly, HandLiveSettings())

    @Volatile
    override var appFocused: Boolean = false
        private set

    @Volatile
    override var accessibilityRunning: Boolean = false
        private set

    val module =
        ClipboardModule(
            dispatcher = Dispatchers.Default.limitedParallelism(1),
            sessions = runtime.sessions,
            settings = settings,
            platform =
                ClipPlatform(
                    clipboard,
                    ClipNotifier(appContext),
                    files,
                    AndroidImageNormalizer(),
                    this,
                    Dispatchers.IO,
                ),
            local = ::localDevice,
            clock = ClipClock(System::currentTimeMillis, SystemClock::elapsedRealtime),
        )

    override fun startVerification(): Boolean = ClipboardReadActivity.startVerification(appContext)

    /** CLIP-01 A3: the Accessibility service bound or unbound; `features.clipboard.auto_send` follows. */
    fun setAccessibilityRunning(running: Boolean) {
        accessibilityRunning = running
        runtime.setAccessibilityRunning(running)
    }

    /** CLIP-01 API 1 logic 1: events count only with the feature, auto-send and the recorded consent. */
    fun autoSendAllowed(): Boolean =
        settings.value.let {
            it.clipboardEnabled && it.clipAutoSend &&
                it.clipA11yConsentAt != null
        }

    /** `clip.auto_send` as stored (not the default before DataStore loads), for the service's `disableSelf()`. */
    fun autoSendSetting(): Flow<Boolean> =
        data.settings.settings
            .map { it.clipAutoSend }
            .distinctUntilChanged()

    /** Reads [clip] on the IO dispatcher (an image is copied before the activity closes), then calls [done]. */
    fun readInBackground(
        clip: ClipData?,
        source: String,
        done: () -> Unit,
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                module.onLocalRead(ClipReader.read(appContext, clip, source, files))
            } finally {
                done()
            }
        }
    }

    /** A-UI resumed (CLIP-01 API 2 logic 6, CLIP-05 E3): listen for copies and run a postponed auto-clear check. */
    fun onAppResumed() {
        appFocused = true
        manager.addPrimaryClipChangedListener(listener)
        module.onAppFocused()
    }

    fun onAppPaused() {
        appFocused = false
        manager.removePrimaryClipChangedListener(listener)
    }

    /** The listener fires only while HandLive has focus; HandLive's own writes are recognized and ignored. */
    private fun onClipChangedWhileFocused() {
        val current = settings.value
        if (module.onClipboardChanged(clipboard.describe()) && current.clipboardEnabled && current.clipAutoSend) {
            BenchLog.event(BenchEvent.COPY_DETECTED)
            readInBackground(manager.primaryClip, ClipboardValues.SOURCE_AUTO) {}
        }
    }

    private suspend fun localDevice(): LocalDevice =
        localLock.withLock {
            local ?: LocalDevice(data.identity.deviceId, LocalDeviceName.read(appContext)).also { local = it }
        }

    companion object {
        private const val CLIP_DIR = "clip"

        @Volatile
        private var instance: ClipboardFeature? = null

        fun get(context: Context): ClipboardFeature =
            instance ?: synchronized(this) {
                instance ?: ClipboardFeature(context).also { instance = it }
            }

        /** Connects the feature to the connection runtime; runs once at process start. */
        fun install(context: Context) {
            val feature = get(context)
            feature.runtime.router.register(MessageType.CLIPBOARD, feature.module.handler)
            ServiceHooks.sendClipboardIntent = ClipboardReadActivity::manualPendingIntent
            feature.module.start()
        }
    }
}
