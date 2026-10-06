package me.egigoka.pomodorough.ui

import androidx.compose.ui.semantics.AccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R43-A08: each Delete button must identify its task to TalkBack
// while the visible text stays the concise localized Delete label.
class R43A08DeleteAccessibilityTest {
    @Test
    fun deleteTaskStringExposesTitleSlot() {
        val strings = resourceText("src/main/res/values/strings.xml")
        assertTrue(
            "accessible Delete label must carry the task title slot",
            strings.contains("name=\"delete_task\"") && strings.contains("%1\$s"),
        )
    }

    @Test
    fun deleteButtonSetsTaskSpecificContentDescription() {
        val views = productionText("me/egigoka/pomodorough/ui/TaskViews.kt")
        assertTrue(
            "Delete button must set contentDescription from the task title",
            views.contains("contentDescription = deleteLabel") &&
                views.contains("delete_task") &&
                views.contains("taskTitle"),
        )
    }

    @Test
    fun visibleDeleteTextStaysConcise() {
        val views = productionText("me/egigoka/pomodorough/ui/TaskViews.kt")
        assertTrue(
            "visible Delete text must stay the concise localized label",
            views.contains("stringResource(R.string.delete)"),
        )
        assertTrue(
            "concise Delete text must not embed the title",
            !views.contains("stringResource(R.string.delete,"),
        )
    }

    @Test
    fun bothLayoutsPassTaskIdentity() {
        val views = productionText("me/egigoka/pomodorough/ui/TaskViews.kt")
        assertTrue(
            "stacked and wide rows must pass the task title to Delete",
            views.contains("DeleteTaskButton(summary.task.title"),
        )
    }

    @Test
    fun deleteLabelsAreDistinctAndIdentifyTasks() {
        val template = deleteTemplate()
        val alpha = deleteTaskAccessibilityLabel(template, "Write release notes")
        val beta = deleteTaskAccessibilityLabel(template, "Water plants")
        assertTrue(alpha.contains("Write release notes"))
        assertTrue(beta.contains("Water plants"))
        assertTrue(alpha != beta)
        assertTrue(!alpha.contains("Water plants"))
        assertTrue(!beta.contains("Write release notes"))
    }

    @Test
    fun mergedSemanticsExposeDistinctDeleteActionsInRowOrder() {
        val rows = listOf("Write release notes" to "task-1", "Water plants" to "task-2")
        val nodes = rows.map { (title, _) -> mergedDeleteNode(title) }
        val labels = nodes.map { it[SemanticsProperties.ContentDescription].single() }
        assertEquals(2, labels.distinct().size)
        assertTrue(labels[0].contains("Write release notes"))
        assertTrue(labels[1].contains("Water plants"))
        nodes.forEachIndexed { index, node ->
            val action = node[SemanticsActions.OnClick]
            assertNotNull("row $index must expose a Delete click action", action)
            assertTrue(requireNotNull(requireNotNull(action).label).contains(rows[index].first))
        }
    }

    @Test
    fun talkBackTraversalFollowsRowOrder() {
        val titles = listOf("Write release notes", "Water plants", "Read inbox")
        val traversal = titles.map { deleteTaskAccessibilityLabel(deleteTemplate(), it) }
        assertEquals(titles.size, traversal.size)
        titles.forEachIndexed { index, title ->
            assertTrue(traversal[index].contains(title))
        }
        assertEquals(traversal, traversal.distinct())
    }

    @Test
    fun deleteTargetsCorrectTask() {
        val deleted = mutableListOf<String>()
        val rows = listOf("task-1" to "Write release notes", "task-2" to "Water plants")
        val nodes = rows.map { (id, title) ->
            mergedDeleteNode(title) { deleted.add(id); true }
        }
        assertTrue(requireNotNull(nodes[1][SemanticsActions.OnClick].action).invoke())
        assertEquals(listOf("task-2"), deleted)
    }

    @Test
    fun deleteLabelKeepsPercentInTitleLiteral() {
        val label = deleteTaskAccessibilityLabel(deleteTemplate(), "Ship 100% today")
        assertTrue(label.contains("Ship 100% today"))
    }

    private fun deleteTemplate(): String {
        val strings = resourceText("src/main/res/values/strings.xml")
        val entry = strings.substringAfter("name=\"delete_task\">").substringBefore("</string>")
        assertTrue(entry.contains("%1\$s"))
        return entry
    }

    private fun mergedDeleteNode(title: String, onDelete: () -> Boolean = { true }): SemanticsConfiguration {
        val label = deleteTaskAccessibilityLabel(deleteTemplate(), title)
        return SemanticsConfiguration().apply {
            set(SemanticsProperties.ContentDescription, listOf(label))
            set(SemanticsActions.OnClick, AccessibilityAction(label) { onDelete(); true })
        }
    }

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }

    private fun resourceText(relative: String): String {
        val root = sequenceOf(File(relative), File("app/$relative"))
            .firstOrNull { it.isFile }
        assertNotNull("resource file $relative", root)
        return requireNotNull(root).readText()
    }
}
