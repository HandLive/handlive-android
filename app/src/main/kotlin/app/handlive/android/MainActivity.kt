package app.handlive.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.feature.clipboard.ClipboardFeature
import app.handlive.android.feature.sms.system.SmsPermissionNotifier
import app.handlive.android.ui.AppDependencies
import app.handlive.android.ui.HandLiveApp
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * A-UI's single activity: edge-to-edge (required with targetSdk 35), the HandLive theme, and the clipboard focus
 * hooks. An [AppCompatActivity], so the in-app language chosen on Android 10–12 (SET-02 field 32) applies to it.
 */
class MainActivity : AppCompatActivity() {
    /** A screen a notification asked for (SET-01 field 17 → the SMS primer); the UI clears it once opened. */
    private val openRequests = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) openRequests.value = intent?.getStringExtra(SmsPermissionNotifier.EXTRA_OPEN)
        val dependencies = AppDependencies(applicationContext)
        setContent { HandLiveTheme { HandLiveApp(dependencies, openRequests) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(SmsPermissionNotifier.EXTRA_OPEN)?.let { openRequests.value = it }
    }

    // CLIP-01 API 2 logic 6 and CLIP-05 E3: with focus HandLive reads the clipboard directly and verifies its clip.
    override fun onResume() {
        super.onResume()
        ClipboardFeature.get(this).onAppResumed()
    }

    override fun onPause() {
        ClipboardFeature.get(this).onAppPaused()
        super.onPause()
    }
}
