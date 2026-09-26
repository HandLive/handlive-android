package app.handlive.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.ui.main.MainScreen
import app.handlive.android.ui.onboarding.OnboardingFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The app's root (SET-01 step 1): `setup.completed_at` empty → part A of the setup, then the pairing screen;
 * set → the tabs. Nothing is shown until the settings are read, so the welcome screen never flashes.
 */
@Composable
fun HandLiveApp(
    dependencies: AppDependencies,
    openRequests: MutableStateFlow<String?> = MutableStateFlow(null),
) {
    val completedFlow =
        remember {
            dependencies.data.settings.settings
                .map { it.setupCompletedAt != null }
        }
    val completed by completedFlow.collectAsStateWithLifecycle(initialValue = null)
    var justFinished by rememberSaveable { mutableStateOf(false) }
    when (completed) {
        null -> Box(Modifier.fillMaxSize().background(HandLiveTheme.colors.systemBackground))
        false -> OnboardingFlow(dependencies) { justFinished = true }
        true -> MainScreen(dependencies, startWithPairing = justFinished, openRequests = openRequests)
    }
}
