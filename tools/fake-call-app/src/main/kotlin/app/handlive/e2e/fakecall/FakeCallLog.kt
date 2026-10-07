package app.handlive.e2e.fakecall

import android.util.Log

/** `HLFAKECALL` lines the harness reads with `adb logcat -s HLFAKECALL`: `event=<name>` and `key=value` pairs. */
object FakeCallLog {
    private const val TAG = "HLFAKECALL"

    fun event(
        name: String,
        vararg fields: Pair<String, Any?>,
    ) {
        val rest = fields.joinToString("") { (key, value) -> " $key=$value" }
        Log.i(TAG, "event=$name$rest")
    }
}
