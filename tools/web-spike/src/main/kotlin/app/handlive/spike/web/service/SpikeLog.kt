package app.handlive.spike.web.service

import android.content.Context
import android.util.Log
import app.handlive.spike.web.logic.PageLogLine
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Writes `HLWEB` lines to logcat (tag `HLWEB`) and appends them to `hlweb.log` in the app's external files directory
 * (`/sdcard/Android/data/app.handlive.spike.web/files/`, readable with `adb pull`). File writes happen on one
 * background thread so the accessibility callbacks never wait for storage.
 */
object SpikeLog {
    private const val FILE_NAME = "hlweb.log"
    private const val PREFS = "spike"
    private const val SALT_KEY = "hash_salt"
    private val writer = Executors.newSingleThreadExecutor()
    private val timestamp = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun file(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FILE_NAME)

    fun dumpDir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "dumps").apply { mkdirs() }

    /** Per-install random salt for [PageLogLine.hash]: hashes cannot be reversed with a list of popular sites. */
    fun salt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(SALT_KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(SALT_KEY, it).apply()
        }
    }

    fun write(
        context: Context,
        fields: List<Pair<String, Any?>>,
    ) {
        val line = PageLogLine.format(listOf("ts" to OffsetDateTime.now().format(timestamp)) + fields)
        Log.i(PageLogLine.TAG, line)
        val target = file(context)
        writer.execute { target.appendText(line + "\n") }
    }

    fun readLines(context: Context): List<String> = file(context).takeIf { it.exists() }?.readLines().orEmpty()

    fun clear(context: Context) {
        val target = file(context)
        writer.execute { target.delete() }
    }
}
