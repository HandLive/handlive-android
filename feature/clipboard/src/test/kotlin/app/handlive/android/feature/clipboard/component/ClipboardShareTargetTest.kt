package app.handlive.android.feature.clipboard.component

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.handlive.android.feature.clipboard.component.ClipboardReadActivity.Launch
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** CLIP-01 API 4: the exported Share alias handles `ACTION_SEND` only; HandLive's own intents keep their extras. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardShareTargetTest {
    private val alias = ComponentName("app.handlive.android", ClipboardReadActivity.SHARE_ALIAS)
    private val activity = ComponentName("app.handlive.android", ClipboardReadActivity::class.java.name)

    @Test
    fun anotherAppThatStartsTheAliasWithoutSendIsRefused() {
        val main =
            Intent(
                Intent.ACTION_MAIN,
            ).setComponent(alias).putExtra("source", "manual").putExtra("mode", "verify")
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(main))
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(Intent().setComponent(alias)))
    }

    @Test
    fun aShareIsHandledAsAShareWhateverExtrasItCarries() {
        val share = Intent(Intent.ACTION_SEND).setComponent(alias).putExtra("mode", "verify")
        assertEquals(Launch.SHARE, ClipboardReadActivity.launchOf(share))
    }

    @Test
    fun anythingThatDoesNotNameTheActivityIsRefused() {
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(Intent().putExtra("source", "manual")))
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(null))
        val other = ComponentName("app.handlive.android", "app.handlive.android.SomethingElse")
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(Intent().setComponent(other)))
    }

    @Test
    fun theAliasTheSystemResolvesForAShareIsRefusedWithoutSend() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val share = Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName)
        val resolved =
            context.packageManager
                .queryIntentActivities(share, 0)
                .single()
                .activityInfo
        assertEquals(ClipboardReadActivity.SHARE_ALIAS, resolved.name)
        val direct = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(resolved.packageName, resolved.name))
        assertEquals(Launch.REFUSED, ClipboardReadActivity.launchOf(direct))
    }

    @Test
    fun handLivesOwnIntentsNameTheActivity() {
        assertEquals(
            Launch.OWN,
            ClipboardReadActivity.launchOf(Intent().setComponent(activity).putExtra("source", "manual")),
        )
    }
}
