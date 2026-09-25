package app.handlive.android.feature.connection

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * The phone's display name (PAIR-01 field 9, `device_name` of CLIP-01 API 6): the name the user gave it
 * (`Settings.Global.DEVICE_NAME`), else the model, at most 64 characters.
 */
object LocalDeviceName {
    const val MAX_LENGTH = 64

    fun read(context: Context): String =
        Settings.Global
            .getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_LENGTH)
            ?: Build.MODEL
}
