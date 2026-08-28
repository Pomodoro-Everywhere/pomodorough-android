package me.egigoka.pomodorough.data

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.core.SharedCoreException
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

internal data class CentralizedSyncRuntimeSnapshot(
    val signedIn: Boolean,
    val centralized: Boolean,
    val resolutionPending: Boolean,
    val accountSwitchPending: Boolean,
    val terminalSyncError: Boolean,
    val pendingQueuesEmpty: Boolean,
    val localRevision: Long,
)

internal enum class SyncPauseReason { WorkspaceTransition, Unavailable, NoPending }

internal sealed interface CentralizedSyncRuntimeEvent {
    data class Paused(
        val accountGeneration: Long,
        val reason: SyncPauseReason,
    ) : CentralizedSyncRuntimeEvent

    data class SyncResponseReady(
        val attempt: SyncAttempt,
        val response: SyncResponse,
        val receivedPhysicalMs: Long,
        val receivedElapsedRealtimeMs: Long,
    ) : CentralizedSyncRuntimeEvent

    data class AuthenticationExpired(val identity: SyncAttemptIdentity) : CentralizedSyncRuntimeEvent

    data class TerminalFailure(
        val identity: SyncAttemptIdentity,
        val error: Throwable,
    ) : CentralizedSyncRuntimeEvent

    data class Retrying(
        val identity: SyncAttemptIdentity,
        val delayMs: Long,
    ) : CentralizedSyncRuntimeEvent

    data class LocalFailure(
        val identity: SyncAttemptIdentity,
        val error: Throwable,
    ) : CentralizedSyncRuntimeEvent

    data class RevisionAuthenticationExpired(
        val accountGeneration: Long,
    ) : CentralizedSyncRuntimeEvent
}

internal interface CentralizedSyncRuntimeHost {
    fun snapshot(): CentralizedSyncRuntimeSnapshot

    fun accountGeneration(): Long

    fun revisionStreamAdmission(): RevisionStreamAdmission

    suspend fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt?

    suspend fun accept(event: CentralizedSyncRuntimeEvent) {
        throw UnsupportedOperationException("Centralized sync runtime events require a host")
    }
}

internal data class CentralizedNetworkTransition(
    val online: Boolean,
    val restored: Boolean,
)

internal class CentralizedSyncRuntime(
    private val initialized: Deferred<Unit>,
    private val initialRetryDelayMs: Long,
    private val remoteSyncIntervalMs: Long,
    private val currentTimeMillis: () -> Long,
    private val elapsedRealtimeMillis: () -> Long,
    initialOnline: Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    json: Json,
    private val host: CentralizedSyncRuntimeHost,
    private val executeSync: suspend (SyncRequest) -> SyncResponse,
    openRevisionStream: suspend (EventSourceListener) -> EventSource,
    revisionReconnectDelayMs: Long = 5_000,
    private val newAttemptId: () -> String = { UUID.randomUUID().toString() },
) {
    private val runtimeJob: Job = SupervisorJob()
    private val scope = CoroutineScope(runtimeJob + dispatcher)
    private val syncSignals = Channel<Unit>(Channel.CONFLATED)
    private val forceSync = java.util.concurrent.atomic.AtomicBoolean(false)
    private val revisionLifecycle = RevisionStreamLifecycle(
        scope = scope,
        initialized = initialized,
        json = json,
        reconnectDelayMs = revisionReconnectDelayMs,
        initialOnline = initialOnline,
        admission = host::revisionStreamAdmission,
        open = openRevisionStream,
        emit = ::acceptRevisionEvent,
        expireAuthentication = { accountGeneration ->
            host.accept(CentralizedSyncRuntimeEvent.RevisionAuthenticationExpired(accountGeneration))
        },
    )

    val foreground: Boolean get() = revisionLifecycle.foreground
    val online: Boolean get() = revisionLifecycle.online

    init {
        require(remoteSyncIntervalMs > 0) { "Remote sync interval must be positive" }
        scope.launch { syncLoop() }
        scope.launch { periodicSyncLoop() }
    }

    fun requestSync(force: Boolean = false) {
        if (!host.snapshot().centralized) return
        if (force) forceSync.set(true)
        syncSignals.trySend(Unit)
    }

    fun markForeground(value: Boolean) {
        revisionLifecycle.markForeground(value)
    }

    fun resumeForeground() {
        revisionLifecycle.requestOpen()
        if (host.snapshot().centralized) requestSync(force = true)
    }

    fun resumeBackground() = revisionLifecycle.requestClose()

    fun updateOnlineState(value: Boolean): CentralizedNetworkTransition {
        val restored = !online && value
        revisionLifecycle.setOnline(value)
        return CentralizedNetworkTransition(value, restored)
    }

    fun resumeNetworkTransition(transition: CentralizedNetworkTransition) {
        if (transition.restored) {
            requestSync(force = true)
            revisionLifecycle.requestOpen()
        } else if (!transition.online) {
            revisionLifecycle.requestClose()
        }
    }

    fun requestRevisionOpen() = revisionLifecycle.requestOpen()

    fun requestRevisionClose() = revisionLifecycle.requestClose()

    suspend fun closeRevisionStream() = revisionLifecycle.closeNow()

    suspend fun awaitPendingRevisionSignals() = revisionLifecycle.awaitPendingSignals()

    suspend fun shutdown() {
        revisionLifecycle.shutdown()
        runtimeJob.cancelAndJoin()
    }

    private suspend fun periodicSyncLoop() {
        while (scope.isActive) {
            delay(remoteSyncIntervalMs)
            val state = host.snapshot()
            if (foreground && online && state.signedIn && state.centralized &&
                !state.resolutionPending && !state.accountSwitchPending
            ) requestSync(force = true)
        }
    }

    private suspend fun syncLoop() {
        for (signal in syncSignals) runSyncSignal()
    }

    private suspend fun runSyncSignal() {
        initialized.await()
        var forced = forceSync.getAndSet(false)
        var retryDelay = initialRetryDelayMs
        while (syncContextActive()) {
            if (!syncMayRun(forced)) break
            val identity = SyncAttemptIdentity(host.accountGeneration(), newAttemptId())
            when (val result = runSyncIteration(identity, retryDelay)) {
                SyncIterationResult.Success -> {
                    retryDelay = initialRetryDelayMs
                    forced = false
                    if (host.snapshot().pendingQueuesEmpty) break
                }
                SyncIterationResult.Stop -> break
                is SyncIterationResult.Retry -> {
                    retryDelay = result.delayMs
                    forced = true
                }
            }
        }
    }

    private fun syncContextActive(): Boolean {
        val state = host.snapshot()
        return scope.isActive && state.signedIn && state.centralized
    }

    private suspend fun syncMayRun(forced: Boolean): Boolean {
        val state = host.snapshot()
        val reason = when {
            state.resolutionPending || state.accountSwitchPending -> SyncPauseReason.WorkspaceTransition
            state.terminalSyncError || !online -> SyncPauseReason.Unavailable
            !forced && state.pendingQueuesEmpty -> SyncPauseReason.NoPending
            else -> return true
        }
        host.accept(CentralizedSyncRuntimeEvent.Paused(host.accountGeneration(), reason))
        return false
    }

    private suspend fun runSyncIteration(
        identity: SyncAttemptIdentity,
        retryDelay: Long,
    ): SyncIterationResult = try {
        syncOnce(identity)
        SyncIterationResult.Success
    } catch (_: AuthenticationRequired) {
        host.accept(CentralizedSyncRuntimeEvent.AuthenticationExpired(identity))
        SyncIterationResult.Stop
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        handleSyncFailure(identity, error, retryDelay)
    }

    private suspend fun handleSyncFailure(
        identity: SyncAttemptIdentity,
        error: Exception,
        retryDelay: Long,
    ): SyncIterationResult = when {
        error is ApiException && error.isRetryable() -> retrySync(identity, retryDelay)
        error is ApiException -> {
            host.accept(CentralizedSyncRuntimeEvent.TerminalFailure(identity, error))
            SyncIterationResult.Stop
        }
        error is IOException -> retrySync(identity, retryDelay)
        error.isTerminalSyncFailure() -> {
            host.accept(CentralizedSyncRuntimeEvent.TerminalFailure(identity, error))
            SyncIterationResult.Stop
        }
        else -> {
            host.accept(CentralizedSyncRuntimeEvent.LocalFailure(identity, error))
            SyncIterationResult.Stop
        }
    }

    private suspend fun retrySync(
        identity: SyncAttemptIdentity,
        delayMs: Long,
    ): SyncIterationResult.Retry {
        host.accept(CentralizedSyncRuntimeEvent.Retrying(identity, delayMs))
        delay(delayMs)
        return SyncIterationResult.Retry((delayMs * 2).coerceAtMost(60_000L))
    }

    private suspend fun syncOnce(identity: SyncAttemptIdentity) {
        val attempt = host.prepareSyncAttempt(identity) ?: return
        val response = executeSync(attempt.request)
        host.accept(
            CentralizedSyncRuntimeEvent.SyncResponseReady(
                attempt = attempt,
                response = response,
                receivedPhysicalMs = currentTimeMillis(),
                receivedElapsedRealtimeMs = elapsedRealtimeMillis(),
            ),
        )
    }

    private fun acceptRevisionEvent(event: RevisionStreamEvent) {
        when (event) {
            is RevisionStreamEvent.RevisionObserved -> {
                if (event.revision == null || event.revision > host.snapshot().localRevision) {
                    requestSync(force = true)
                }
            }
            RevisionStreamEvent.Unauthorized -> requestSync(force = true)
            is RevisionStreamEvent.AuthenticationExpired -> scope.launch {
                host.accept(
                    CentralizedSyncRuntimeEvent.RevisionAuthenticationExpired(event.accountGeneration),
                )
            }
        }
    }
}

private sealed interface SyncIterationResult {
    data object Success : SyncIterationResult

    data object Stop : SyncIterationResult

    data class Retry(val delayMs: Long) : SyncIterationResult
}

private fun ApiException.isRetryable(): Boolean =
    statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500

private fun Exception.isTerminalSyncFailure(): Boolean =
    this is SyncProtocolException || this is SerializationException ||
        this is CoreProjectionException || this is SharedCoreException ||
        this is ApiException
