package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The keyboard's Done key adds the task exactly like the Add task button and
 * stays inert while the draft would be rejected. An accepted task releases
 * the composer focus so the keyboard closes; a rejected one keeps it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TaskComposerDoneKeyTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun doneKeyAddsValidDraft() {
        val added = renderComposer()

        compose.onNode(hasSetTextAction()).performTextInput("Ship UX pass")
        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(listOf("Ship UX pass"), added)
    }

    @Test
    fun doneKeyIgnoresEmptyDraft() {
        val added = renderComposer()

        compose.onNode(hasSetTextAction()).performImeAction()

        assertEquals(emptyList<String>(), added)
    }

    @Test
    fun acceptedDraftReleasesFocus() {
        renderComposer()

        compose.onNode(hasSetTextAction()).performTextInput("Ship UX pass")
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    @Test
    fun rejectedDraftKeepsFocus() {
        renderComposer(accept = false)

        compose.onNode(hasSetTextAction()).performTextInput("Ship UX pass")
        compose.onNode(hasSetTextAction()).performImeAction()

        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun renderComposer(accept: Boolean = true): List<String> {
        val added = mutableListOf<String>()
        val draft = TaskDraftUiState()
        compose.setContent {
            MaterialTheme {
                TaskBoardHeader(
                    summaries = emptyList(),
                    mutationsEnabled = true,
                    onAddTask = { title, done ->
                        added += title
                        done(accept)
                    },
                    draftState = draft,
                )
            }
        }
        return added
    }
}
