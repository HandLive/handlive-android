package app.handlive.android.ui.onboarding

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.ui.system.Manufacturer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** SET-01 field 8: the reason sentence shows on every manufacturer, above the manufacturer steps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AutostartScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val reason =
        "Some phones stop apps that run in the background. These settings keep HandLive connected to your Mac, " +
            "iPhone, and iPad."

    @Test
    fun theReasonShowsWithoutManufacturerSteps() {
        compose.setContent { HandLiveTheme { AutostartScreen(null, true, {}, {}, {}, {}) } }
        compose.onNodeWithText(reason).assertExists()
        compose.onNodeWithText("Open Manufacturer Settings").assertDoesNotExist()
    }

    @Test
    fun theReasonShowsAboveTheManufacturerSteps() {
        compose.setContent { HandLiveTheme { AutostartScreen(Manufacturer.SAMSUNG, false, {}, {}, {}, {}) } }
        val reasonTop = compose.onNodeWithText(reason).getUnclippedBoundsInRoot().top
        val stepsTop =
            compose
                .onNodeWithText("Battery › Background usage limits › Never sleeping apps › add HandLive")
                .getUnclippedBoundsInRoot()
                .top
        assertTrue(reasonTop < stepsTop)
    }

    @Test
    @Config(qualifiers = "vi")
    fun vietnameseReason() {
        compose.setContent { HandLiveTheme { AutostartScreen(Manufacturer.XIAOMI, true, {}, {}, {}, {}) } }
        compose
            .onNodeWithText(
                "Một số điện thoại dừng các ứng dụng chạy nền. Các cài đặt này giúp HandLive luôn kết nối với Mac, " +
                    "iPhone và iPad.",
            ).assertExists()
    }
}
