package me.egigoka.pomodorough.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaskDraftPolicyTest {
    @Test fun whitespaceOnlyIsRejected() = assertEquals(
        TaskDraftError.Blank, TaskDraftPolicy.validate(" \t\n", emptyList()),
    )

    @Test fun embeddedControlCharacterIsRejected() = assertEquals(
        TaskDraftError.ControlCharacter, TaskDraftPolicy.validate("Ship\u0007it", emptyList()),
    )

    @Test fun utf8BoundaryAccepts512BytesAndRejects513() {
        assertNull(TaskDraftPolicy.validate("é".repeat(256), emptyList()))
        assertEquals(
            TaskDraftError.TooLong,
            TaskDraftPolicy.validate("é".repeat(256) + "a", emptyList()),
        )
    }

    @Test fun normalizedDuplicateIsRejected() = assertEquals(
        TaskDraftError.Duplicate, TaskDraftPolicy.validate("  Release notes  ", listOf("Release notes")),
    )
}
