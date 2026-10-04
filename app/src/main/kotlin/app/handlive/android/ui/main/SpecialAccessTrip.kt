package app.handlive.android.ui.main

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.feature.connection.capability.NotificationAccess
import app.handlive.android.ui.system.PhoneEnvironmentReader
import app.handlive.android.ui.system.RestrictedSettings
import app.handlive.android.ui.system.SystemPages

/** The restricted system pages HandLive sends the user to, and App info where they are allowed (SET-01 API 6, 9). */
enum class SystemPage { ACCESSIBILITY, NOTIFICATION_ACCESS, APP_INFO }

/**
 * The trip to a system page: which one HandLive opened, so that the return can be judged (SET-01 E7, E8, E11), and
 * whether the user has already been to App info. Saved with the activity's state, so a configuration change on the
 * way (Accessibility is where font and display size change) does not lose it.
 */
class SpecialAccessTrip(
    private val pending: MutableState<SystemPage?> = mutableStateOf(null),
    private val appInfoVisited: MutableState<Boolean> = mutableStateOf(false),
) {
    fun leaveFor(page: SystemPage) {
        pending.value = page
    }

    /**
     * The page the user comes back from, forgotten at once so that a second resume (a rotation, the notification
     * shade) never shows field 14 twice.
     */
    fun takeReturn(): SystemPage? {
        val page = pending.value
        pending.value = null
        if (page == SystemPage.APP_INFO) appInfoVisited.value = true
        return page
    }

    /**
     * What comes before Accessibility or Notification access. After App info, a merely likely restriction no longer
     * stops the way (the user may just have allowed it; Android reports no difference): the page opens at once. A
     * certain block still shows field 14.
     */
    fun before(
        restriction: RestrictedSettings,
        notificationAccess: Boolean,
    ): Route? {
        val known = appInfoVisited.value && restriction == RestrictedSettings.LIKELY
        return restrictedSettingBefore(if (known) RestrictedSettings.NONE else restriction, notificationAccess)
    }
}

/** What HandLive reads on the way back from a system page. */
data class SpecialAccessState(
    val restriction: RestrictedSettings,
    val settings: HandLiveSettings,
    val accessibilityOn: Boolean,
    val notificationAccess: Boolean,
)

/**
 * SET-01 E8 and E11: back from the page HandLive opened without the access turned on, while Android may restrict it
 * and the user still wants the feature, field 14 shows again with "Open Settings". Nothing after App info (no
 * surprise navigation), and nothing while the feature or automatic sending is off.
 */
fun routeOnReturn(
    page: SystemPage?,
    now: SpecialAccessState,
): Route? {
    if (now.restriction == RestrictedSettings.NONE) return null
    val settings = now.settings
    return when (page) {
        SystemPage.ACCESSIBILITY -> {
            Route
                .RestrictedSetting(openAppInfo = true)
                .takeIf { settings.clipboardEnabled && settings.clipAutoSend && !now.accessibilityOn }
        }

        SystemPage.NOTIFICATION_ACCESS -> {
            Route
                .RestrictedSetting(notificationAccess = true, openAppInfo = true)
                .takeIf { settings.callEnabled && settings.callAppCalls && !now.notificationAccess }
        }

        SystemPage.APP_INFO, null -> {
            null
        }
    }
}

/** The trip, kept in the saved instance state. */
@Composable
fun rememberSpecialAccessTrip(): SpecialAccessTrip {
    val pending = rememberSaveable { mutableStateOf<SystemPage?>(null) }
    val appInfoVisited = rememberSaveable { mutableStateOf(false) }
    return SpecialAccessTrip(pending, appInfoVisited)
}

/** Judges each return to the app (E8, E11) and pushes field 14 again when [routeOnReturn] asks for it. */
@Composable
fun SpecialAccessReturn(main: MainContext) {
    val context = LocalContext.current
    val settings by rememberUpdatedState(rememberSettings(main))
    SpecialAccessReturnEffect(
        trip = main.trip,
        read = {
            SpecialAccessState(
                restriction = PhoneEnvironmentReader.restrictedSettings(context),
                settings = settings,
                accessibilityOn = main.dependencies.clipboard.consent.isServiceEnabled(),
                notificationAccess = NotificationAccess.granted(context),
            )
        },
        show = { route -> if (main.stack.lastOrNull() != route) main.push(route) },
    )
}

/** On every resume: the page the user comes back from, if any, [read] only then, and [show] what it calls for. */
@Composable
internal fun SpecialAccessReturnEffect(
    trip: SpecialAccessTrip,
    read: () -> SpecialAccessState,
    show: (Route) -> Unit,
) {
    val currentTrip by rememberUpdatedState(trip)
    val currentRead by rememberUpdatedState(read)
    val currentShow by rememberUpdatedState(show)
    LifecycleResumeEffect(Unit) {
        currentTrip.takeReturn()?.let { page -> routeOnReturn(page, currentRead())?.let(currentShow) }
        onPauseOrDispose {}
    }
}

/** CLIP-01 A2 / SET-01 step 12: Settings › Accessibility, where the user turns HandLive on. */
fun openAccessibility(
    context: Context,
    main: MainContext,
) {
    main.trip.leaveFor(SystemPage.ACCESSIBILITY)
    SystemPages.open(
        context,
        main.dependencies.clipboard.consent
            .settingsIntent(),
    )
}

/** SET-01 API 9: HandLive's entry in Notification access, else the list; the access is read again on resume. */
fun openNotificationAccess(
    context: Context,
    main: MainContext,
) {
    main.trip.leaveFor(SystemPage.NOTIFICATION_ACCESS)
    SystemPages.open(
        context,
        SystemPages.notificationListenerSettings(context),
        SystemPages.notificationListenerList(),
    )
}

/** Field 14's "Open Settings": App info, where ⋮ › "Allow restricted settings" is (API 6). */
fun openAppInfo(
    context: Context,
    main: MainContext,
) {
    main.trip.leaveFor(SystemPage.APP_INFO)
    SystemPages.open(context, SystemPages.appDetails(context))
}

/**
 * Turning automatic sending on once the consent exists (CLIP-01 A1 is not shown again): field 14 first when Android
 * may restrict Accessibility, else Accessibility itself.
 */
fun openAutoSendAccess(
    context: Context,
    main: MainContext,
) {
    val restricted = main.trip.before(PhoneEnvironmentReader.restrictedSettings(context), notificationAccess = false)
    if (restricted != null) main.push(restricted) else openAccessibility(context, main)
}
