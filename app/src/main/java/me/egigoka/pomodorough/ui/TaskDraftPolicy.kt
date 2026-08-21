package me.egigoka.pomodorough.ui

import androidx.annotation.StringRes
import java.nio.charset.StandardCharsets
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.domain.TaskReducer

enum class TaskDraftError(@StringRes val messageRes: Int) {
    Blank(R.string.enter_printable_task_text),
    ControlCharacter(R.string.remove_control_characters),
    TooLong(R.string.task_must_be_512_utf_8_bytes_or_fewer),
    Duplicate(R.string.that_task_already_exists),
}

object TaskDraftPolicy {
    fun validate(value: String, existingTitles: List<String>): TaskDraftError? {
        val normalized = TaskReducer.normalizeTitle(value)
        if (normalized.isBlank()) return TaskDraftError.Blank
        if (normalized.codePoints().anyMatch { Character.isISOControl(it) }) {
            return TaskDraftError.ControlCharacter
        }
        if (normalized.toByteArray(StandardCharsets.UTF_8).size > 512) {
            return TaskDraftError.TooLong
        }
        val candidate = TaskReducer.taskFromTitle(normalized.trim()) ?: return TaskDraftError.Blank
        if (existingTitles.any { TaskReducer.taskFromTitle(TaskReducer.normalizeTitle(it).trim())?.id == candidate.id }) {
            return TaskDraftError.Duplicate
        }
        return null
    }
}
