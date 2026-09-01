package me.egigoka.pomodorough

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

internal enum class PendingTimerActionStep {
    Idle,
    NotificationIntro,
    NotificationPermission,
    NotificationRecovery,
    NotificationSettings,
    ExactAlarmCheck,
    ExactAlarmFallback,
    ExactAlarmSettings,
}

internal data class PendingTimerActionCallbackIdentity(
    val actionId: String,
    val launchId: String,
)

internal data class PendingTimerActionState(
    val step: PendingTimerActionStep,
    val actionId: String? = null,
    val launchId: String? = null,
)

internal data class PendingTimerActionSavedState(
    val stepName: String,
    val actionId: String?,
    val launchId: String?,
)

internal sealed interface PendingTimerActionEffect {
    data object None : PendingTimerActionEffect
    data class CheckExactAlarm(val callback: PendingTimerActionCallbackIdentity) : PendingTimerActionEffect
    data class ToggleTimer(val actionId: String) : PendingTimerActionEffect
    data class RequestNotificationPermission(
        val callback: PendingTimerActionCallbackIdentity,
    ) : PendingTimerActionEffect

    data class OpenNotificationSettings(
        val callback: PendingTimerActionCallbackIdentity,
    ) : PendingTimerActionEffect

    data class OpenExactAlarmSettings(
        val callback: PendingTimerActionCallbackIdentity,
    ) : PendingTimerActionEffect
}

internal fun interface PendingTimerActionIdentityGenerator {
    fun nextId(): String
}

internal class PendingTimerActionCoordinator private constructor(
    restoredState: PendingTimerActionState,
    private val identityGenerator: PendingTimerActionIdentityGenerator,
) {
    var state by mutableStateOf(restoredState)
        private set
    val step: PendingTimerActionStep
        get() = state.step
    private var attached = true

    fun begin(needsNotificationPermission: Boolean): PendingTimerActionEffect {
        if (!canBegin()) return PendingTimerActionEffect.None
        val actionId = nextId()
        if (needsNotificationPermission) {
            state = PendingTimerActionState(PendingTimerActionStep.NotificationIntro, actionId)
            return PendingTimerActionEffect.None
        }
        return beginExactAlarmCheck(actionId)
    }

    fun confirmNotificationIntro(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.NotificationIntro, actionId)) {
            return PendingTimerActionEffect.None
        }
        return beginLaunch(actionId, PendingTimerActionStep.NotificationPermission) {
            PendingTimerActionEffect.RequestNotificationPermission(it)
        }
    }

    fun declineNotificationIntro(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.NotificationIntro, actionId)) {
            return PendingTimerActionEffect.None
        }
        state = PendingTimerActionState(PendingTimerActionStep.NotificationRecovery, actionId)
        return PendingTimerActionEffect.None
    }

    fun notificationPermissionResult(
        callback: PendingTimerActionCallbackIdentity,
        granted: Boolean,
    ): PendingTimerActionEffect {
        if (!canComplete(PendingTimerActionStep.NotificationPermission, callback)) {
            return PendingTimerActionEffect.None
        }
        if (granted) return beginExactAlarmCheck(callback.actionId)
        state = PendingTimerActionState(PendingTimerActionStep.NotificationRecovery, callback.actionId)
        return PendingTimerActionEffect.None
    }

    fun openNotificationSettings(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.NotificationRecovery, actionId)) {
            return PendingTimerActionEffect.None
        }
        return beginLaunch(actionId, PendingTimerActionStep.NotificationSettings) {
            PendingTimerActionEffect.OpenNotificationSettings(it)
        }
    }

    fun notificationSettingsResult(
        callback: PendingTimerActionCallbackIdentity,
        granted: Boolean,
    ): PendingTimerActionEffect {
        if (!canComplete(PendingTimerActionStep.NotificationSettings, callback)) {
            return PendingTimerActionEffect.None
        }
        if (granted) return beginExactAlarmCheck(callback.actionId)
        state = PendingTimerActionState(PendingTimerActionStep.NotificationRecovery, callback.actionId)
        return PendingTimerActionEffect.None
    }

    fun continueWithoutNotifications(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.NotificationRecovery, actionId)) {
            return PendingTimerActionEffect.None
        }
        return beginExactAlarmCheck(actionId)
    }

    fun exactAlarmResult(
        callback: PendingTimerActionCallbackIdentity,
        needsFallback: Boolean,
    ): PendingTimerActionEffect {
        if (!canComplete(PendingTimerActionStep.ExactAlarmCheck, callback)) {
            return PendingTimerActionEffect.None
        }
        if (needsFallback) {
            state = PendingTimerActionState(PendingTimerActionStep.ExactAlarmFallback, callback.actionId)
            return PendingTimerActionEffect.None
        }
        return finish(callback.actionId)
    }

    fun openExactAlarmSettings(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.ExactAlarmFallback, actionId)) {
            return PendingTimerActionEffect.None
        }
        return beginLaunch(actionId, PendingTimerActionStep.ExactAlarmSettings) {
            PendingTimerActionEffect.OpenExactAlarmSettings(it)
        }
    }

    fun exactAlarmSettingsResult(
        callback: PendingTimerActionCallbackIdentity,
    ): PendingTimerActionEffect {
        if (!canComplete(PendingTimerActionStep.ExactAlarmSettings, callback)) {
            return PendingTimerActionEffect.None
        }
        return beginExactAlarmCheck(callback.actionId)
    }

    fun useInexactAlarm(actionId: String): PendingTimerActionEffect {
        if (!canAdvance(PendingTimerActionStep.ExactAlarmFallback, actionId)) {
            return PendingTimerActionEffect.None
        }
        return finish(actionId)
    }

    fun dismissDialog(actionId: String) {
        if (!attached || state.actionId != actionId || step !in cancellableSteps) return
        state = idleState
    }

    fun resumeAfterRestore(): PendingTimerActionEffect {
        val callback = pendingCallback() ?: return PendingTimerActionEffect.None
        return if (step == PendingTimerActionStep.ExactAlarmCheck) {
            PendingTimerActionEffect.CheckExactAlarm(callback)
        } else {
            PendingTimerActionEffect.None
        }
    }

    fun pendingCallback(): PendingTimerActionCallbackIdentity? {
        val actionId = state.actionId ?: return null
        val launchId = state.launchId ?: return null
        return PendingTimerActionCallbackIdentity(actionId, launchId)
    }

    fun savedState(): PendingTimerActionSavedState = PendingTimerActionSavedState(
        stepName = step.name,
        actionId = state.actionId,
        launchId = state.launchId,
    )

    fun detach() {
        attached = false
    }

    private fun beginExactAlarmCheck(actionId: String): PendingTimerActionEffect =
        beginLaunch(actionId, PendingTimerActionStep.ExactAlarmCheck) {
            PendingTimerActionEffect.CheckExactAlarm(it)
        }

    private fun beginLaunch(
        actionId: String,
        nextStep: PendingTimerActionStep,
        effect: (PendingTimerActionCallbackIdentity) -> PendingTimerActionEffect,
    ): PendingTimerActionEffect {
        val callback = PendingTimerActionCallbackIdentity(actionId, nextId())
        state = PendingTimerActionState(nextStep, actionId, callback.launchId)
        return effect(callback)
    }

    private fun finish(actionId: String): PendingTimerActionEffect {
        state = idleState
        return PendingTimerActionEffect.ToggleTimer(actionId)
    }

    private fun nextId(): String = identityGenerator.nextId().also { require(it.isNotBlank()) }

    private fun canBegin(): Boolean = attached && step == PendingTimerActionStep.Idle

    private fun canAdvance(expected: PendingTimerActionStep, actionId: String): Boolean =
        attached && step == expected && state.actionId == actionId && state.launchId == null

    private fun canComplete(
        expected: PendingTimerActionStep,
        callback: PendingTimerActionCallbackIdentity,
    ): Boolean = attached && step == expected && pendingCallback() == callback

    companion object {
        private val idleState = PendingTimerActionState(PendingTimerActionStep.Idle)
        private val cancellableSteps = setOf(
            PendingTimerActionStep.NotificationIntro,
            PendingTimerActionStep.NotificationRecovery,
            PendingTimerActionStep.ExactAlarmFallback,
        )
        private val awaitingSteps = setOf(
            PendingTimerActionStep.NotificationPermission,
            PendingTimerActionStep.NotificationSettings,
            PendingTimerActionStep.ExactAlarmCheck,
            PendingTimerActionStep.ExactAlarmSettings,
        )
        private val randomIdentityGenerator = PendingTimerActionIdentityGenerator {
            UUID.randomUUID().toString()
        }

        fun restore(
            savedState: PendingTimerActionSavedState?,
            identityGenerator: PendingTimerActionIdentityGenerator = randomIdentityGenerator,
        ): PendingTimerActionCoordinator = PendingTimerActionCoordinator(
            restoredState = validatedRestoredState(savedState),
            identityGenerator = identityGenerator,
        )

        private fun validatedRestoredState(saved: PendingTimerActionSavedState?): PendingTimerActionState {
            val restored = saved ?: return idleState
            val step = PendingTimerActionStep.entries.firstOrNull { it.name == restored.stepName }
                ?: return idleState
            val actionId = restored.actionId?.takeIf(String::isNotBlank)
            val launchId = restored.launchId?.takeIf(String::isNotBlank)
            if (step == PendingTimerActionStep.Idle) return idleState
            if (step in cancellableSteps && actionId != null && launchId == null) {
                return PendingTimerActionState(step, actionId)
            }
            if (step in awaitingSteps && actionId != null && launchId != null) {
                return PendingTimerActionState(step, actionId, launchId)
            }
            return idleState
        }
    }
}
