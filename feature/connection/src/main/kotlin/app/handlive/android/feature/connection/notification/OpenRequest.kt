package app.handlive.android.feature.connection.notification

/**
 * The screen a notification opens in HandLive (SET-01 field 17): the launch intent's extra [EXTRA] names it, the UI
 * opens it once and clears the request.
 */
object OpenRequest {
    const val EXTRA = "app.handlive.extra.OPEN"

    /** The SMS primer (SET-01 part B). */
    const val SMS_PERMISSION = "sms_permission"

    /** The calls primer (SET-01 part B). */
    const val CALL_PERMISSION = "call_permission"
}
