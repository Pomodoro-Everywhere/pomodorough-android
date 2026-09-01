package me.egigoka.pomodorough

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingTimerActionLifecycleA6InstrumentedTest {
    @Test
    fun savedStateRegistryRecreationTransfersPendingPermissionIdentity() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        lateinit var oldCoordinator: PendingTimerActionCoordinator
        lateinit var saved: PendingTimerActionSavedState
        lateinit var callback: PendingTimerActionCallbackIdentity
        try {
            scenario.onActivity { activity ->
                oldCoordinator = activity.pendingTimerActionCoordinator()
                assertEquals(PendingTimerActionEffect.None, oldCoordinator.begin(true))
                val actionId = requireNotNull(oldCoordinator.state.actionId)
                val effect = oldCoordinator.confirmNotificationIntro(actionId)
                assertTrue(effect is PendingTimerActionEffect.RequestNotificationPermission)
                callback = (effect as PendingTimerActionEffect.RequestNotificationPermission).callback
                saved = oldCoordinator.savedState()
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                val recreated = activity.pendingTimerActionCoordinator()
                assertEquals(saved, recreated.savedState())
                assertEquals(callback, recreated.pendingCallback())
                assertEquals(
                    PendingTimerActionEffect.None,
                    oldCoordinator.notificationPermissionResult(callback, granted = true),
                )
                assertTrue(
                    recreated.notificationPermissionResult(callback, granted = true) is
                        PendingTimerActionEffect.CheckExactAlarm,
                )
            }
        } finally {
            scenario.close()
        }
    }

    private fun MainActivity.pendingTimerActionCoordinator(): PendingTimerActionCoordinator {
        val field = MainActivity::class.java.getDeclaredField("pendingTimerAction")
        field.isAccessible = true
        return field.get(this) as PendingTimerActionCoordinator
    }
}
