package app.handlive.android.ui.main

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.handlive.android.core.design.theme.HandLiveTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * CLIP-01 A2: the consent is written even though the write outlasts a frame; the disclosure leaves the stack only
 * afterwards, so the scope that runs the write is not cancelled under it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConsentChoicesTest {
    @get:Rule
    val compose = createComposeRule()

    private val stack = mutableStateListOf<Route>(Route.Permissions(), Route.Consent)
    private val written = CompletableDeferred<Unit>()
    private var agreed = false
    private var manual = false
    private var afterAgree = 0

    private fun show() {
        compose.setContent {
            HandLiveTheme {
                if (stack.lastOrNull() == Route.Consent) {
                    ConsentChoices(
                        stack = stack,
                        agree = {
                            written.await()
                            agreed = true
                        },
                        sendManually = {
                            written.await()
                            manual = true
                        },
                        afterAgree = { afterAgree++ },
                    )
                }
            }
        }
    }

    @Test
    fun agreeIsWrittenBeforeTheDisclosureLeaves() {
        show()
        compose.onNodeWithText("Agree").performClick()
        compose.runOnIdle { written.complete(Unit) }
        compose.waitForIdle()
        assertTrue("the consent was written, not cancelled with the disclosure", agreed)
        assertEquals(listOf<Route>(Route.Permissions()), stack.toList())
        assertEquals(1, afterAgree)
    }

    @Test
    fun sendManuallyIsWrittenBeforeTheDisclosureLeaves() {
        show()
        compose.onNodeWithText("Send Manually").performClick()
        compose.runOnIdle { written.complete(Unit) }
        compose.waitForIdle()
        assertTrue("the choice was written, not cancelled with the disclosure", manual)
        assertEquals(listOf<Route>(Route.Permissions()), stack.toList())
        assertEquals(0, afterAgree)
    }
}
