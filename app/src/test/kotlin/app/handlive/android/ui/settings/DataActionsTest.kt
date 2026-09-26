package app.handlive.android.ui.settings

import app.handlive.android.feature.relay.ServerDeletion
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SET-02 flow A1–A6 on the phone: confirmation, the relay step, E5, E7 and E8. */
@OptIn(ExperimentalCoroutinesApi::class)
class DataActionsTest {
    private val serverCalls = mutableListOf<Boolean>()
    private var serverOutcome = ServerDeletion.DONE
    private var erased = 0
    private var eraseFails = false

    @Test
    fun removeFromServerAsksFirstThenReportsTheResult() =
        runTest {
            val actions = actions()
            actions.ask(DataAction.REMOVE_FROM_SERVER)
            assertEquals(DataStep.Confirm(DataAction.REMOVE_FROM_SERVER), actions.step.value)
            val result = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { actions.results.first() }

            actions.confirm()

            assertEquals(listOf(false), serverCalls)
            assertEquals(DataResult.REMOVED, result.await())
            assertEquals(DataStep.Idle, actions.step.value)
            assertEquals(0, erased)
        }

    @Test
    fun anUnreachableRelayChangesNothingWhenOnlyRemoving() =
        runTest {
            serverOutcome = ServerDeletion.UNREACHABLE
            val actions = actions()
            val result = backgroundScope.async(UnconfinedTestDispatcher(testScheduler)) { actions.results.first() }
            actions.ask(DataAction.REMOVE_FROM_SERVER)
            actions.confirm()
            assertEquals(DataResult.UNREACHABLE, result.await())
            assertEquals(DataStep.Idle, actions.step.value)
        }

    @Test
    fun deleteAllRevokesThePairsOnTheRelayThenErasesThisPhone() =
        runTest {
            val actions = actions()
            actions.ask(DataAction.DELETE_ALL)
            actions.confirm()
            assertEquals(listOf(true), serverCalls)
            assertEquals(1, erased)
        }

    @Test
    fun withTheRelayUnreachableTheUserMayDeleteLocallyAnyway() =
        runTest {
            serverOutcome = ServerDeletion.UNREACHABLE
            val actions = actions()
            actions.ask(DataAction.DELETE_ALL)
            actions.confirm()
            assertEquals(DataStep.DeleteOffline, actions.step.value)
            assertEquals(0, erased)

            actions.deleteAnyway()
            assertEquals(1, erased)
        }

    @Test
    fun cancelChangesNothing() =
        runTest {
            val actions = actions()
            actions.ask(DataAction.DELETE_ALL)
            actions.cancel()
            assertEquals(DataStep.Idle, actions.step.value)
            actions.confirm()
            actions.deleteAnyway()

            serverOutcome = ServerDeletion.UNREACHABLE
            actions.ask(DataAction.DELETE_ALL)
            actions.confirm()
            actions.cancel()
            assertEquals(DataStep.Idle, actions.step.value)
            assertEquals(listOf(true), serverCalls)
            assertEquals(0, erased)
        }

    @Test
    fun aFailedLocalDeletionLetsTheUserTryAgain() =
        runTest {
            eraseFails = true
            val actions = actions()
            actions.ask(DataAction.DELETE_ALL)
            actions.confirm()
            assertEquals(DataStep.Idle, actions.step.value)
            actions.ask(DataAction.DELETE_ALL)
            assertTrue(actions.step.value is DataStep.Confirm)
        }

    private fun TestScope.actions() =
        DataActions(
            TestScope(UnconfinedTestDispatcher(testScheduler)),
            { revokePairs ->
                serverCalls += revokePairs
                serverOutcome
            },
            {
                erased++
                check(!eraseFails) { "keystore error" }
            },
        )
}
