package app.handlive.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.handlive.android.core.design.component.HLSymbol
import app.handlive.android.core.design.component.HLTabBar
import app.handlive.android.core.design.component.HLTabItem
import app.handlive.android.core.design.theme.HandLiveTheme
import app.handlive.android.ui.main.StatusBanners
import app.handlive.android.ui.settings.SettingsScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** A1.4 acceptance: TalkBack reads every screen's title as a heading and every switch with its role and state. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "vi-w411dp-h2000dp")
class ScreenSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun hasState(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun everyScreenHasAHeading() {
        var current by mutableStateOf(ScreenCatalog.screens.keys.first())
        compose.setContent { Scaled(fontScale = 1f) { key(current) { ScreenCatalog.screens.getValue(current)() } } }
        ScreenCatalog.screens.keys.forEach { name ->
            compose.runOnUiThread { current = name }
            compose.waitForIdle()
            val headings =
                compose
                    .onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading),
                    ).fetchSemanticsNodes()
            assertTrue("$name has no heading for TalkBack", headings.isNotEmpty())
        }
    }

    @Test
    fun settingsSwitchesReadTheirStateInVietnamese() {
        compose.setContent {
            HandLiveTheme {
                SettingsScreen(UiSamples.settings, StatusBanners(), UiSamples.NoActions, "Theo hệ thống")
            }
        }
        compose.onNodeWithText("Đồng bộ bảng nhớ tạm").assert(hasRole(Role.Switch)).assert(hasState("Bật"))
        compose.onNodeWithText("Kết nối qua Internet").assert(hasRole(Role.Switch)).assert(hasState("Bật"))
        compose.onNodeWithText("Tự gửi khi sao chép").assert(hasRole(Role.Switch))
    }

    @Test
    fun theTabBarReadsSelectedTabs() {
        compose.setContent {
            HandLiveTheme {
                HLTabBar(
                    listOf(HLTabItem("Thiết bị", HLSymbol.Devices), HLTabItem("Cài đặt", HLSymbol.Settings)),
                    0,
                    {},
                )
            }
        }
        compose
            .onNodeWithText("Thiết bị")
            .assert(hasRole(Role.Tab))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
    }
}
