package app.handlive.android.ui.main

/** Screens above the two tabs; the tab bar shows only when none is open. */
sealed interface Route {
    data object PairDevice : Route

    data class DeviceDetails(
        val pairId: String,
    ) : Route

    data object AutoClear : Route

    data object Language : Route

    data object Permissions : Route

    /** The Accessibility disclosure (CLIP-01 A2). */
    data object Consent : Route

    /** SET-01 field 14 before the Accessibility settings. */
    data object RestrictedSetting : Route

    /** SET-01 part B for SMS: the primer, then the system dialogs (steps 10–11). */
    data object SmsPermission : Route
}
