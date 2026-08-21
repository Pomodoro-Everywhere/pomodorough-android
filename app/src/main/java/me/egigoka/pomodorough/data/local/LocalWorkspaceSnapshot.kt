package me.egigoka.pomodorough.data.local

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class LocalWorkspaceCoordinator {
    private val mutex = Mutex()

    suspend fun <T> withLock(action: suspend () -> T): T {
        if (coroutineContext[WorkspaceLockContext]?.coordinator === this) return action()
        return mutex.withLock {
            withContext(WorkspaceLockContext(this)) { action() }
        }
    }

    private class WorkspaceLockContext(
        val coordinator: LocalWorkspaceCoordinator,
    ) : AbstractCoroutineContextElement(WorkspaceLockContext) {
        companion object : CoroutineContext.Key<WorkspaceLockContext>
    }
}

data class LocalWorkspaceSnapshot(
    val local: LocalStateEntity,
    val commands: List<PendingCommandEntity> = emptyList(),
    val taskOperations: List<PendingTaskOperationEntity> = emptyList(),
    val durationOperations: List<PendingDurationOperationEntity> = emptyList(),
    val autoStartOperations: List<PendingAutoStartOperationEntity> = emptyList(),
    val selectedTaskOperations: List<PendingSelectedTaskOperationEntity> = emptyList(),
    val bootstrapResolution: PendingBootstrapResolutionEntity? = null,
)
