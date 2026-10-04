package app.handlive.android.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.design.component.HLFeedback
import app.handlive.android.core.design.component.HLFeedbackHost
import app.handlive.android.core.design.component.HLFeedbackState
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.component.HLTabBar
import app.handlive.android.core.design.component.HLTabItem
import app.handlive.android.core.design.component.LocalHLFloatingBarInset
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.ServiceLauncher
import app.handlive.android.feature.connection.ServiceState
import app.handlive.android.feature.connection.notification.OpenRequest
import app.handlive.android.ui.AppDependencies
import app.handlive.android.ui.system.SystemPages
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The app after setup (03-android.md "Navigation"): the Devices and Settings tabs under a floating tab bar,
 * subscreens with the system back gesture, the Feedback HUD, and the warnings of SET-01 E1 and E2. The service is
 * started again whenever the app comes to the foreground and it is not running (E2).
 */
@Composable
fun MainScreen(
    dependencies: AppDependencies,
    startWithPairing: Boolean,
    openRequests: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val context = LocalContext.current
    val feedback = remember { HLFeedbackState() }
    val stack = remember { mutableStateListOf<Route>().apply { if (startWithPairing) add(Route.PairDevice) } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val serviceState by dependencies.runtime.state.collectAsStateWithLifecycle()
    val environment = rememberPhoneEnvironment()
    LifecycleResumeEffect(Unit) {
        dependencies.runtime.refreshEnvironment()
        val state = dependencies.runtime.state.value
        if (state == ServiceState.STOPPED || state == ServiceState.FAILED) ServiceLauncher.start(context)
        onPauseOrDispose {}
    }
    val banners =
        StatusBanners(
            notificationsOff = !environment.notificationsAllowed,
            serviceFailed = serviceState == ServiceState.FAILED,
            onOpenNotificationSettings = { SystemPages.open(context, SystemPages.notificationSettings(context)) },
            onRetryService = { ServiceLauncher.start(context) },
        )
    val main = MainContext(dependencies, stack, feedback, banners, rememberSpecialAccessTrip())
    MainEffects(main, openRequests)
    BackHandler(enabled = stack.isNotEmpty(), onBack = main::pop)
    // The tab bar floats over the tab content: its measured height, plus the gap under it, is what the content's
    // lists keep clear at the bottom (LocalHLFloatingBarInset), so the last row never ends under the bar.
    val density = LocalDensity.current
    var tabBarHeight by remember { mutableStateOf(0.dp) }
    val tabBarGap = HandLiveTheme.spacing.space8
    Box(modifier = Modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            val top = stack.lastOrNull()
            if (top == null) {
                CompositionLocalProvider(
                    LocalHLFloatingBarInset provides tabBarHeight + tabBarGap,
                ) { TabContent(main, tab) }
            } else {
                RouteContent(main, top, environment)
            }
        }
        if (stack.isEmpty()) {
            HLTabBar(
                items =
                    listOf(
                        HLTabItem(stringResource(R.string.pairing_devices), HLSymbol.Devices),
                        HLTabItem(stringResource(R.string.settings_title), HLSymbol.Settings),
                    ),
                selectedIndex = tab,
                onSelect = { tab = it },
                modifier =
                    Modifier
                        .align(
                            Alignment.BottomCenter,
                        ).navigationBarsPadding()
                        .padding(bottom = tabBarGap)
                        .onSizeChanged { tabBarHeight = with(density) { it.height.toDp() } },
            )
        }
        HLFeedbackHost(feedback, Modifier.align(Alignment.TopCenter))
    }
}

/** What the app watches while it is open: unpair notices, notification requests and returns from system pages. */
@Composable
private fun MainEffects(
    main: MainContext,
    openRequests: MutableStateFlow<String?>,
) {
    UnpairedByPeerNotice(main)
    OpenRequests(main, openRequests)
    SpecialAccessReturn(main)
}

/**
 * PAIR-03 field 5 on this phone: "<name> unpaired this device", or PAIR-02 E3 "<name> was unpaired from another
 * device" when the relay reported the revocation; shown once.
 */
@Composable
private fun UnpairedByPeerNotice(main: MainContext) {
    val unpair = main.dependencies.pairing.unpair
    val notice by unpair.unpairedByPeer.collectAsStateWithLifecycle()
    val text =
        notice?.let {
            stringResource(
                if (it.elsewhere) R.string.pairing_revoked_elsewhere else R.string.pairing_unpaired_by_peer,
                it.peerName,
            )
        }
    LaunchedEffect(text) {
        if (text != null) {
            main.feedback.show(HLFeedback(text, HLSymbol.Info))
            unpair.noticeShown()
        }
    }
}

/** A notification's screen: the SMS or calls primer of the permission suggestion (SET-01 field 17). */
@Composable
private fun OpenRequests(
    main: MainContext,
    requests: MutableStateFlow<String?>,
) {
    val request by requests.collectAsStateWithLifecycle()
    LaunchedEffect(request) {
        val route =
            when (request) {
                OpenRequest.SMS_PERMISSION -> Route.SmsPermission
                OpenRequest.CALL_PERMISSION -> Route.CallPermission
                else -> null
            }
        if (route != null && main.stack.lastOrNull() != route) main.push(route)
        requests.value = null
    }
}

@Composable
private fun TabContent(
    main: MainContext,
    tab: Int,
) {
    if (tab == 0) DevicesTab(main) else SettingsTab(main)
}

/** Settings are read as they are stored; the defaults show until DataStore answers. */
@Composable
fun rememberSettings(main: MainContext): HandLiveSettings {
    val settings by main.dependencies.data.settings.settings
        .collectAsStateWithLifecycle(HandLiveSettings())
    return settings
}
