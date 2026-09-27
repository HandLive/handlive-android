package app.handlive.android.feature.connection.bench

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log

/**
 * `HLBENCH/1` event lines for the latency benchmarks of gate G1 (`shared/tools/bench/README.md`), written with
 * `Log.i("HLBENCH", …)` in debuggable builds only. Lines carry ids, kinds, sizes and states — never clipboard
 * content, names or addresses (QC2, 0.6.5).
 */
object BenchLog {
    const val TAG = "HLBENCH"

    @Volatile
    private var enabled = false

    @Volatile
    private var device = UNKNOWN_DEVICE

    /** Enables logging for debuggable builds (`android:debuggable`, i.e. debug build types) only. */
    fun install(context: Context) {
        enabled = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /** `dev` field: the first 8 hex digits of this phone's `device_id`. */
    fun setDevice(deviceId: String) {
        device = deviceId.take(DEVICE_PREFIX)
    }

    fun event(
        event: String,
        vararg fields: Pair<String, Any>,
    ) = event(event, fields.asList())

    fun event(
        event: String,
        fields: List<Pair<String, Any>>,
    ) {
        if (!enabled) return
        Log.i(TAG, format(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos(), device, event, fields))
    }

    /** `HLBENCH/1 wall=<ms> mono=<ns> dev=<id8> role=android ev=<event> [key=value …]`; values lose their spaces. */
    fun format(
        wallMillis: Long,
        monoNanos: Long,
        device: String,
        event: String,
        fields: List<Pair<String, Any>>,
    ): String =
        buildString {
            append("HLBENCH/1 wall=").append(wallMillis)
            append(" mono=").append(monoNanos)
            append(" dev=").append(device)
            append(" role=android ev=").append(event)
            fields.forEach { (key, value) ->
                append(' ').append(key).append('=').append(value.toString().replace(' ', '_'))
            }
        }

    private const val DEVICE_PREFIX = 8
    private const val UNKNOWN_DEVICE = "00000000"
}

/** Event names of the `HLBENCH/1` table that Android writes. */
object BenchEvent {
    const val COPY_DETECTED = "copy_detected"
    const val CLIP_READ = "clip_read"
    const val CLIP_SENT = "clip_sent"
    const val CLIP_RECEIVED = "clip_received"
    const val CLIP_APPLIED = "clip_applied"
    const val ACK_SENT = "ack_sent"
    const val ACK_RECEIVED = "ack_received"
    const val NET = "net"
}
