package app.handlive.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import app.handlive.android.core.strings.R
import app.handlive.android.feature.clipboard.ClipboardFeature

/**
 * Placeholder screen until the Phase 1 screens land. An [AppCompatActivity], so the in-app language chosen on
 * Android 10–12 (`AppCompatDelegate.setApplicationLocales`, SET-02 field 32) applies to it.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PlaceholderScreen() }
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

@Composable
private fun PlaceholderScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BasicText(text = stringResource(R.string.common_app_name))
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaceholderScreenPreview() {
    PlaceholderScreen()
}
