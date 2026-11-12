package me.egigoka.pomodorough.data

import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A task delete the user can still undo from the snackbar. */
data class PendingTaskDeletion(val taskId: String, val title: String)

/** Durable note of a task delete that has not reached its owner's queue for good. */
internal data class PendingTaskDeletionMarker(val taskId: String, val workspaceOwner: String) {
    companion object {
        // Owner of the local workspace that has no account.
        const val SignedOutWorkspaceOwner = "signed-out-local-workspace"
    }
}

/** The open undo window: the task, its title for the snackbar and the workspace it was deleted in. */
internal data class OwnedTaskDeletion(val taskId: String, val title: String, val workspaceOwner: String) {
    val marker get() = PendingTaskDeletionMarker(taskId, workspaceOwner)
}

/** What the workspace did with a marked delete. */
internal enum class MarkedDeletion { Committed, AlreadyGone, Refused }

/** The account change that settles an open undo window inside the action lock. */
internal enum class TaskDeletionSessionChange {
    // The session of the same owner ends; the workspace stays with that owner.
    SessionEnds,

    // Another account signs in; the switch prompt can discard this owner's queue.
    OwnerLeaves,

    // The owner signs in again, or an account takes over the signed-out workspace.
    AccountJoins,
}

/** What a leftover replay knows about the owner's tasks. */
internal enum class LeftoverReplay {
    // Only the local workspace: an account task that is absent keeps its marker.
    LocalWorkspace,

    // The owner's sign-in has just installed the server copy: an absent task is gone.
    ServerCopy,
}

// The markers survive a process kill, so the delete is replayed at the next
// start. They are written with commit(), before the row is hidden. Each owner
// keeps its own set: a marker of an owner that left waits for that owner's next
// sign-in and is never cleared by another owner.
internal class PendingTaskDeletionMarkerStore(private val preferences: SharedPreferences) {
    @Synchronized
    fun write(marker: PendingTaskDeletionMarker) {
        val taskIds = read(marker.workspaceOwner) + marker.taskId
        preferences.edit(commit = true) { putStringSet(ownerKey(marker.workspaceOwner), taskIds) }
    }

    @Synchronized
    fun read(workspaceOwner: String): Set<String> =
        preferences.getStringSet(ownerKey(workspaceOwner), null)?.toSet().orEmpty()

    // The marker and any supersede intent for it leave in one write.
    @Synchronized
    fun remove(marker: PendingTaskDeletionMarker) {
        val owner = marker.workspaceOwner
        preferences.edit(commit = true) {
            putOrRemove(ownerKey(owner), read(owner) - marker.taskId)
            putOrRemove(supersedeKey(owner), superseding(owner) - marker.taskId)
        }
    }

    // UX round 2, rework2: a create or restore of a marked task supersedes the old delete. Room
    // and these preferences cannot share one write, so the intent is written before the task is
    // saved, and the marker leaves with the intent after. A replay that finds an intent decides
    // by the task: present means the save landed, absent means it did not. Returns false when
    // the task has no marker, so an ordinary save writes nothing here.
    @Synchronized
    fun beginSupersede(marker: PendingTaskDeletionMarker): Boolean {
        val owner = marker.workspaceOwner
        if (marker.taskId !in read(owner)) return false
        preferences.edit(commit = true) { putOrRemove(supersedeKey(owner), superseding(owner) + marker.taskId) }
        return true
    }

    // The save did not land: the intent leaves and the marker stays.
    @Synchronized
    fun abandonSupersede(marker: PendingTaskDeletionMarker) {
        val owner = marker.workspaceOwner
        preferences.edit(commit = true) { putOrRemove(supersedeKey(owner), superseding(owner) - marker.taskId) }
    }

    /** Task ids of [workspaceOwner] whose save started to supersede a marker. */
    @Synchronized
    fun superseding(workspaceOwner: String): Set<String> =
        preferences.getStringSet(supersedeKey(workspaceOwner), null)?.toSet().orEmpty()

    private fun SharedPreferences.Editor.putOrRemove(key: String, taskIds: Set<String>) {
        if (taskIds.isEmpty()) remove(key) else putStringSet(key, taskIds)
    }

    private fun ownerKey(workspaceOwner: String) = "$OwnerKeyPrefix$workspaceOwner"

    private fun supersedeKey(workspaceOwner: String) = "$SupersedeKeyPrefix$workspaceOwner"

    companion object {
        const val PreferencesName = "pending-task-deletion"
        private const val OwnerKeyPrefix = "owner:"
        private const val SupersedeKeyPrefix = "superseding:"
    }
}

/**
 * UX round 2: the undo window of a task delete. The repository owns it, so the
 * tile, the notification actions and the screen all see the same pending delete,
 * and a rotation does not lose it. At most one deletion waits.
 */
internal class PendingTaskDeletionWindow(private val markers: PendingTaskDeletionMarkerStore) {
    private var openDeletion: OwnedTaskDeletion? = null
    private val shown = MutableStateFlow<PendingTaskDeletion?>(null)
    val pending: StateFlow<PendingTaskDeletion?> = shown

    @Synchronized
    fun open(task: FocusTask, workspaceOwner: String) {
        markers.write(PendingTaskDeletionMarker(task.id, workspaceOwner))
        show(OwnedTaskDeletion(task.id, task.title, workspaceOwner))
    }

    // The marker goes first, so a kill after Undo never replays the delete.
    @Synchronized
    fun undo() {
        val deletion = openDeletion ?: return
        markers.remove(deletion.marker)
        show(null)
    }

    // Ends the window for the one caller that will settle the delete. Its marker stays.
    @Synchronized
    fun take(): OwnedTaskDeletion? = openDeletion.also { show(null) }

    // Puts a taken deletion back when the workspace refused it and the user can still undo.
    @Synchronized
    fun restore(deletion: OwnedTaskDeletion) {
        if (openDeletion == null) show(deletion)
    }

    fun forget(marker: PendingTaskDeletionMarker) = markers.remove(marker)

    // A save of a marked task by its owner starts here; see the marker store.
    fun beginSupersede(marker: PendingTaskDeletionMarker): Boolean = markers.beginSupersede(marker)

    // The owner created or restored the task, so the old delete never replays.
    fun supersede(marker: PendingTaskDeletionMarker) = markers.remove(marker)

    // The owner's tasks in [taskIds] supersede its leftover markers. The open window keeps its own.
    fun supersedeLeftovers(workspaceOwner: String, taskIds: Collection<String>) {
        (leftoverTaskIds(workspaceOwner) intersect taskIds.toSet())
            .forEach { supersede(PendingTaskDeletionMarker(it, workspaceOwner)) }
    }

    // Settles the intents an earlier process left: a task that is present was saved after its
    // delete, so its marker leaves; for an absent task only the intent leaves.
    @Synchronized
    fun resolveSuperseding(workspaceOwner: String, isPresent: (String) -> Boolean) {
        markers.superseding(workspaceOwner).forEach { taskId ->
            val marker = PendingTaskDeletionMarker(taskId, workspaceOwner)
            if (isPresent(taskId)) markers.remove(marker) else markers.abandonSupersede(marker)
        }
    }

    // Markers without an open window were left by an earlier process, by a
    // delete the workspace could not accept yet, or by a session that ended.
    // Only the markers of [workspaceOwner] are returned; other owners keep theirs.
    @Synchronized
    fun leftoverTaskIds(workspaceOwner: String): Set<String> {
        val open = openDeletion?.takeIf { it.workspaceOwner == workspaceOwner }?.taskId
        return markers.read(workspaceOwner) - setOfNotNull(open)
    }

    private fun show(deletion: OwnedTaskDeletion?) {
        openDeletion = deletion
        shown.value = deletion?.let { PendingTaskDeletion(it.taskId, it.title) }
    }
}
