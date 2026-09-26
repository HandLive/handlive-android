package app.handlive.android.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.data.settings.HandLiveSettings
import app.handlive.android.core.design.component.HLFeedback
import app.handlive.android.core.design.component.HLFeedbackHost
import app.handlive.android.core.design.component.HLFeedbackState
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.component.HLTabBar
import app.handlive.android.core.design.component.HLTabItem
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.core.strings.R
import app.handlive.android.feature.connection.ServiceLauncher
import app.handlive.android.feature.connection.ServiceState
import app.handlive.android.ui.AppDependencies
import app.handlive.android.ui.system.SystemPages

/**
 * The app after setup (03-android.md "Navigation"): the Devices and Settings tabs under a floating tab bar,
 * subscreens with the system back gesture, the Feedback HUD, and the warnings of SET-01 E1 and E2. The service is
 * started again whenever the app comes to the foreground and it is not running (E2).
 */
@Composable
fun MainScreen(
    dependencies: AppDependencies,
    startWithPairing: Boolean,
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
    val main = MainContext(dependencies, stack, feedback, banners)
    UnpairedByPeerNotice(main)
    BackHandler(enabled = stack.isNotEmpty(), onBack = main::pop)
    Box(modifier = Modifier.fillMaxSize().background(HandLiveTheme.colors.systemGroupedBackground)) {
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            val top = stack.lastOrNull()
            if (top == null) TabContent(main, tab) else RouteContent(main, top, environment)
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
                        .padding(bottom = HandLiveTheme.spacing.space8),
            )
        }
        HLFeedbackHost(feedback, Modifier.align(Alignment.TopCenter))
    }
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
