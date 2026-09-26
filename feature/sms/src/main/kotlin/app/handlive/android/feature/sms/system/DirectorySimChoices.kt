package app.handlive.android.feature.sms.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.handlive.android.feature.connection.capability.SimCard
import app.handlive.android.feature.connection.capability.SimDirectory
import app.handlive.android.feature.sms.send.SimChoices

/** [SimChoices] from the connection module's [SimDirectory]: no list without `READ_PHONE_STATE`. */
class DirectorySimChoices(
    context: Context,
    private val directory: SimDirectory = SimDirectory(context),
) : SimChoices {
    private val appContext = context.applicationContext

    override fun active(): List<SimCard>? =
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            directory.activeSims()
        } else {
            null
        }

    override fun defaultSmsSubId(): Int? = directory.defaultSmsSubId()

    override fun countryIso(subId: Int?): String? =
        active()?.firstOrNull { it.subId == subId }?.countryIso ?: directory.countryIso(subId)
}
