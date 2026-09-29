package app.handlive.spike.web.service

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import app.handlive.spike.web.logic.BrowserAdapter
import app.handlive.spike.web.logic.BrowserAdapters
import app.handlive.spike.web.logic.ForegroundCheck
import app.handlive.spike.web.logic.NormalizedUrl
import app.handlive.spike.web.logic.PageLogLine
import app.handlive.spike.web.logic.PrivateState
import app.handlive.spike.web.logic.SettleGate
import app.handlive.spike.web.logic.UrlBarMatch
import app.handlive.spike.web.logic.UrlNormalizer
import java.io.File
import java.util.concurrent.Executors

/**
 * "HandLive Browser Pages" spike service (W3). It hears only from the supported browsers (`android:packageNames`)
 * and only window state and content changes, reads the URL bar through a per-browser adapter, waits until the
 * address has been stable for `WEB_SETTLE`, checks the private mode, and logs one `HLWEB` line per page. It sends
 * nothing anywhere: the app has no network permission.
 */
class BrowserPagesService : AccessibilityService() {
    private data class Candidate(
        val adapter: BrowserAdapter,
        val url: NormalizedUrl,
        val match: UrlBarMatch,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val gate = SettleGate<String>()
    private val counter = NodeCounter()
    private val foreground = ForegroundCheck({ BrowserAdapters.forPackage(it) != null })
    private val dumpExecutor = Executors.newSingleThreadExecutor()
    private var candidates = mutableMapOf<String, Candidate>()
    private var current: Pair<String, String>? = null // browser id, hash of the page last reported active
    private var noBarLogged: String? = null
    private var inspectPending = false
    private var events = 0L
    private var cpuNanos = 0L
    private lateinit var salt: String
    private val versions = mutableMapOf<String, String>()

    private val inspectRunnable = Runnable { measured { inspect() } }
    private val settleRunnable = Runnable { measured { settle() } }
    private val leftPoll =
        object : Runnable {
            override fun run() {
                measured { checkStillInBrowser() }
                if (current != null) handler.postDelayed(this, LEFT_POLL_MS)
            }
        }
    private val statsRunnable =
        object : Runnable {
            override fun run() {
                logStats()
                handler.postDelayed(this, STATS_EVERY_MS)
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> endPage("screen_off")
                    ACTION_DUMP -> handler.postDelayed({ dumpActiveWindow() }, intent.getLongExtra(EXTRA_DELAY_MS, 0L))
                    ACTION_STATS -> logStats()
                }
            }
        }

    override fun onServiceConnected() {
        salt = SpikeLog.salt(this)
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(ACTION_DUMP)
                addAction(ACTION_STATS)
            }
        // Exported so `adb shell am broadcast` can ask for a dump; a dump only writes redacted structure locally.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        SpikeLog.write(this, listOf("ev" to "connected", "api" to Build.VERSION.SDK_INT))
        handler.postDelayed(statsRunnable, STATS_EVERY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        events++
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            return
        }
        // Content changes arrive in bursts while a page loads: inspect the window at most every INSPECT_COALESCE_MS.
        if (!inspectPending) {
            inspectPending = true
            handler.postDelayed(inspectRunnable, INSPECT_COALESCE_MS)
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(receiver) }
        SpikeLog.write(this, listOf("ev" to "disconnected"))
        super.onDestroy()
    }

    private fun inspect() {
        inspectPending = false
        val info = rootInActiveWindow
        val front = info?.packageName?.toString()
        val adapter = BrowserAdapters.forPackage(front)
        if (info == null || adapter == null) {
            // Same rule as the poll: a null root is a transient gap until it repeats; another app ends the page.
            if (foreground.observe(front) == ForegroundCheck.Verdict.LEFT) endPage("left", front ?: "none")
            return
        }
        foreground.observe(front)
        val root = AccessibilityUiNode(info, counter)
        val match = adapter.findUrlBar(root)
        if (match == null) {
            gate.observe(null, now())
            if (noBarLogged != adapter.browser.wireId) {
                noBarLogged = adapter.browser.wireId
                log("nobar", adapter, "reason" to "no_url_bar")
            }
            return
        }
        noBarLogged = null
        val url = UrlNormalizer.normalize(match.node.text)
        if (match.node.isFocused || url == null) {
            gate.observe(null, now()) // typing, or not a web page (new tab page, search terms, chrome://)
            return
        }
        val key = adapter.browser.wireId + " " + url.url
        candidates = mutableMapOf(key to Candidate(adapter, url, match))
        val dueAt = gate.observe(key, now()) ?: return
        handler.removeCallbacks(settleRunnable)
        handler.postDelayed(settleRunnable, (dueAt - now()).coerceAtLeast(0L))
    }

    private fun settle() {
        val key = gate.due(now()) ?: return
        val candidate = candidates[key] ?: return
        val info = rootInActiveWindow ?: return
        if (info.packageName?.toString() != candidate.adapter.browser.packageName) return
        val root = AccessibilityUiNode(info, counter)
        val privateState = candidate.adapter.privateState(root, candidate.match)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            probeSecureWindow(root.windowId) { secure -> report(candidate, privateState, secure) }
        } else {
            report(candidate, privateState, "n/a")
        }
    }

    private fun report(
        candidate: Candidate,
        privateState: PrivateState,
        secure: String,
    ) {
        val adapter = candidate.adapter
        val common =
            arrayOf(
                "via" to candidate.match.via.logValue,
                "source" to candidate.match.source,
                "secure" to secure,
            )
        if (privateState is PrivateState.Private || secure == "yes") {
            if (current != null) endPage("private")
            val marker = (privateState as? PrivateState.Private)?.marker ?: "flag_secure"
            log("private", adapter, "private" to "true", "marker" to marker, *common)
            return
        }
        val hash = PageLogLine.hash(candidate.url.url, salt)
        current = adapter.browser.wireId to hash
        log(
            "active",
            adapter,
            "host" to candidate.url.host,
            "hash" to hash,
            "host_only" to candidate.url.hostOnly,
            "scheme" to if (candidate.url.schemeShown) "shown" else "assumed",
            "private" to if (privateState == PrivateState.Normal) "false" else "unknown",
            *common,
        )
        handler.removeCallbacks(leftPoll)
        handler.postDelayed(leftPoll, LEFT_POLL_MS)
    }

    /**
     * API 34+: a window screenshot fails with `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` when the window has FLAG_SECURE.
     * The picture itself is discarded at once; only the error code matters. Needs `canTakeScreenshot`.
     */
    private fun probeSecureWindow(
        windowId: Int,
        done: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return done("n/a")
        takeScreenshotOfWindow(
            windowId,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    screenshot.hardwareBuffer.close()
                    done("no")
                }

                override fun onFailure(errorCode: Int) {
                    done(if (errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) "yes" else "err$errorCode")
                }
            },
        )
    }

    private fun checkStillInBrowser() {
        val front = rootInActiveWindow?.packageName?.toString()
        if (foreground.observe(front) == ForegroundCheck.Verdict.LEFT) endPage("left", front ?: "none")
    }

    /** `web/inactive` in the product: the reported page is no longer in front. */
    private fun endPage(
        reason: String,
        front: String? = null,
    ) {
        gate.reset()
        foreground.reset()
        handler.removeCallbacks(settleRunnable)
        handler.removeCallbacks(leftPoll)
        val (browser, hash) = current ?: return
        current = null
        // front: the package now in front (or none) when the page ended because the browser left.
        val fields = listOf("ev" to "inactive", "browser" to browser, "hash" to hash, "reason" to reason)
        SpikeLog.write(this, if (front != null) fields + ("front" to front) else fields)
    }

    private fun dumpActiveWindow() {
        val info = rootInActiveWindow
        val packageName = info?.packageName?.toString()
        if (info == null || BrowserAdapters.forPackage(packageName) == null) {
            SpikeLog.write(this, listOf("ev" to "dump", "reason" to "no_browser_in_front"))
            return
        }
        val adapter = BrowserAdapters.forPackage(packageName)!!
        val version = version(adapter.browser.packageName)
        val target =
            File(
                SpikeLog.dumpDir(this),
                "${adapter.browser.wireId}-$version-api${Build.VERSION.SDK_INT}-${System.currentTimeMillis()}.txt",
            )
        dumpExecutor.execute {
            val header =
                listOf(
                    "browser=${adapter.browser.wireId} package=$packageName version=$version",
                    "api=${Build.VERSION.SDK_INT} device=${Build.MANUFACTURER} ${Build.MODEL}",
                    "text and descriptions are redacted to their length",
                )
            val nodes = NodeTreeDumper.dump(AccessibilityUiNode(info, NodeCounter()), header, target)
            handler.post { log("dump", adapter, "nodes" to nodes, "source" to target.name) }
        }
    }

    private fun logStats() {
        SpikeLog.write(
            this,
            listOf("ev" to "stats", "events" to events, "cpu_ms" to cpuNanos / 1_000_000, "nodes" to counter.nodes),
        )
    }

    private fun log(
        ev: String,
        adapter: BrowserAdapter,
        vararg fields: Pair<String, Any?>,
    ) {
        SpikeLog.write(
            this,
            listOf(
                "ev" to ev,
                "browser" to adapter.browser.wireId,
                "ver" to version(adapter.browser.packageName),
                "api" to Build.VERSION.SDK_INT,
            ) + fields,
        )
    }

    private fun version(packageName: String): String =
        versions.getOrPut(packageName) {
            try {
                packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
            } catch (_: PackageManager.NameNotFoundException) {
                "?"
            }
        }

    /** Main-thread CPU time spent in the service's own work, for the battery/CPU check (W8). */
    private inline fun measured(block: () -> Unit) {
        val start = Debug.threadCpuTimeNanos()
        block()
        cpuNanos += Debug.threadCpuTimeNanos() - start
    }

    private fun now() = SystemClock.uptimeMillis()

    companion object {
        const val ACTION_DUMP = "app.handlive.spike.web.DUMP"
        const val ACTION_STATS = "app.handlive.spike.web.STATS"
        const val EXTRA_DELAY_MS = "delay_ms"
        private const val INSPECT_COALESCE_MS = 250L
        private const val LEFT_POLL_MS = 2_000L
        private const val STATS_EVERY_MS = 5 * 60_000L
    }
}
