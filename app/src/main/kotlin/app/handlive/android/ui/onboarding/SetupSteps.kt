package app.handlive.android.ui.onboarding

import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.UnusedAppPause

/**
 * The steps of SET-01 part A (at most five screens; every one after the welcome can be skipped). [START_SERVICE]
 * has no screen: the service starts (step 5a) and the flow moves on, or shows [SERVICE_FAILED] (E2).
 */
enum class SetupStep { WELCOME, NOTIFICATIONS, START_SERVICE, SERVICE_FAILED, BACKGROUND, AUTOSTART, DONE }

/** The order of steps 1–7: a step skips itself when already satisfied. */
object SetupSteps {
    /** Step 3: Android 13+ without the notification permission explains it first. */
    fun afterWelcome(environment: PhoneEnvironment): SetupStep =
        if (environment.notificationPermissionRuntime && !environment.notificationsAllowed) {
            SetupStep.NOTIFICATIONS
        } else {
            SetupStep.START_SERVICE
        }

    /** Step 5b: the battery exemption, unless already granted. */
    fun afterServiceStarted(environment: PhoneEnvironment): SetupStep =
        if (environment.batteryExempt) afterBackground(environment) else SetupStep.BACKGROUND

    /** Steps 5c–5d: manufacturer instructions or "Pause app activity if unused", when there is something to do. */
    fun afterBackground(environment: PhoneEnvironment): SetupStep =
        if (environment.manufacturer != null || environment.unusedAppPause == UnusedAppPause.ENABLED) {
            SetupStep.AUTOSTART
        } else {
            SetupStep.DONE
        }
}
