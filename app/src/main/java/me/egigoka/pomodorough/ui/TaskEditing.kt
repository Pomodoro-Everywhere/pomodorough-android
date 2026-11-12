package me.egigoka.pomodorough.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.data.PendingTaskDeletion
import me.egigoka.pomodorough.data.TaskDailySummary

/** Rename and undo-delete state hoisted from the view model to the task board. */
data class TaskEditingActions(
    val editingTaskId: String? = null,
    val onBeginRename: (String) -> Unit = {},
    val onCancelRename: () -> Unit = {},
    val onRename: (String, String) -> Unit = { _, _ -> },
    val pendingDeletion: PendingTaskDeletion? = null,
    val onUndoDeletion: () -> Unit = {},
    val onConfirmDeletion: (String) -> Unit = {},
)

/** The board hides a task while its deletion can still be undone. */
internal fun visibleTaskSummaries(
    summaries: List<TaskDailySummary>,
    pendingDeletion: PendingTaskDeletion?,
): List<TaskDailySummary> =
    if (pendingDeletion == null) summaries else summaries.filter { it.task.id != pendingDeletion.taskId }

// Undo returns the row untouched. The snackbar has no time limit: Delete now
// or a swipe commits the delete, and so does leaving the Tasks screen.
// Keying on the pending deletion cancels the first snackbar when a second
// delete replaces it, so only the matching dismissal can confirm.
@Composable
internal fun TaskDeletionUndoSnackbar(
    pending: PendingTaskDeletion?,
    host: SnackbarHostState,
    onUndo: () -> Unit,
    onConfirm: (String) -> Unit,
    onTasksScreen: Boolean,
) {
    if (pending == null) return
    if (!onTasksScreen) {
        LaunchedEffect(pending) { onConfirm(pending.taskId) }
        return
    }
    val message = stringResource(R.string.deleted_task_undoable, pending.title)
    val undoLabel = stringResource(R.string.undo)
    LaunchedEffect(pending) {
        val result = host.showSnackbar(
            message = message,
            actionLabel = undoLabel,
            withDismissAction = false,
            duration = SnackbarDuration.Indefinite,
        )
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> onConfirm(pending.taskId)
        }
    }
}

// UX round 2, B4: a keyboard delete removes the focused row. Focus moves into
// the snackbar, so the next key reaches Undo and not the top of the screen.
// A touch delete is unchanged: buttons take no focus in touch mode.
@Composable
internal fun UndoSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { snackbar ->
        val focus = remember { FocusRequester() }
        LaunchedEffect(snackbar) { focus.requestFocus() }
        TaskDeletionSnackbar(snackbar, Modifier.focusRequester(focus))
    }
}

// UX round 2: the second button deletes the task at once, so it says
// "Delete now" in words instead of a close icon named "Dismiss". The buttons
// sit on their own line so a long task title keeps its width.
@Composable
private fun TaskDeletionSnackbar(snackbar: SnackbarData, modifier: Modifier) {
    Snackbar(
        modifier = modifier,
        action = {
            TextButton(
                onClick = snackbar::performAction,
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
            ) { Text(snackbar.visuals.actionLabel.orEmpty()) }
        },
        dismissAction = {
            TextButton(
                onClick = snackbar::dismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.dismissActionContentColor),
            ) { Text(stringResource(R.string.delete_now)) }
        },
        actionOnNewLine = true,
    ) { Text(snackbar.visuals.message) }
}

// Done commits; Escape, back, the cancel icon, an empty draft, or an
// unchanged title all cancel without emitting a rename.
@Composable
internal fun TaskTitleEditor(
    title: String,
    onCommit: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by rememberSaveable { mutableStateOf(title) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val commit = {
        keyboard?.hide()
        val renamed = draft.trim()
        if (renamed.isEmpty() || renamed == title) onCancel() else onCommit(renamed)
    }
    BackHandler(onBack = onCancel)
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
                if (escape) onCancel()
                escape
            },
        label = { Text(stringResource(R.string.rename_task_field)) },
        singleLine = true,
        trailingIcon = { CancelRenameButton(onCancel) },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

@Composable
private fun CancelRenameButton(onCancel: () -> Unit) {
    IconButton(onClick = onCancel) {
        Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.cancel_rename))
    }
}
