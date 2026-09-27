package app.handlive.android.feature.sms.system

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import app.handlive.android.feature.sms.send.SmsRadio

/**
 * [SmsRadio] on `SmsManager` (SMS-04 API 3): the manager of the chosen SIM — `createForSubscriptionId` on API 31+,
 * `getSmsManagerForSubscriptionId` on API 29–30 — with one "sent" and one "delivered" intent per part, both explicit
 * broadcasts to [SmsResultReceiver]. The system writes the message into the Sent box itself (0.9.2).
 */
class AndroidSmsRadio(
    context: Context,
) : SmsRadio {
    private val appContext = context.applicationContext

    override fun divide(
        subId: Int?,
        body: String,
    ): List<String> = manager(subId).divideMessage(body).orEmpty()

    override fun send(
        subId: Int?,
        destination: String,
        parts: List<String>,
        localId: String,
    ) {
        val sent = ArrayList(parts.indices.map { SmsResultReceiver.sentIntent(appContext, localId, it, parts.size) })
        val delivered =
            ArrayList(parts.indices.map { SmsResultReceiver.deliveredIntent(appContext, localId, it, parts.size) })
        manager(subId).sendMultipartTextMessage(destination, null, ArrayList(parts), sent, delivered)
    }

    private fun manager(subId: Int?): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val base = checkNotNull(appContext.getSystemService(SmsManager::class.java)) { "no SmsManager" }
            if (subId != null) base.createForSubscriptionId(subId) else base
        } else {
            @Suppress("DEPRECATION")
            if (subId != null) SmsManager.getSmsManagerForSubscriptionId(subId) else SmsManager.getDefault()
        }
}
