package app.handlive.android.ui.system

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import java.util.Locale

/** SET-01 field 1: the privacy page behind "HandLive and Your Privacy", in the app's display language. */
object PrivacyPage {
    private const val ENGLISH = "https://github.com/HandLive/handlive/blob/main/docs/privacy.md"
    private const val VIETNAMESE = "https://github.com/HandLive/handlive/blob/main/docs/privacy.vi.md"
    private const val VIETNAMESE_LANGUAGE = "vi"

    /** The Vietnamese page for a Vietnamese display language, the English page otherwise. */
    fun url(displayLocale: Locale): String = if (displayLocale.language == VIETNAMESE_LANGUAGE) VIETNAMESE else ENGLISH

    /** Opens the page in the browser; nothing happens on a phone without one. */
    fun open(
        context: Context,
        displayLocale: Locale,
    ) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, url(displayLocale).toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
