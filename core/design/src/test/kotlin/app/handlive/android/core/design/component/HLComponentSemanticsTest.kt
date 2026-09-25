package app.handlive.android.core.design.component

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.handlive.android.core.design.theme.HandLiveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TalkBack đọc được nhãn, vai trò và trạng thái của thành phần (03-android.md, 08-kha-nang-tiep-can.md). Runs in
 * Vietnamese, so the state texts come from `values-vi` of the generated catalog; one test checks the English default.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "vi")
class HLComponentSemanticsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    private fun hasState(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    @Test
    fun switchRowReadsTitleRoleAndStateAndTogglesFromWholeRow() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                var checked by remember { mutableStateOf(false) }
                HLGroupedList {
                    section(title = "Bảng nhớ tạm") {
                        switchRow(title = "Đồng bộ bảng nhớ tạm", checked = checked, onCheckedChange = { checked = it })
                    }
                }
            }
        }
        val row = compose.onNodeWithText("Đồng bộ bảng nhớ tạm")
        row.assert(hasRole(Role.Switch)).assert(hasState("Tắt")).assertIsEnabled()
        row.performClick()
        row.assert(hasState("Bật"))
        compose.onNodeWithText("Bảng nhớ tạm").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun unavailableSwitchRowIsDisabledAndReadsReason() {
        compose.setContent {
            HandLiveTheme(darkTheme = true, highContrast = true) {
                HLGroupedList {
                    section {
                        switchRow(
                            title = "Tin nhắn SMS",
                            checked = false,
                            onCheckedChange = {},
                            unavailableReason = "Thiếu quyền SMS trên điện thoại",
                        )
                    }
                }
            }
        }
        compose
            .onNodeWithText("Tin nhắn SMS", substring = true)
            .assertIsNotEnabled()
            .assert(hasText("Thiếu quyền SMS trên điện thoại"))
    }

    @Test
    fun standaloneSwitchHasSwitchRoleAndSpokenState() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                var checked by remember { mutableStateOf(true) }
                HLSwitch(checked = checked, onCheckedChange = { checked = it })
            }
        }
        val node = compose.onNode(hasRole(Role.Switch))
        node.assert(hasState("Bật"))
        node.performClick()
        node.assert(hasState("Tắt"))
    }

    @Test
    fun statusIndicatorReadsFullSentenceAsPoliteLiveRegion() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                Column {
                    HLStatusIndicator(status = HLConnectionStatus.ConnectedWiFi, deviceName = "Pixel 8 của Lan")
                    HLStatusIndicator(status = HLConnectionStatus.ConnectedInternet, deviceName = "iPad")
                    // Not connected: the device name is not read.
                    HLStatusIndicator(status = HLConnectionStatus.Disconnected, deviceName = "Pixel 8 của Lan")
                }
            }
        }
        compose
            .onNodeWithContentDescription("Đã kết nối qua Wi-Fi với Pixel 8 của Lan")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.onNodeWithContentDescription("Đã kết nối qua Internet với iPad").assertExists()
        compose.onNodeWithContentDescription("Mất kết nối").assertExists()
    }

    @Test
    @Config(qualifiers = "en")
    fun englishIsTheDefaultLanguage() {
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = false) {
                Column {
                    HLStatusIndicator(status = HLConnectionStatus.ConnectedWiFi, deviceName = "Pixel 8")
                    HLSwitch(checked = true, onCheckedChange = {}, modifier = Modifier.testTag(SWITCH))
                }
            }
        }
        compose.onNodeWithContentDescription("Connected via Wi-Fi to Pixel 8").assertExists()
        compose.onNodeWithTag(SWITCH).assert(hasState("On"))
    }

    @Test
    fun pillShowsShortLabelButReadsFullSentence() {
        compose.setContent {
            HandLiveTheme(darkTheme = true, highContrast = false) {
                HLStatusIndicator(
                    status = HLConnectionStatus.ConnectedWiFi,
                    deviceName = "Pixel 8 của Lan",
                    variant = HLStatusIndicatorVariant.Pill,
                )
            }
        }
        compose.onNodeWithContentDescription("Đã kết nối qua Wi-Fi với Pixel 8 của Lan").assertExists()
        // Nhãn ngắn chỉ để nhìn; TalkBack không đọc "LAN" mà đọc câu đầy đủ.
        compose.onNodeWithText("LAN", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("LAN").assertDoesNotExist()
    }

    @Test
    fun buttonHasButtonRoleAndDisabledStateIsExposed() {
        var clicks = 0
        compose.setContent {
            HandLiveTheme(darkTheme = false, highContrast = true) {
                Column {
                    HLButton(text = "Ghép nối", onClick = { clicks++ })
                    HLButton(text = "Hủy ghép nối", onClick = {}, style = HLButtonStyle.Destructive, enabled = false)
                }
            }
        }
        compose.onNodeWithText("Ghép nối").assert(hasRole(Role.Button)).performClick()
        compose.runOnIdle { assertEquals(1, clicks) }
        compose.onNodeWithText("Hủy ghép nối").assertIsNotEnabled()
    }

    private companion object {
        const val SWITCH = "switch"
    }
}
