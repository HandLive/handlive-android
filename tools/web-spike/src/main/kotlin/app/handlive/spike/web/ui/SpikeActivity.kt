package app.handlive.spike.web.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.handlive.spike.web.R
import app.handlive.spike.web.logic.PageLogLine
import app.handlive.spike.web.service.BrowserPagesService
import app.handlive.spike.web.service.SpikeLog

/**
 * The spike's only screen: whether the service is on, buttons to open Accessibility settings, dump the browser
 * window, export the log as CSV and clear it, and the last lines of the log.
 */
class SpikeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
            }
        status = TextView(this)
        column.addView(status)
        column.addView(
            button(R.string.open_accessibility_settings) {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            },
        )
        column.addView(
            button(R.string.dump_in_five_seconds) {
                sendBroadcast(
                    Intent(BrowserPagesService.ACTION_DUMP)
                        .setPackage(packageName)
                        .putExtra(BrowserPagesService.EXTRA_DELAY_MS, DUMP_DELAY_MS),
                )
            },
        )
        column.addView(button(R.string.export_csv) { exportCsv() })
        column.addView(
            button(R.string.clear_log) {
                SpikeLog.clear(this)
                logView.postDelayed({ refresh() }, REFRESH_AFTER_CLEAR_MS)
            },
        )
        column.addView(button(R.string.refresh) { refresh() })
        logView =
            TextView(this).apply {
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = LOG_TEXT_SP
                setTextIsSelectable(true)
            }
        column.addView(logView)
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun button(
        label: Int,
        onClick: (View) -> Unit,
    ) = Button(this).apply {
        setText(label)
        setOnClickListener(onClick)
    }

    private fun refresh() {
        status.text = getString(if (serviceEnabled()) R.string.service_on else R.string.service_off) + "\n" +
            getString(R.string.log_location, SpikeLog.file(this).absolutePath)
        logView.text =
            SpikeLog
                .readLines(this)
                .takeLast(SHOWN_LINES)
                .asReversed()
                .joinToString("\n")
    }

    /** Reads the list of enabled services; turning the service on is left to the owner in Settings. */
    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val me = ComponentName(this, BrowserPagesService::class.java).flattenToString()
        return enabled?.split(':')?.any { it.equals(me, ignoreCase = true) } == true
    }

    @Deprecated("Activity result API needs AndroidX; the spike stays dependency-free.")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || uri == null) return
        val rows = SpikeLog.readLines(this).mapNotNull(PageLogLine::parse).map(PageLogLine::csvRow)
        contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
            out.appendLine(PageLogLine.csvHeader())
            rows.forEach(out::appendLine)
        }
    }

    @Suppress("DEPRECATION")
    private fun exportCsv() {
        val intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("text/csv")
                .putExtra(Intent.EXTRA_TITLE, "hlweb-${System.currentTimeMillis()}.csv")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private companion object {
        const val REQUEST_EXPORT = 1
        const val DUMP_DELAY_MS = 5_000L
        const val REFRESH_AFTER_CLEAR_MS = 300L
        const val SHOWN_LINES = 200
        const val LOG_TEXT_SP = 11f
    }
}
