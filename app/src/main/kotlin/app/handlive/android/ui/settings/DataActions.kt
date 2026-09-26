package app.handlive.android.ui.settings

import app.handlive.android.feature.relay.ServerDeletion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** SET-02 fields 26 and 27. */
enum class DataAction { REMOVE_FROM_SERVER, DELETE_ALL }

/** Where flow A1–A6 stands, for the dialogs of fields 28–29 and E7. */
sealed interface DataStep {
    data object Idle : DataStep

    /** Fields 28–29: the warning, the button that names the action, and "Cancel". */
    data class Confirm(
        val action: DataAction,
    ) : DataStep

    data object Working : DataStep

    /** E7: "Couldn't connect to the server. Delete from this device anyway?" with "Delete" and "Cancel". */
    data object DeleteOffline : DataStep
}

/** Field 30: what "Remove Device from Server" ended with. */
enum class DataResult { REMOVED, UNREACHABLE }

/**
 * Flow A1–A6 of SET-02 on the phone. "Remove Device from Server": `DELETE /v1/devices/me?revoke_pairs=false`, then
 * "Removed from the server" or E5. "Delete All HandLive Data": the same with `revoke_pairs=true`, then the local
 * deletion ([eraseLocally], which restarts HandLive); with the relay unreachable the user may delete locally anyway
 * (E7). Cancel at any question changes nothing (E8). Runs in [scope], which outlives the screen.
 */
class DataActions(
    private val scope: CoroutineScope,
    private val deleteFromServer: suspend (revokePairs: Boolean) -> ServerDeletion,
    private val eraseLocally: suspend () -> Unit,
) {
    private val stepFlow = MutableStateFlow<DataStep>(DataStep.Idle)
    private val resultFlow = MutableSharedFlow<DataResult>(extraBufferCapacity = 1)

    val step: StateFlow<DataStep> = stepFlow.asStateFlow()
    val results: SharedFlow<DataResult> = resultFlow.asSharedFlow()

    fun ask(action: DataAction) {
        if (stepFlow.value == DataStep.Idle) stepFlow.value = DataStep.Confirm(action)
    }

    fun cancel() {
        if (stepFlow.value != DataStep.Working) stepFlow.value = DataStep.Idle
    }

    fun confirm() {
        val action = (stepFlow.value as? DataStep.Confirm)?.action ?: return
        stepFlow.value = DataStep.Working
        scope.launch {
            when (action) {
                DataAction.REMOVE_FROM_SERVER -> removeFromServer()
                DataAction.DELETE_ALL -> deleteAll()
            }
        }
    }

    /** E7 confirmed: delete on this device only; the relay drops the device after 180 idle days (0.9.4). */
    fun deleteAnyway() {
        if (stepFlow.value != DataStep.DeleteOffline) return
        stepFlow.value = DataStep.Working
        scope.launch { erase() }
    }

    private suspend fun removeFromServer() {
        val outcome = deleteFromServer(false)
        stepFlow.value = DataStep.Idle
        resultFlow.emit(if (outcome == ServerDeletion.DONE) DataResult.REMOVED else DataResult.UNREACHABLE)
    }

    private suspend fun deleteAll() {
        if (deleteFromServer(true) == ServerDeletion.DONE) erase() else stepFlow.value = DataStep.DeleteOffline
    }

    private suspend fun erase() {
        try {
            eraseLocally()
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") _: Exception,
        ) {
            // A deletion step failed twice (API 7): the user can try again; what is deleted stays deleted.
            stepFlow.value = DataStep.Idle
        }
    }
}
