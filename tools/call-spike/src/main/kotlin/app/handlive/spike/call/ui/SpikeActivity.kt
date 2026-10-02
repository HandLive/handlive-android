package app.handlive.spike.call.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.handlive.spike.call.R
import app.handlive.spike.call.service.CallNotificationsService
import app.handlive.spike.call.service.SpikeLog

/** Shows whether notification access is on, opens the settings the owner turns on by hand, and the last log lines. */
class SpikeActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val padding = (16 * resources.displayMetrics.density).toInt()
        status = TextView(this)
        log = TextView(this).apply { textSize = 11f }
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(padding, padding, padding, padding)
                addView(status)
                addView(
                    button(R.string.open_notification_access) { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
                )
                addView(button(R.string.open_accessibility) { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) })
                addView(button(R.string.refresh) { refresh() })
                addView(
                    button(R.string.clear_log) {
                        SpikeLog.clear(this@SpikeActivity)
                        refresh()
                    },
                )
                addView(log)
            }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val component = ComponentName(this, CallNotificationsService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        status.setText(if (component in enabled) R.string.status_on else R.string.status_off)
        log.text = SpikeLog.readLines(this).takeLast(LOG_LINES).joinToString("\n")
    }

    private fun button(
        label: Int,
        onClick: () -> Unit,
    ) = Button(this).apply {
        setText(label)
        setOnClickListener { onClick() }
    }

    private fun open(action: String) {
        startActivity(Intent(action))
    }

    private companion object {
        const val LOG_LINES = 40
    }
}
