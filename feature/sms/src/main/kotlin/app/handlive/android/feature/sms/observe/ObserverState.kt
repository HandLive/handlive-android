package app.handlive.android.feature.sms.observe

/** `sms_observer_state.last_sms_id` (0.9.1): the largest `_id` already processed by the observer (SMS-02). */
interface ObserverState {
    /** `null` on the very first run. */
    suspend fun lastSmsId(): Long?

    suspend fun write(lastSmsId: Long)
}
