package app.handlive.android.feature.sms.module

/**
 * What the SMS group may do right now (SET-01 API 2): the setting `feature.sms` (with telephony) and the runtime
 * permissions. Read at every request, since the user can change them at any time.
 */
interface SmsAccess {
    /** `feature.sms` is on and the phone has telephony. */
    fun enabled(): Boolean

    /** `READ_SMS`, `SEND_SMS`, `READ_CONTACTS`, `READ_PHONE_STATE`, as short names. */
    fun granted(permission: String): Boolean
}
