package app.handlive.spike.call.service

import android.content.Context
import android.util.Log
import app.handlive.spike.call.logic.CallLogLine
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * Writes `HLCALL` lines to logcat (tag `HLCALL`) and appends them to `hlcall.log` in the app's external files
 * directory (`/sdcard/Android/data/app.handlive.spike.call/files/`, readable with `adb pull`). File writes run on one
 * background thread so the listener callbacks never wait for storage.
 */
object SpikeLog {
    private const val FILE_NAME = "hlcall.log"
    private val writer = Executors.newSingleThreadExecutor()
    private val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    fun write(
        context: Context,
        fields: List<Pair<String, Any?>>,
    ) {
        val line = CallLogLine.format(listOf("ts" to OffsetDateTime.now().format(timestamp)) + fields)
        Log.i(CallLogLine.TAG, line)
        val target = file(context)
        writer.execute { target.appendText(line + "\n") }
    }

    fun readLines(context: Context): List<String> = file(context).takeIf { it.exists() }?.readLines().orEmpty()

    fun clear(context: Context) {
        val target = file(context)
        writer.execute { target.delete() }
    }
}
