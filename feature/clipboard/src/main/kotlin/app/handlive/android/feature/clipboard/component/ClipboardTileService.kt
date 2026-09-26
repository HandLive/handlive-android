package app.handlive.android.feature.clipboard.component

import android.annotation.SuppressLint
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.handlive.android.core.strings.R
import app.handlive.android.core.transport.capability.Feature
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.session.PeerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * CLIP-01 API 3: the "Send Clipboard" Quick Settings tile. Active when a client has active clipboard, with the
 * subtitle "To <client>", "To N devices" or "Not connected" (field 5); a tap opens `ClipboardReadActivity` with
 * `source = manual` and collapses the panel.
 */
class ClipboardTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        scope =
            CoroutineScope(SupervisorJob() + Dispatchers.Main).also { listening ->
                ConnectionRuntime
                    .get(this)
                    .sessions
                    .onEach { update(it.values.filter { session -> session.isEffective(Feature.CLIPBOARD) }) }
                    .launchIn(listening)
            }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun(::open) else open()
    }

    /** API 34+ requires the PendingIntent variant (logic 3). */
    private fun open() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(ClipboardReadActivity.manualPendingIntent(this))
        } else {
            openBeforeApi34()
        }
    }

    // Reached only below API 34, where the Intent variant is the only one: the PendingIntent overload exists from
    // API 34 on, and the panel must collapse or the activity cannot take focus.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openBeforeApi34() = startActivityAndCollapse(ClipboardReadActivity.manualIntent(this))

    private fun update(targets: List<PeerSession>) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.clipboard_send)
        tile.state = if (targets.isEmpty()) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.subtitle =
            when (targets.size) {
                0 -> getString(R.string.clipboard_tile_not_connected)
                1 -> getString(R.string.clipboard_tile_to_device, targets.single().peerName)
                else -> resources.getQuantityString(R.plurals.clipboard_tile_to_count, targets.size, targets.size)
            }
        tile.updateTile()
    }
}
