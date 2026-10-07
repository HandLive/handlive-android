package app.handlive.e2e.fakecall

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The launcher activity, which HandLive's `MAIN`/`LAUNCHER` query makes visible, and the entry of the adb commands.
 * Starting it from the shell puts the app in the foreground, so it may start its foreground service:
 *
 * `adb shell am start -n app.handlive.e2e.fakecall/.CommandActivity --es cmd <ring|answer|hangup|upload|clear>`
 *
 * It has no screen: it hands the command to [FakeCallService] and finishes.
 */
class CommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val command = intent.getStringExtra(EXTRA_CMD)
        val service = Intent(this, FakeCallService::class.java)
        when (command) {
            "ring" -> startForegroundService(service.setAction(FakeCallService.RING))
            "answer" -> startForegroundService(service.setAction(FakeCallService.ANSWER))
            "hangup" -> startService(service.setAction(FakeCallService.HANG_UP_HERE))
            "upload" -> startService(service.setAction(FakeCallService.UPLOAD))
            "clear" -> startService(service.setAction(FakeCallService.CLEAR_UPLOAD))
            else -> FakeCallLog.event("unknown_cmd", "cmd" to command)
        }
        finish()
    }

    private companion object {
        const val EXTRA_CMD = "cmd"
    }
}
