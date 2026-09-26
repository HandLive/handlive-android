package app.handlive.android.feature.sms.send

import app.handlive.android.feature.connection.capability.SimCard

/** `SmsManager` of one SIM (SMS-04 API 3): split a text into parts and send them with one result intent per part. */
interface SmsRadio {
    /** `SmsManager.divideMessage` (the SIM's manager; [subId] `null` = the default one). */
    fun divide(
        subId: Int?,
        body: String,
    ): List<String>

    /**
     * `sendMultipartTextMessage(destination, null, parts, sentIntents, deliveryIntents)`, one "sent" and one
     * "delivered" intent per part carrying [localId], the part index and count. Throws when the call fails.
     */
    fun send(
        subId: Int?,
        destination: String,
        parts: List<String>,
        localId: String,
    )
}

/** The SIMs as SMS-04 API 1 logic 3 needs them. */
interface SimChoices {
    /** The active SIMs; `null` without `READ_PHONE_STATE` (the list cannot be read). */
    fun active(): List<SimCard>?

    /** `SmsManager.getDefaultSmsSubscriptionId()`; `null` when the phone asks every time. */
    fun defaultSmsSubId(): Int?

    /** The country for number normalization (the SIM's, else the network's or the phone's region). */
    fun countryIso(subId: Int?): String?
}
