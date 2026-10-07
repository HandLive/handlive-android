package app.handlive.android.feature.call.appcall

import android.content.Context
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * The phone's audio mode, as a detached app call needs it (CALL-05 E11): a call whose in-call notification was swiped
 * away goes on while a call holds the mode `MODE_IN_COMMUNICATION`, and ends when the mode leaves it. The mode belongs
 * to the whole phone, not to one app.
 */
interface CommunicationMode {
    /** A call holds the audio mode now and its end can be watched (API 31+, `MODE_IN_COMMUNICATION`). */
    fun inCommunication(): Boolean

    /**
     * [left] runs, on any thread, when the mode leaves communication — at once when it already has — until [stop]. A
     * new watch replaces the previous one.
     */
    fun watch(left: () -> Unit)

    fun stop()

    companion object {
        /** No audio mode to follow: a call whose in-call notification goes without the app ends at once. */
        val NONE =
            object : CommunicationMode {
                override fun inCommunication() = false

                override fun watch(left: () -> Unit) = left()

                override fun stop() = Unit
            }
    }
}

/**
 * [CommunicationMode] from `AudioManager`: `getMode()` and, from API 31, `addOnModeChangedListener` (no permission).
 * Below API 31 the mode cannot be watched, so nothing is ever in communication. A system service that refuses counts as
 * no call.
 */
class AndroidCommunicationMode(
    context: Context,
) : CommunicationMode {
    private val appContext = context.applicationContext
    private val audio: AudioManager? = appContext.getSystemService(AudioManager::class.java)

    /** The registered `AudioManager.OnModeChangedListener`, typed loosely so the class loads below API 31. */
    private var listener: Any? = null

    override fun inCommunication(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { audio?.mode == AudioManager.MODE_IN_COMMUNICATION }.getOrDefault(false)

    override fun watch(left: () -> Unit) {
        stop()
        val registered = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && audio != null && register(audio, left)
        // Without a listener nothing would ever tell the end; the mode may also have changed before it was in place.
        if (!registered || !inCommunication()) left()
    }

    override fun stop() {
        val registered = listener ?: return
        listener = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) unregister(registered)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun register(
        audio: AudioManager,
        left: () -> Unit,
    ): Boolean {
        val changed =
            AudioManager.OnModeChangedListener { mode -> if (mode != AudioManager.MODE_IN_COMMUNICATION) left() }
        return runCatching { audio.addOnModeChangedListener(appContext.mainExecutor, changed) }
            .onSuccess { listener = changed }
            .isSuccess
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun unregister(registered: Any) {
        runCatching { audio?.removeOnModeChangedListener(registered as AudioManager.OnModeChangedListener) }
    }
}
