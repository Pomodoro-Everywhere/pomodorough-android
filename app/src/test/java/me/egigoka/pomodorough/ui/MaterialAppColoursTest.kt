package me.egigoka.pomodorough.ui

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import me.egigoka.pomodorough.data.FocusTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AND-12: dialogs, menus and the Crash reports switch take the app palette instead of the
 * Material baseline, and the task menu marks its current choice. AND-19: the CREATE IROH ROOM
 * button stands out from its card in both themes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en")
class MaterialAppColoursTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun dialogAndMenuContainersUseTheAppPalette() {
        val (light, dark) = captureBothThemes { MaterialTheme.colorScheme }
        assertEquals(Color(0xFFDCE7E9), light.surfaceContainerHigh)
        assertEquals(Cloud, light.surfaceContainer)
        assertNotEquals(lightColorScheme().surfaceContainerHigh, light.surfaceContainerHigh)
        assertTextReadable("light dialog text", light.onSurface, light.surfaceContainerHigh)
        assertTextReadable("light dialog button", light.primary, light.surfaceContainerHigh)

        assertEquals(Color(0xFF1C2A37), dark.surfaceContainerHigh)
        assertNotEquals(darkColorScheme().surfaceContainerHigh, dark.surfaceContainerHigh)
        assertTextReadable("dark dialog text", dark.onSurface, dark.surfaceContainerHigh)
        assertTextReadable("dark dialog button", dark.primary, dark.surfaceContainerHigh)
    }

    @Test
    fun crashReportsSwitchUsesTheAppPrimaryInBothStates() {
        captureBothThemes { MaterialTheme.colorScheme to crashSwitchColors() }.toList().forEach { (scheme, colours) ->
            assertEquals(scheme.primary, colours.checkedTrackColor)
            assertEquals(scheme.onPrimary, colours.checkedThumbColor)
            assertEquals(scheme.primary, colours.uncheckedThumbColor)
            assertEquals(scheme.primary, colours.uncheckedBorderColor)
            assertTrue(contrastRatio(colours.uncheckedThumbColor, colours.uncheckedTrackColor) >= 3.0)
        }
    }

    @Test
    fun createRoomButtonStandsOutFromItsCardInBothThemes() {
        val themes = captureBothThemes { MaterialTheme.colorScheme to createRoomButtonColors() }
        listOf("light" to themes.first, "dark" to themes.second).forEach { (theme, captured) ->
            val (scheme, colours) = captured
            val edge = contrastRatio(colours.containerColor, scheme.tertiaryContainer)
            assertTrue("$theme button against its card is $edge:1", edge >= 3.0)
            assertTextReadable("$theme button label", colours.contentColor, colours.containerColor)
        }
    }

    @Test
    fun taskMenuMarksTheCurrentTask() {
        val tasks = listOf(FocusTask("task-1", "Write tests"), FocusTask("task-2", "Review notes"))
        compose.setContent {
            PomodoroughTheme(darkTheme = false) {
                TaskSelectionMenu(TaskSelectorState(tasks, "task-2", true), expanded = true, onSelectTask = {}, onDismiss = {})
            }
        }

        compose.onNodeWithText("Review notes").assertIsSelected()
        compose.onNodeWithText("Write tests").assertIsNotSelected()
        compose.onNodeWithText("No task").assertIsNotSelected()
    }

    /** Reads [read] under the light theme and the dark theme in one composition. */
    private fun <T> captureBothThemes(read: @Composable () -> T): Pair<T, T> {
        val values = arrayOfNulls<Any?>(2)
        compose.setContent {
            PomodoroughTheme(darkTheme = false) { values[0] = read() }
            PomodoroughTheme(darkTheme = true) { values[1] = read() }
        }
        compose.waitForIdle()
        @Suppress("UNCHECKED_CAST")
        return (values[0] as T) to (values[1] as T)
    }

    private fun assertTextReadable(name: String, text: Color, background: Color) {
        val ratio = contrastRatio(text, background)
        assertTrue("$name is $ratio:1", ratio >= 4.5)
    }
}
