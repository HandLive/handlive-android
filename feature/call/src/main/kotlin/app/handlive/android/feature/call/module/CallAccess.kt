package app.handlive.android.feature.call.module

import app.handlive.android.feature.call.context.PhoneState

/**
 * What the call group may do right now (SET-01 API 2): the setting `feature.call` (with telephony) and the runtime
 * permissions. Read at every request, since the user can change them at any time.
 */
interface CallAccess {
    /** `feature.call` is on and the phone has telephony. */
    fun enabled(): Boolean

    /** `READ_PHONE_STATE`, `READ_CALL_LOG`, `ANSWER_PHONE_CALLS`, `READ_CONTACTS`, as short names. */
    fun granted(permission: String): Boolean
}

/**
 * The public Telecom methods of CALL-02 and CALL-03 (C12, no `InCallService`); both are deprecated since API 29 but
 * still work. Each throws `SecurityException` without `ANSWER_PHONE_CALLS`.
 */
interface CallTelecom {
    /** `TelecomManager.acceptRingingCall()` (CALL-02 API 2). */
    fun acceptRingingCall()

    /** `TelecomManager.endCall()` (CALL-02 API 3): `false` when there is no call or Telecom refuses to end it. */
    fun endCall(): Boolean

    /** The phone's aggregate state right now, to tell why `endCall()` returned `false`; `null` when unknown. */
    fun phoneState(): PhoneState?
}
