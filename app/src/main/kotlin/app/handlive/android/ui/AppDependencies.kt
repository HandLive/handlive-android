package app.handlive.android.ui

import android.content.Context
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.pairing.PairingFeature
import app.handlive.android.feature.relay.RelayFeature
import app.handlive.android.settings.DataEraser
import app.handlive.android.ui.settings.DataActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The process singletons the screens read and act on (A-UI talks to A-SVC and A-CLIP in the same process). */
class AppDependencies(
    context: Context,
) {
    val data: HandLiveData = HandLiveData.get(context)
    val runtime: ConnectionRuntime = ConnectionRuntime.get(context)
    val pairing: PairingFeature = PairingFeature.get(context)
    val clipboard: ClipboardFeature = ClipboardFeature.get(context)
    val relay: RelayFeature = RelayFeature.get()

    /** SET-02 A1–A6; its scope outlives the Settings screen, so leaving it does not stop a deletion halfway. */
    val dataActions: DataActions =
        DataActions(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            relay::deleteFromServer,
            DataEraser(context)::erase,
        )
}
