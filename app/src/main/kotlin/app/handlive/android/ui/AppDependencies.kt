package app.handlive.android.ui

import android.content.Context
import app.handlive.android.core.data.HandLiveData
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.pairing.PairingFeature

/** The process singletons the screens read and act on (A-UI talks to A-SVC and A-CLIP in the same process). */
class AppDependencies(
    context: Context,
) {
    val data: HandLiveData = HandLiveData.get(context)
    val runtime: ConnectionRuntime = ConnectionRuntime.get(context)
    val pairing: PairingFeature = PairingFeature.get(context)
    val clipboard: ClipboardFeature = ClipboardFeature.get(context)
}
