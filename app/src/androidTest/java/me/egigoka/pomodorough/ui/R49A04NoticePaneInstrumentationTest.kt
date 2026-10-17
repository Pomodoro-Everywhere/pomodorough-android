package me.egigoka.pomodorough.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class R49A04NoticePaneInstrumentationTest(
    orientation: FoldHingeOrientation,
    density: Float,
    direction: LayoutDirection,
) {
    @get:Rule val rule = createComposeRule()
    private val fixture = R49A04NoticePaneFixture(rule, R49A04NoticeEnvironment(orientation, density, direction))

    @Test
    fun splitArrivalExposesPoliteMessagesIndependentDismissalsAndEveryTimerAction() {
        fixture.show()
        fixture.setRegion(R49A04NoticeRegion.Split)
        fixture.noticeNode().assertDoesNotExist()
        fixture.publishMessages()
        fixture.assertPaneGeometry()
        fixture.assertMessages()
        timerStatuses().forEach { fixture.activateTimer(it) }
        fixture.dismissNotice()
        fixture.assertConflictReachable()
        rule.runOnIdle { assertEquals(1, fixture.noticeDismissals); assertEquals(0, fixture.conflictDismissals) }
        fixture.dismissConflict()
        rule.runOnIdle { assertEquals(1, fixture.noticeDismissals); assertEquals(1, fixture.conflictDismissals) }
        fixture.publishMessages("Replacement notice")
        fixture.assertMessages("Replacement notice")
        fixture.dismissConflict()
        fixture.assertNoticeReachable("Replacement notice")
        fixture.dismissNotice("Replacement notice")
        rule.runOnIdle { assertEquals(2, fixture.noticeDismissals); assertEquals(2, fixture.conflictDismissals) }
        fixture.activateTimer(null)
    }

    @Test
    fun singleLeadingTrailingAndOutsideRegionsKeepMessagesAndActionsClearOfOriginalHinge() {
        fixture.show()
        listOf(R49A04NoticeRegion.Leading, R49A04NoticeRegion.Trailing, R49A04NoticeRegion.Outside).forEach { region ->
            fixture.setRegion(region)
            fixture.publishMessages()
            fixture.assertPaneGeometry()
            fixture.assertMessages()
            timerStatuses().forEach { fixture.activateTimer(it) }
            fixture.dismissConflict()
            fixture.assertNoticeReachable()
            fixture.dismissNotice()
        }
        rule.runOnIdle { assertEquals(3, fixture.noticeDismissals); assertEquals(3, fixture.conflictDismissals) }
    }

    private fun timerStatuses() = listOf(null, TimerStatus.Running, TimerStatus.Paused, TimerStatus.Completed)

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}, density={1}, direction={2}")
        fun cases(): List<Array<Any>> = listOf(FoldHingeOrientation.Vertical, FoldHingeOrientation.Horizontal)
            .flatMap { orientation -> listOf(1f, 1.5f, 2.625f).flatMap { density ->
                LayoutDirection.entries.map { direction -> arrayOf(orientation, density, direction) }
            } }
    }
}
