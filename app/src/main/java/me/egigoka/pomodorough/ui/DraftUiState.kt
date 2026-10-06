package me.egigoka.pomodorough.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue

// R43-A07: draft lifetimes across tab navigation and recreation.
// Task draft and room name are non-sensitive and survive tab switches,
// config recreation, and process recreation via SavedState.
// Join/invite code is sensitive (grants full read/write, reveals peer
// IPs): it is memory-only, survives tab switches and config recreation
// in ViewModel memory, but must not survive process recreation, so it is
// never in SavedState and has no Saver.
class TaskDraftUiState {
    var draft by mutableStateOf("")
    var submissionError by mutableStateOf<String?>(null)

    fun onDraftChange(value: String) {
        draft = value
        submissionError = null
    }

    fun onSubmissionResult(accepted: Boolean, failureCopy: String) {
        if (accepted) {
            draft = ""
            submissionError = null
        } else {
            submissionError = failureCopy
        }
    }
}

internal val TaskDraftSaver: Saver<TaskDraftUiState, Any> = listSaver(
    save = { listOf(it.draft, it.submissionError) },
    restore = ::restoreTaskDraft,
)

internal fun restoreTaskDraft(saved: List<Any?>): TaskDraftUiState? {
    if (saved.size != 2) return null
    return TaskDraftUiState().apply {
        draft = saved[0] as? String ?: return null
        submissionError = saved[1] as? String
    }
}

class RoomNameDraftState {
    var name by mutableStateOf("")

    fun onChange(value: String) {
        name = value
    }

    fun clear() {
        name = ""
    }
}

internal val RoomNameDraftSaver: Saver<RoomNameDraftState, Any> = listSaver(
    save = { listOf(it.name) },
    restore = ::restoreRoomNameDraft,
)

internal fun restoreRoomNameDraft(saved: List<Any?>): RoomNameDraftState? {
    if (saved.size != 1) return null
    return RoomNameDraftState().apply {
        name = saved[0] as? String ?: return null
    }
}

class JoinInviteDraftState {
    var code by mutableStateOf("")
}

internal fun shouldClearNetworkDrafts(previousRoomId: String?, currentRoomId: String?): Boolean =
    previousRoomId == null && currentRoomId != null
