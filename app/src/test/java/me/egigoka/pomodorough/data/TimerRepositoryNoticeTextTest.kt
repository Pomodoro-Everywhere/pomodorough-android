package me.egigoka.pomodorough.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX round 2, B3: a notice shows a localized sentence chosen by failure type.
 * This reads the repository source and fails when an exception message or an
 * English literal is assigned to a notice again. L5 extends it to the error
 * text of the history-resolution card and of the account-switch card, and
 * to the conflict card, which reads the screen sources.
 */
class TimerRepositoryNoticeTextTest {
    @Test
    fun noNoticeTextComesFromAnExceptionMessageOrALiteral() {
        val raw = noticeTextAssignments(repositorySource()).filter { it.value.carriesRawText() }
        assertEquals("notice text that is not a localized sentence", emptyList<String>(), raw.map { it.toString() })
    }

    @Test
    fun theScanSeesTheNoticeAssignmentsOfTheRepository() {
        val assignments = noticeTextAssignments(repositorySource())
        assertTrue("found ${assignments.size} assignments", assignments.size >= 40)
    }

    @Test
    fun theScanFlagsEveryRawForm() {
        val source = """
            notice = error.message
            notice = "Sync stopped"
            mutationFailure = failure.localizedMessage ?: fallback
            nextNotice = state.message,
            error = error.message ?: fallback,
            val message = error.toString()
            notice = logoutError?.let {
                it.message
            }
            notice = appContext.getString(R.string.sync_stopped_after_a_local_failure)
            notice = failureNoticeText(error, R.string.sign_out_failed_local_timer_data_was_kept)
            if (notice == mutationFailure) notice = null
            error = failureNoticeText(error, R.string.could_not_refresh_remote_history),
        """.trimIndent().lines()
        val flagged = noticeTextAssignments(source).filter { it.value.carriesRawText() }.map { it.line }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), flagged)
    }

    // L5: AppState.conflict is a diagnostic. A screen may test it for null to
    // show the conflict card, and must never render its value.
    @Test
    fun noScreenRendersTheConflictReason() {
        val reads = uiSources().flatMap { file ->
            conflictValueReads(file.readLines()).map { "${file.name}:$it" }
        }
        assertEquals("screens that read the conflict value", emptyList<String>(), reads)
    }

    @Test
    fun theConflictScanSeesTheCardAndFlagsAValueRead() {
        val nullChecks = uiSources().sumOf { file -> file.readLines().count { ConflictNullCheck.containsMatchIn(it) } }
        assertTrue("found $nullChecks null checks", nullChecks >= 2)
        val source = """
            if (state.conflict != null) ConflictCard(onDismiss)
            state.conflict?.let { MessageCard(title, it, Lavender, onDismiss) }
            Text(state.conflict.orEmpty())
            if (state.conflict == null) return
            // the card never shows state.conflict
            else stringResource(R.string.conflict)
            MessageCard(title, appState.conflict!!, Lavender, onDismiss)
        """.trimIndent().lines()
        assertEquals(listOf(2, 3, 7), conflictValueReads(source))
    }

    // Comment lines are skipped; R.string.conflict is the sync status label, not the value.
    private fun conflictValueReads(lines: List<String>): List<Int> =
        lines.mapIndexedNotNull { index, line ->
            val code = line.trim()
            if (code.startsWith("//") || code.startsWith("*")) return@mapIndexedNotNull null
            (index + 1).takeIf { ConflictRead.findAll(line).count() > ConflictNullCheck.findAll(line).count() }
        }

    private fun uiSources(): List<File> =
        File(sourceRoot(), "me/egigoka/pomodorough/ui").listFiles { file -> file.extension == "kt" }.orEmpty().toList()

    private data class Assignment(val line: Int, val value: String) {
        override fun toString() = "line $line: $value"
    }

    // The value runs to the end of the line, or to the line that closes the
    // brackets the first line opened.
    private fun noticeTextAssignments(lines: List<String>): List<Assignment> =
        lines.mapIndexedNotNull { index, line ->
            val start = NoticeTextTarget.find(line) ?: return@mapIndexedNotNull null
            var value = line.substring(start.range.last + 1)
            var next = index + 1
            while (value.openBrackets() > 0 && next < lines.size) value += " " + lines[next++].trim()
            Assignment(index + 1, value.trim())
        }

    private fun String.openBrackets(): Int = count { it in "({" } - count { it in ")}" }

    private fun String.carriesRawText(): Boolean = RawText.containsMatchIn(this)

    private fun repositorySource(): List<String> =
        File(sourceRoot(), "me/egigoka/pomodorough/data/TimerRepository.kt").readLines()

    private fun sourceRoot(): File =
        sequenceOf(File("src/main/java"), File("app/src/main/java")).first(File::isDirectory)

    private companion object {
        // Fields and arguments that become AppState.notice, the local value the
        // mutation failure paths assign to both of them, and the error text of
        // HistoryResolutionState and AccountSwitchState.
        val NoticeTextTarget = Regex("""\b(notice|mutationFailure|nextNotice|val message|error)\s*=(?!=)""")
        val RawText = Regex("""["]|\.message\b|\.localizedMessage\b|\.toString\(\)""")
        val ConflictRead = Regex("""(?<!R\.string)\.conflict\b""")
        val ConflictNullCheck = Regex("""\.conflict\s*[!=]=\s*null""")
    }
}
