package app.handlive.android.ui.main

import androidx.annotation.StringRes
import app.handlive.android.core.strings.R

/** Screens above the two tabs; the tab bar shows only when none is open. */
sealed interface Route {
    data object PairDevice : Route

    data class DeviceDetails(
        val pairId: String,
    ) : Route

    data object AutoClear : Route

    data object Language : Route

    /**
     * Settings › Permissions & Background with the feature list (SET-01 field 10); [afterFirstPairing]: opened over
     * the Devices tab right after the phone's first pairing (step 8).
     */
    data class Permissions(
        val afterFirstPairing: Boolean = false,
    ) : Route

    /** The Accessibility disclosure (CLIP-01 A2). */
    data object Consent : Route

    /** SET-01 field 14 before the Accessibility settings. */
    data object RestrictedSetting : Route

    /** SET-01 part B for SMS: the primer, then the system dialogs (steps 10–11). */
    data object SmsPermission : Route

    /** SET-01 part B for calls: the primer, then the system dialogs (steps 10–11). */
    data object CallPermission : Route
}

/** The back button names the screen it returns to (03-android.md "Navigation"): the Devices tab or Settings. */
@get:StringRes
val Route.Permissions.backLabel: Int
    get() = if (afterFirstPairing) R.string.pairing_devices else R.string.settings_title

/**
 * "Done" on the pairing result: the pairing screen closes, and after the phone's first pair the feature list opens
 * so the features that are on by default can get their permissions (SET-01 step 8). A second tap arriving before
 * the screen changed finds the pairing screen gone and does nothing.
 */
fun MutableList<Route>.closePairing(firstPair: Boolean) {
    if (lastOrNull() != Route.PairDevice) return
    removeAt(lastIndex)
    if (firstPair) add(Route.Permissions(afterFirstPairing = true))
}
