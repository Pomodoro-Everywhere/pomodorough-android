package me.egigoka.pomodorough.ui

import java.io.File
import me.egigoka.pomodorough.data.CanonicalTimer
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// R50-A05: landscape actions clipped enlarged labels in a 320dp pane
// at font scale 2.0 (equal weights + fixed 54dp + maxLines 1).
// FlowRow wraps intrinsically, heightIn grows, labels wrap fully.
class R50A05LandscapeActionsTest {
    @Test
    fun pausedTimerKeepsActiveActions() {
        val controls = timerControlState(heroState(status = TimerStatus.Paused))
        assertTrue(controls.active)
        assertFalse(controls.hasActiveCompletionAlert)
    }

    @Test
    fun runningTimerKeepsActiveActions() {
        val controls = timerControlState(heroState(status = TimerStatus.Running))
        assertTrue(controls.active)
    }

    @Test
    fun completedIdleHasNoActiveActions() {
        val controls = timerControlState(heroState(status = TimerStatus.Completed))
        assertFalse(controls.active)
        assertFalse(controls.hasActiveCompletionAlert)
    }

    @Test
    fun completionAlertExposesStopSound() {
        val timer = timerWithStatus(TimerStatus.Completed)
        val state = heroState(status = TimerStatus.Completed, alertId = timer.id, timer = timer)
        val controls = timerControlState(state)
        assertFalse(controls.active)
        assertTrue(controls.hasActiveCompletionAlert)
    }

    @Test
    fun translatedActionLabelsPresentAndDistinct() {
        val strings = stringsText()
        val labels = listOf("resume", "finish", "cancel", "stop_sound").map { name ->
            Regex("<string name=\"$name\">([^<]+)</string>").find(strings)?.groupValues?.get(1)
        }
        assertTrue(labels.all { !it.isNullOrBlank() })
        assertEquals(4, labels.toSet().size)
    }

    @Test
    fun narrowAndWidePanesShareIntrinsicFlowRow() {
        val range = actionsRange()
        assertTrue(range.contains("FlowRow("))
        assertTrue(range.contains("Modifier.fillMaxWidth()"))
        assertTrue(range.contains("horizontalArrangement = Arrangement.spacedBy(10.dp)"))
        assertTrue(range.contains("verticalArrangement = Arrangement.spacedBy(10.dp)"))
        val hero = productionText("me/egigoka/pomodorough/ui/TimerHero.kt")
        val landscape = hero.substringAfter("internal fun LandscapeTimerHero")
            .substringBefore("internal fun landscapeReadoutFontSize")
        assertTrue(landscape.contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun actionsGrowAtDefaultAndLargeFontScales() {
        val range = actionsRange()
        assertTrue(range.contains("heightIn(min = 48.dp)"))
        assertFalse(range.contains("height(54.dp)"))
        assertFalse(range.contains("weight(1f)"))
        assertFalse(range.contains("maxLines"))
        assertFalse(range.contains("TextOverflow.Ellipsis"))
    }

    @Test
    fun actionPlacementStaysRelativeInRtl() {
        val hero = productionText("me/egigoka/pomodorough/ui/TimerHero.kt")
        assertFalse(hero.contains("absoluteOffset"))
        assertFalse(hero.contains("absolutePadding"))
        assertTrue(actionsRange().contains("Arrangement.spacedBy"))
    }

    @Test
    fun eachCallbackWiredOncePerAction() {
        val range = actionsRange()
        assertEquals(1, countOccurrences(range, "actions.onToggleTimer"))
        assertEquals(1, countOccurrences(range, "actions.onFinishTimer"))
        assertEquals(1, countOccurrences(range, "actions.onCancelTimer"))
        assertEquals(1, countOccurrences(range, "actions.onStopSound"))
        assertTrue(range.contains("R.string.finish"))
        assertTrue(range.contains("R.string.cancel"))
        assertTrue(range.contains("R.string.stop_sound"))
    }

    private fun heroState(
        status: String,
        alertId: String? = null,
        timer: CanonicalTimer? = null,
    ) = TimerHeroState(
        timer = timer ?: timerWithStatus(status),
        activeCompletionAlertTimerId = alertId,
        settings = TimerSettings(),
        longBreakProgress = 0,
        ready = true,
        tasks = emptyList(),
        selectedTaskId = null,
        mutationsEnabled = true,
    )

    private fun timerWithStatus(status: String) = CanonicalTimer(
        id = "timer-r50-a05",
        phase = TimerPhase.Focus,
        status = status,
        plannedDurationMs = 25 * 60_000L,
        anchorAt = "2026-08-20T00:25:00Z",
        elapsedAtAnchorMs = 0L,
    )

    private fun actionsRange(): String {
        val hero = productionText("me/egigoka/pomodorough/ui/TimerHero.kt")
        return hero.substringAfter("internal fun LandscapeTimerActions")
            .substringBefore("internal fun LongBreakProgress")
    }

    private fun countOccurrences(text: String, needle: String): Int =
        text.split(needle).size - 1

    private fun productionText(relative: String): String {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return File(requireNotNull(root), relative).readText()
    }

    private fun stringsText(): String {
        val root = sequenceOf(File("src/main/res"), File("app/src/main/res"))
            .firstOrNull(File::isDirectory)
        assertNotNull("res root", root)
        return File(requireNotNull(root), "values/strings.xml").readText()
    }
}
