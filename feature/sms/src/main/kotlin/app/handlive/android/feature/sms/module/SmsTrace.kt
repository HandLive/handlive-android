package app.handlive.android.feature.sms.module

/**
 * The `HLBENCH/1` events of the SMS group (shared/tools/bench/README.md, T2.1) that the phone writes: identifiers,
 * boxes, states and error codes only — never text, numbers or names (0.6.5). A no-op outside debug builds.
 */
interface SmsTrace {
    /** A new provider row that goes out (SMS-02 steps 3–4); [onChangeAt] = the first `onChange` of the batch. */
    fun detected(
        messageKey: String,
        box: String,
        onChangeAt: Long,
        providerDate: Long,
    ) = Unit

    /** `sms/new` handed to one client's session; [viaRelay] = through the relay. */
    fun newSent(
        messageKey: String,
        peerDeviceId: String,
        viaRelay: Boolean,
    ) = Unit

    fun sendReceived(
        localId: String,
        peerDeviceId: String,
    ) = Unit

    fun sendAckSent(
        localId: String,
        peerDeviceId: String,
        ok: Boolean,
        code: String?,
    ) = Unit

    /** Every part reported by `SmsManager`: `sent` or `failed` with its code. */
    fun radioDone(
        localId: String,
        failed: Boolean,
        code: String?,
    ) = Unit

    fun statusSent(
        localId: String,
        peerDeviceId: String,
        status: String,
        code: String?,
    ) = Unit

    companion object {
        val NONE = object : SmsTrace {}
    }
}
