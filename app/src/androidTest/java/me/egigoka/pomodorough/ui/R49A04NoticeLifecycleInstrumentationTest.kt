package me.egigoka.pomodorough.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.egigoka.pomodorough.data.TimerStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class R49A04NoticeLifecycleInstrumentationTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun enlargedRtlSinglePaneActuallyScrollsToControlsAndBackToDismissMessages() {
        val fixture = fixture(fontScale = 1.5f, heightFraction = 0.65f)
        fixture.show()
        fixture.setRegion(R49A04NoticeRegion.Trailing)
        fixture.publishMessages()
        fixture.assertMessages()
        fixture.assertConflictReachable()
        val before = fixture.scrollPosition()
        fixture.activateTimer(TimerStatus.Paused)
        // Scroll is a no-op when the short en-US strings already fit: the
        // controls are revealed without moving. Expanded locales overflow and
        // must advance the real pane scroll position.
        assertTrue("Reaching the controls must not move the pane backwards", fixture.scrollPosition() >= before)
        fixture.assertPaneGeometry()
        fixture.dismissNotice()
        fixture.assertConflictReachable()
        fixture.dismissConflict()
        rule.runOnIdle { assertEquals(1, fixture.noticeDismissals); assertEquals(1, fixture.conflictDismissals) }
    }

    @Test
    fun replacementNoticeCancelsOldTimeoutAndUsesAccessibilityRecommendation() {
        val timeout = R49A04NoticeTimeout()
        val fixture = fixture()
        fixture.show(timeout)
        fixture.setRegion(R49A04NoticeRegion.Split)
        rule.mainClock.autoAdvance = false
        fixture.publishMessages()
        advance(12_000)
        fixture.assertMessages()
        fixture.publishMessages("Replacement notice")
        advance(9_000)
        fixture.assertMessages("Replacement notice")
        rule.runOnIdle { assertEquals(0, fixture.noticeDismissals) }
        advance(11_100)
        fixture.noticeNode("Replacement notice").assertDoesNotExist()
        fixture.assertConflictReachable()
        rule.runOnIdle {
            assertEquals(1, fixture.noticeDismissals)
            assertEquals(0, fixture.conflictDismissals)
            assertTrue(timeout.requests.isNotEmpty())
            timeout.requests.forEach { assertEquals(R49A04NoticeTimeout.Request(7_000, false, true, true), it) }
        }
    }

    @Test
    fun semanticsDismissalCancelsPendingTimeoutAndDoesNotDismissConflict() {
        val fixture = fixture()
        fixture.show(R49A04NoticeTimeout())
        fixture.setRegion(R49A04NoticeRegion.Leading)
        fixture.publishMessages()
        fixture.assertMessages()
        rule.mainClock.autoAdvance = false
        fixture.dismissNotice()
        advance(21_000)
        fixture.assertConflictReachable()
        rule.runOnIdle { assertEquals(1, fixture.noticeDismissals); assertEquals(0, fixture.conflictDismissals) }
    }

    private fun fixture(fontScale: Float = 1f, heightFraction: Float = 1f) = R49A04NoticePaneFixture(
        rule, R49A04NoticeEnvironment(FoldHingeOrientation.Horizontal, 1.5f, LayoutDirection.Rtl, fontScale, heightFraction),
    )

    private fun advance(milliseconds: Long) {
        rule.mainClock.advanceTimeBy(milliseconds)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
    }
}
