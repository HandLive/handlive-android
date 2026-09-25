package app.handlive.android.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.handlive.android.ui.system.PhoneEnvironment
import app.handlive.android.ui.system.PhoneEnvironmentReader

/** A value read from Android again on every resume (the user may have changed it in the system settings). */
@Composable
fun <T> rememberOnResume(read: () -> T): MutableState<T> {
    val state = remember { mutableStateOf(read()) }
    LifecycleResumeEffect(Unit) {
        state.value = read()
        onPauseOrDispose {}
    }
    return state
}

/** [PhoneEnvironment] kept current across resumes, including the asynchronous "Pause app activity" status. */
@Composable
fun rememberPhoneEnvironment(): PhoneEnvironment {
    val context = LocalContext.current
    val environment = remember { mutableStateOf(PhoneEnvironmentReader.read(context)) }
    LifecycleResumeEffect(Unit) {
        environment.value = PhoneEnvironmentReader.read(context, environment.value.unusedAppPause)
        PhoneEnvironmentReader.readUnusedAppPause(context) {
            environment.value =
                environment.value.copy(unusedAppPause = it)
        }
        onPauseOrDispose {}
    }
    return environment.value
}
