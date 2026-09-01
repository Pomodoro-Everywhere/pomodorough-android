package me.egigoka.pomodorough

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingTimerActionCoordinatorA6Test {
    @Test
    fun completedPermissionActionCannotFeedLaterPermissionStage() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstPermission = startPermission(action)
        val firstCheck = expectCheck(action.notificationPermissionResult(firstPermission, granted = true))
        expectToggle(action.exactAlarmResult(firstCheck, needsFallback = false))

        val secondPermission = startPermission(action)
        assertNotEquals(firstPermission, secondPermission)
        assertNone(action.notificationPermissionResult(firstPermission, granted = true))
        assertEquals(secondPermission, action.pendingCallback())

        val secondCheck = expectCheck(action.notificationPermissionResult(secondPermission, granted = true))
        expectToggle(action.exactAlarmResult(secondCheck, needsFallback = false))
    }

    @Test
    fun completedNotificationSettingsActionCannotFeedLaterSettingsStage() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstSettings = startNotificationSettings(action)
        val firstCheck = expectCheck(action.notificationSettingsResult(firstSettings, granted = true))
        expectToggle(action.exactAlarmResult(firstCheck, needsFallback = false))

        val secondSettings = startNotificationSettings(action)
        assertNone(action.notificationSettingsResult(firstSettings, granted = true))
        assertEquals(secondSettings, action.pendingCallback())

        val secondCheck = expectCheck(action.notificationSettingsResult(secondSettings, granted = true))
        expectToggle(action.exactAlarmResult(secondCheck, needsFallback = false))
    }

    @Test
    fun completedExactSettingsActionCannotFeedLaterExactSettingsStage() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstSettings = startExactAlarmSettings(action)
        val firstCheck = expectCheck(action.exactAlarmSettingsResult(firstSettings))
        expectToggle(action.exactAlarmResult(firstCheck, needsFallback = false))

        val secondSettings = startExactAlarmSettings(action)
        assertNone(action.exactAlarmSettingsResult(firstSettings))
        assertEquals(secondSettings, action.pendingCallback())

        val secondCheck = expectCheck(action.exactAlarmSettingsResult(secondSettings))
        expectToggle(action.exactAlarmResult(secondCheck, needsFallback = false))
    }

    @Test
    fun completedExactCheckCannotFeedLaterExactCheckStage() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstCheck = startExactAlarmCheck(action)
        expectToggle(action.exactAlarmResult(firstCheck, needsFallback = false))

        val secondCheck = startExactAlarmCheck(action)
        assertNone(action.exactAlarmResult(firstCheck, needsFallback = false))
        assertEquals(secondCheck, action.pendingCallback())
        expectToggle(action.exactAlarmResult(secondCheck, needsFallback = false))
    }

    @Test
    fun completedFallbackActionCannotFeedLaterFallbackStage() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstActionId = startExactAlarmFallback(action)
        expectToggle(action.useInexactAlarm(firstActionId))

        val secondActionId = startExactAlarmFallback(action)
        assertNone(action.useInexactAlarm(firstActionId))
        assertEquals(secondActionId, action.state.actionId)
        expectToggle(action.useInexactAlarm(secondActionId))
    }

    @Test
    fun duplicateNotificationSettingsDeliveryCannotConsumeRelaunch() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstSettings = startNotificationSettings(action)
        assertNone(action.notificationSettingsResult(firstSettings, granted = false))
        val actionId = requireActionId(action)
        val secondSettings = expectNotificationSettings(action.openNotificationSettings(actionId))

        assertNone(action.notificationSettingsResult(firstSettings, granted = true))
        assertEquals(secondSettings, action.pendingCallback())
        val exactCheck = expectCheck(action.notificationSettingsResult(secondSettings, granted = true))
        expectToggle(action.exactAlarmResult(exactCheck, needsFallback = false))
    }

    @Test
    fun duplicateExactCallbacksCannotConsumeSameActionRelaunches() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        val firstCheck = startExactAlarmCheck(action)
        assertNone(action.exactAlarmResult(firstCheck, needsFallback = true))
        val firstSettings = expectExactSettings(action.openExactAlarmSettings(requireActionId(action)))
        val secondCheck = expectCheck(action.exactAlarmSettingsResult(firstSettings))
        assertNone(action.exactAlarmResult(firstCheck, needsFallback = false))
        assertEquals(secondCheck, action.pendingCallback())
        assertNone(action.exactAlarmResult(secondCheck, needsFallback = true))
        val secondSettings = expectExactSettings(action.openExactAlarmSettings(requireActionId(action)))

        assertNone(action.exactAlarmSettingsResult(firstSettings))
        assertEquals(secondSettings, action.pendingCallback())
    }

    @Test
    fun cancelledDialogIdentityCannotCancelOrAdvanceReplacement() {
        val identities = SequenceIdentityGenerator()
        val action = coordinator(identities)
        assertNone(action.begin(needsNotificationPermission = true))
        val firstActionId = requireActionId(action)
        action.dismissDialog(firstActionId)

        assertNone(action.begin(needsNotificationPermission = true))
        val secondActionId = requireActionId(action)
        action.dismissDialog(firstActionId)
        assertNone(action.confirmNotificationIntro(firstActionId))
        assertNone(action.declineNotificationIntro(firstActionId))
        assertEquals(PendingTimerActionStep.NotificationIntro, action.step)
        expectPermission(action.confirmNotificationIntro(secondActionId))
    }

    @Test
    fun recreationTransfersPermissionLaunchIdentityToReplacementOnly() {
        val identities = SequenceIdentityGenerator()
        val oldActivity = coordinator(identities)
        val permission = startPermission(oldActivity)
        val recreated = recreate(oldActivity, identities)

        assertEquals(permission, recreated.pendingCallback())
        assertNone(oldActivity.notificationPermissionResult(permission, granted = true))
        val check = expectCheck(recreated.notificationPermissionResult(permission, granted = true))
        expectToggle(recreated.exactAlarmResult(check, needsFallback = false))
    }

    @Test
    fun recreationPreservesSettingsFallbackAndExactCheckIdentities() {
        val identities = SequenceIdentityGenerator()
        assertRestoredLaunch(
            identities,
            ::startNotificationSettings,
            PendingTimerActionStep.NotificationRecovery,
        ) { action, callback ->
            action.notificationSettingsResult(callback, granted = false)
        }
        assertRestoredLaunch(
            identities,
            ::startExactAlarmSettings,
            PendingTimerActionStep.ExactAlarmCheck,
        ) { action, callback ->
            action.exactAlarmSettingsResult(callback)
        }
        assertRestoredLaunch(
            identities,
            ::startExactAlarmCheck,
            PendingTimerActionStep.ExactAlarmFallback,
        ) { action, callback ->
            action.exactAlarmResult(callback, needsFallback = true)
        }

        val fallback = coordinator(identities)
        val actionId = startExactAlarmFallback(fallback)
        val recreatedFallback = recreate(fallback, identities)
        assertEquals(actionId, recreatedFallback.state.actionId)
        expectToggle(recreatedFallback.useInexactAlarm(actionId))
    }

    @Test
    fun exactCheckRestorationReusesIdentityWithoutMintingAnotherLaunch() {
        val identities = SequenceIdentityGenerator()
        val original = coordinator(identities)
        val callback = startExactAlarmCheck(original)
        val recreated = recreate(original, identities)

        assertEquals(PendingTimerActionEffect.CheckExactAlarm(callback), recreated.resumeAfterRestore())
        assertEquals(callback, recreated.pendingCallback())
        expectToggle(recreated.exactAlarmResult(callback, needsFallback = false))
        assertNone(recreated.exactAlarmResult(callback, needsFallback = false))
    }

    @Test
    fun malformedOrStageOnlySavedStateFailsClosed() {
        val malformed = listOf(
            PendingTimerActionSavedState("not-a-step", "action", "launch"),
            PendingTimerActionSavedState(PendingTimerActionStep.NotificationIntro.name, null, null),
            PendingTimerActionSavedState(PendingTimerActionStep.NotificationPermission.name, "action", null),
            PendingTimerActionSavedState(PendingTimerActionStep.ExactAlarmFallback.name, "action", "launch"),
        )

        malformed.forEach { saved ->
            val restored = PendingTimerActionCoordinator.restore(saved, SequenceIdentityGenerator())
            assertEquals(PendingTimerActionState(PendingTimerActionStep.Idle), restored.state)
        }
    }

    private fun assertRestoredLaunch(
        identities: SequenceIdentityGenerator,
        start: (PendingTimerActionCoordinator) -> PendingTimerActionCallbackIdentity,
        completedStep: PendingTimerActionStep,
        complete: (PendingTimerActionCoordinator, PendingTimerActionCallbackIdentity) -> PendingTimerActionEffect,
    ) {
        val original = coordinator(identities)
        val callback = start(original)
        val awaitingStep = original.step
        val recreated = recreate(original, identities)
        assertEquals(callback, recreated.pendingCallback())
        assertNone(complete(original, callback))
        assertEquals(awaitingStep, original.step)
        complete(recreated, callback)
        assertEquals(completedStep, recreated.step)
    }

    private fun startPermission(
        action: PendingTimerActionCoordinator,
    ): PendingTimerActionCallbackIdentity {
        assertNone(action.begin(needsNotificationPermission = true))
        return expectPermission(action.confirmNotificationIntro(requireActionId(action)))
    }

    private fun startNotificationSettings(
        action: PendingTimerActionCoordinator,
    ): PendingTimerActionCallbackIdentity {
        assertNone(action.begin(needsNotificationPermission = true))
        val actionId = requireActionId(action)
        assertNone(action.declineNotificationIntro(actionId))
        return expectNotificationSettings(action.openNotificationSettings(actionId))
    }

    private fun startExactAlarmCheck(
        action: PendingTimerActionCoordinator,
    ): PendingTimerActionCallbackIdentity = expectCheck(
        action.begin(needsNotificationPermission = false),
    )

    private fun startExactAlarmFallback(action: PendingTimerActionCoordinator): String {
        val callback = startExactAlarmCheck(action)
        assertNone(action.exactAlarmResult(callback, needsFallback = true))
        assertEquals(PendingTimerActionStep.ExactAlarmFallback, action.step)
        return requireActionId(action)
    }

    private fun startExactAlarmSettings(
        action: PendingTimerActionCoordinator,
    ): PendingTimerActionCallbackIdentity {
        val actionId = startExactAlarmFallback(action)
        return expectExactSettings(action.openExactAlarmSettings(actionId))
    }

    private fun recreate(
        original: PendingTimerActionCoordinator,
        identities: SequenceIdentityGenerator,
    ): PendingTimerActionCoordinator {
        val saved = original.savedState()
        original.detach()
        return PendingTimerActionCoordinator.restore(saved, identities)
    }

    private fun coordinator(identities: SequenceIdentityGenerator) =
        PendingTimerActionCoordinator.restore(null, identities)

    private fun requireActionId(action: PendingTimerActionCoordinator): String =
        requireNotNull(action.state.actionId)

    private fun expectPermission(effect: PendingTimerActionEffect): PendingTimerActionCallbackIdentity {
        assertTrue(effect is PendingTimerActionEffect.RequestNotificationPermission)
        return (effect as PendingTimerActionEffect.RequestNotificationPermission).callback
    }

    private fun expectNotificationSettings(
        effect: PendingTimerActionEffect,
    ): PendingTimerActionCallbackIdentity {
        assertTrue(effect is PendingTimerActionEffect.OpenNotificationSettings)
        return (effect as PendingTimerActionEffect.OpenNotificationSettings).callback
    }

    private fun expectExactSettings(effect: PendingTimerActionEffect): PendingTimerActionCallbackIdentity {
        assertTrue(effect is PendingTimerActionEffect.OpenExactAlarmSettings)
        return (effect as PendingTimerActionEffect.OpenExactAlarmSettings).callback
    }

    private fun expectCheck(effect: PendingTimerActionEffect): PendingTimerActionCallbackIdentity {
        assertTrue(effect is PendingTimerActionEffect.CheckExactAlarm)
        return (effect as PendingTimerActionEffect.CheckExactAlarm).callback
    }

    private fun expectToggle(effect: PendingTimerActionEffect) {
        assertTrue(effect is PendingTimerActionEffect.ToggleTimer)
    }

    private fun assertNone(effect: PendingTimerActionEffect) {
        assertEquals(PendingTimerActionEffect.None, effect)
    }
}

private class SequenceIdentityGenerator : PendingTimerActionIdentityGenerator {
    private var next = 0

    override fun nextId(): String = "a6-id-${next++}"
}
