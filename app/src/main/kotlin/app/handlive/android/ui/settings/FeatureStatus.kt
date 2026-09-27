package app.handlive.android.ui.settings

import app.handlive.android.core.strings.R

/** SET-01 field 10: the status of one feature card (and of its switch in SET-02). */
enum class FeatureStatus(
    val label: Int,
) {
    ON(R.string.common_on),
    OFF(R.string.common_off),

    /** Some permission is missing and the system can still ask for it: "Grant Permission" (field 11). */
    NEEDS_PERMISSION(R.string.permission_status_needs_permission),

    /** Every missing permission was denied for good: "Open Settings" (field 16, E5). */
    PERMISSION_DENIED(R.string.permission_status_denied),

    /** The phone has no telephony (SET-01 step 8): the capability reports SMS off. */
    UNSUPPORTED(R.string.permission_status_unsupported),
}

/**
 * The SMS permissions of SET-01 API 2 on this phone: whether it has telephony, which of `READ_SMS`, `SEND_SMS`,
 * `READ_CONTACTS`, `READ_PHONE_STATE` are missing (short names), and which of those the system will not ask for again.
 */
data class SmsAccessState(
    val telephony: Boolean = true,
    val missing: Set<String> = emptySet(),
    val deniedForGood: Set<String> = emptySet(),
) {
    /** SET-02 field 7 [enabled] combined with the permissions (SET-01 steps 8 and 10). */
    fun status(enabled: Boolean): FeatureStatus =
        when {
            !telephony -> FeatureStatus.UNSUPPORTED
            !enabled -> FeatureStatus.OFF
            missing.isEmpty() -> FeatureStatus.ON
            deniedForGood.containsAll(missing) -> FeatureStatus.PERMISSION_DENIED
            else -> FeatureStatus.NEEDS_PERMISSION
        }
}
