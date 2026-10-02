package app.handlive.spike.call.service

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Spike question 3: can the probe move another app's call audio to a Bluetooth HFP device (the Mac) with
 * `setCommunicationDevice`, with or without taking the communication audio mode itself? Logs every change of the
 * communication device, the request result and how long the route took to apply.
 */
class AudioRouter private constructor(
    private val context: Context,
) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var modeSetByProbe = false
    private var watching = false

    fun watch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || watching) return
        watching = true
        audio.addOnCommunicationDeviceChangedListener(context.mainExecutor) { device ->
            SpikeLog.write(
                context,
                listOf(
                    "ev" to "comm_device",
                    "type" to device?.type,
                    "name" to device?.productName,
                    "mode" to audio.mode,
                ),
            )
        }
    }

    fun logDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return unsupported("devices")
        audio.availableCommunicationDevices.forEach {
            SpikeLog.write(
                context,
                listOf("ev" to "device", "id" to it.id, "type" to it.type, "name" to it.productName),
            )
        }
        val current = audio.communicationDevice
        SpikeLog.write(
            context,
            listOf("ev" to "current", "type" to current?.type, "name" to current?.productName, "mode" to audio.mode),
        )
    }

    @SuppressLint("WrongConstant")
    fun routeToBluetooth(takeMode: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return unsupported("route")
        val target = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        if (target == null) {
            SpikeLog.write(context, listOf("ev" to "route", "result" to "no_bt_sco"))
            return
        }
        CallRegistry.markCommand(if (takeMode) "route_mode" else "route")
        if (takeMode) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            modeSetByProbe = true
        }
        val started = SystemClock.elapsedRealtime()
        val accepted = audio.setCommunicationDevice(target)
        SpikeLog.write(
            context,
            listOf(
                "ev" to "route_requested",
                "accepted" to accepted,
                "take_mode" to takeMode,
                "mode" to audio.mode,
                "name" to target.productName,
            ),
        )
        if (accepted) awaitRoute(target.id, started)
    }

    fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audio.clearCommunicationDevice()
        if (modeSetByProbe) {
            audio.mode = AudioManager.MODE_NORMAL
            modeSetByProbe = false
        }
        SpikeLog.write(context, listOf("ev" to "unroute", "mode" to audio.mode))
    }

    private fun awaitRoute(
        deviceId: Int,
        started: Long,
    ) {
        handler.postDelayed(
            object : Runnable {
                override fun run() {
                    val elapsed = SystemClock.elapsedRealtime() - started
                    val current = audio.communicationDevice
                    when {
                        current?.id == deviceId -> {
                            SpikeLog.write(context, listOf("ev" to "route", "result" to "applied", "ms" to elapsed))
                        }

                        elapsed > ROUTE_TIMEOUT_MS -> {
                            SpikeLog.write(
                                context,
                                listOf(
                                    "ev" to "route",
                                    "result" to "timeout",
                                    "ms" to elapsed,
                                    "current" to current?.type,
                                ),
                            )
                        }

                        else -> {
                            handler.postDelayed(this, POLL_MS)
                        }
                    }
                }
            },
            POLL_MS,
        )
    }

    private fun unsupported(what: String) {
        SpikeLog.write(context, listOf("ev" to what, "result" to "unsupported_api", "sdk" to Build.VERSION.SDK_INT))
    }

    companion object {
        private const val POLL_MS = 50L
        private const val ROUTE_TIMEOUT_MS = 3_000L

        @SuppressLint("StaticFieldLeak") // holds the application context only
        private var instance: AudioRouter? = null

        @Synchronized
        fun get(context: Context): AudioRouter =
            instance ?: AudioRouter(context.applicationContext).also { instance = it }
    }
}
