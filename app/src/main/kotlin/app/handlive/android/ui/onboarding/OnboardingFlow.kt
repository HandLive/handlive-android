package app.handlive.android.ui.onboarding

import android.Manifest
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.feature.connection.ConnectionRuntime
import app.handlive.android.feature.connection.ServiceLauncher
import app.handlive.android.feature.connection.ServiceState
import app.handlive.android.ui.AppDependencies
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.PhoneEnvironmentReader
import app.handlive.android.ui.system.PrivacyPage
import app.handlive.android.ui.system.SystemPages
import app.handlive.android.ui.system.UnusedAppPause
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * SET-01 part A on screen: welcome → notifications (Android 13+) → service start → background running →
 * manufacturer instructions → `setup.completed_at`, then [onFinished] (PAIR-01). The environment is read again
 * on every resume, so a step already satisfied is skipped.
 */
@Composable
fun OnboardingFlow(
    dependencies: AppDependencies,
    onFinished: () -> Unit,
) {
    val context = LocalContext.current
    var step by rememberSaveable { mutableStateOf(SetupStep.WELCOME) }
    var environment by remember { mutableStateOf(PhoneEnvironmentReader.read(context)) }
    LifecycleResumeEffect(Unit) {
        environment = PhoneEnvironmentReader.read(context, environment.unusedAppPause)
        PhoneEnvironmentReader.readUnusedAppPause(context) { environment = environment.copy(unusedAppPause = it) }
        onPauseOrDispose {}
    }
    val notifications =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { step = SetupStep.START_SERVICE }
    val battery =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            step = SetupSteps.afterBackground(PhoneEnvironmentReader.read(context, environment.unusedAppPause))
        }
    LaunchedEffect(Unit) {
        // Step 2: the identity keys exist before anything else needs them.
        withContext(Dispatchers.IO) {
            dependencies.data.settings.markSetupStarted(System.currentTimeMillis())
            dependencies.data.identity
        }
    }
    LaunchedEffect(step) {
        when (step) {
            SetupStep.START_SERVICE -> {
                val running = startService(context, dependencies.runtime)
                step = if (running) SetupSteps.afterServiceStarted(environment) else SetupStep.SERVICE_FAILED
            }

            SetupStep.DONE -> {
                dependencies.data.settings.markSetupCompleted(System.currentTimeMillis())
                onFinished()
            }

            else -> {
                Unit
            }
        }
    }
    SetupStepContent(step, environment, onStep = { step = it }, onNotifications = {
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }, onBattery = {
        runCatching { battery.launch(SystemPages.batteryExemption(context)) }
            .onFailure { SystemPages.open(context, SystemPages.batterySettings()) }
    })
}

@Composable
private fun SetupStepContent(
    step: SetupStep,
    environment: PhoneEnvironment,
    onStep: (SetupStep) -> Unit,
    onNotifications: () -> Unit,
    onBattery: () -> Unit,
) {
    val context = LocalContext.current
    val displayLocale = LocalConfiguration.current.locales[0]
    when (step) {
        SetupStep.WELCOME -> {
            WelcomeScreen(
                onGetStarted = { onStep(SetupSteps.afterWelcome(environment)) },
                onPrivacy = { PrivacyPage.open(context, displayLocale) },
            )
        }

        SetupStep.NOTIFICATIONS -> {
            NotificationsPrimer(onNotifications)
        }

        SetupStep.SERVICE_FAILED -> {
            ServiceFailedScreen { onStep(SetupStep.START_SERVICE) }
        }

        SetupStep.BACKGROUND -> {
            BackgroundPrimer(onBattery)
        }

        SetupStep.AUTOSTART -> {
            val manufacturer = environment.manufacturer
            AutostartScreen(
                manufacturer = manufacturer,
                pauseEnabled = environment.unusedAppPause == UnusedAppPause.ENABLED,
                onOpenManufacturer = {
                    manufacturer?.let { SystemPages.open(context, SystemPages.manufacturerAutostart(context, it)) }
                },
                onOpenPause = { SystemPages.open(context, SystemPages.unusedAppRestrictions(context)) },
                onDone = { onStep(SetupStep.DONE) },
                onSkip = { onStep(SetupStep.DONE) },
            )
        }

        SetupStep.START_SERVICE, SetupStep.DONE -> {
            Box(Modifier.fillMaxSize().background(HandLiveTheme.colors.systemBackground))
        }
    }
}

/** Step 5a with E2: start the foreground service, retry once while in the foreground, then report. */
private suspend fun startService(
    context: Context,
    runtime: ConnectionRuntime,
): Boolean =
    (1..START_ATTEMPTS).any {
        ServiceLauncher.start(context) &&
            withTimeoutOrNull(START_TIMEOUT_MILLIS) {
                runtime.state.first { it == ServiceState.RUNNING || it == ServiceState.FAILED }
            } == ServiceState.RUNNING
    }

private const val START_ATTEMPTS = 2
private const val START_TIMEOUT_MILLIS = 15_000L
