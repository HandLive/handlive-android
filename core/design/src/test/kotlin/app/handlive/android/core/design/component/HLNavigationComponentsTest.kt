package app.handlive.android.core.design.component

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.handlive.android.core.design.theme.HandLiveTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Tab bar, alert, action sheet, feedback HUD and the new list rows: roles, states and callbacks for TalkBack. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HLNavigationComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun tabsAreSelectableTabs() {
        compose.setContent {
            HandLiveTheme {
                var selected by remember { mutableIntStateOf(0) }
                HLTabBar(
                    items = listOf(HLTabItem("Devices", HLSymbol.Devices), HLTabItem("Settings", HLSymbol.Settings)),
                    selectedIndex = selected,
                    onSelect = { selected = it },
                )
            }
        }
        compose.onNodeWithText("Devices").assertIsSelected()
        compose.onNodeWithText("Settings").assertIsNotSelected().performClick()
        compose.onNodeWithText("Settings").assertIsSelected().assert(hasRole(Role.Tab))
    }

    @Test
    fun alertPutsCancelFirstAndCallsBack() {
        var confirmed = 0
        var dismissed = 0
        compose.setContent {
            HandLiveTheme {
                HLAlert(
                    title = "Unpair MacBook?",
                    message = "This can't be undone.",
                    confirmLabel = "Unpair",
                    onConfirm = { confirmed++ },
                    dismissLabel = "Cancel",
                    onDismiss = { dismissed++ },
                    destructive = true,
                )
            }
        }
        compose.onNodeWithText("Unpair MacBook?").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Unpair").assert(hasRole(Role.Button)).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }

    @Test
    fun actionSheetRunsTheActionOrCancels() {
        var unpaired = false
        var cancelled = false
        compose.setContent {
            HandLiveTheme {
                HLActionSheet(
                    title = "Unpair MacBook?",
                    message = null,
                    actions = listOf(HLSheetAction("Unpair", destructive = true) { unpaired = true }),
                    cancelLabel = "Cancel",
                    onDismiss = { cancelled = true },
                )
            }
        }
        compose.onNodeWithText("Unpair").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(unpaired && cancelled)
    }

    @Test
    fun feedbackIsAPoliteLiveRegionForOneAndAHalfSeconds() {
        val state = HLFeedbackState().apply { show(HLFeedback("Paired")) }
        compose.mainClock.autoAdvance = false
        compose.setContent { HandLiveTheme { Box { HLFeedbackHost(state) } } }
        compose.mainClock.advanceTimeBy(100)
        compose
            .onNodeWithText("Paired")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithText("Paired").assertDoesNotExist()
    }

    @Test
    fun checkRowsAreRadioButtonsAndValueRowsReadAsOne() {
        compose.setContent {
            HandLiveTheme {
                var choice by remember { mutableStateOf(60) }
                HLGroupedList {
                    section {
                        checkRow("Off", selected = choice == 0) { choice = 0 }
                        checkRow("After 1 Minute", selected = choice == 60) { choice = 60 }
                    }
                    section { valueRow("Security Code", "fc64 7e0b") }
                    section {
                        switchRow(
                            "Sync Images",
                            checked = true,
                            onCheckedChange = {},
                            description = "Copied images are sent too.",
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("After 1 Minute").assertIsSelected().assert(hasRole(Role.RadioButton))
        compose.onNodeWithText("Off").performClick().assertIsSelected()
        compose.onNodeWithText("Security Code").assertExists()
        compose.onNodeWithText("Copied images are sent too.", useUnmergedTree = true).assertExists()
    }
}
