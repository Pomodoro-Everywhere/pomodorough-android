package me.egigoka.pomodorough.integration

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode

internal data class P225CredentialLossScenario(
    val initial: AuthCredentialState,
    val lost: AuthCredentialState,
    val mode: ReplicationMode,
    val autoStart: Boolean,
)

internal fun p225CredentialLossScenarios(): List<P225CredentialLossScenario> =
    listOf(AuthCredentialState.Empty, AuthCredentialState.Active).flatMap { initial ->
        listOf(AuthCredentialState.Unreadable, AuthCredentialState.LogoutPending).flatMap { lost ->
            listOf(ReplicationMode.OFFLINE, ReplicationMode.IROH).flatMap { mode ->
                listOf(false, true).map { autoStart ->
                    P225CredentialLossScenario(initial, lost, mode, autoStart)
                }
            }
        }
    }

internal class P225DelayedAccountService(
    private val delayProfile: Boolean = true,
) : PomodoroughService by TestRepositoryService() {
    val blocked = CompletableDeferred<Unit>()
    val profileCalls = AtomicInteger()
    val bootstrapCalls = AtomicInteger()

    override suspend fun me(accessToken: String): MeResponse {
        profileCalls.incrementAndGet()
        if (delayProfile) {
            blocked.complete(Unit)
            awaitCancellation()
        }
        return MeResponse(testUser(), "csrf-token")
    }

    override suspend fun bootstrap(accessToken: String): SyncResponse {
        bootstrapCalls.incrementAndGet()
        blocked.complete(Unit)
        awaitCancellation()
    }
}

internal class P225ReloadReplication(
    mode: ReplicationMode = ReplicationMode.OFFLINE,
) : IrohReplicationController {
    override val state = MutableStateFlow(IrohNetworkState(mode = mode))
    var onInitialize: suspend () -> Unit = {}
    var onMutation: suspend () -> Unit = {}
    val initializeCalls = AtomicInteger()
    val mutationCalls = AtomicInteger()

    override suspend fun initialize() {
        initializeCalls.incrementAndGet()
        onInitialize()
    }
    override suspend fun setMode(mode: ReplicationMode) = error("Unused")
    override suspend fun createRoom(name: String) = error("Unused")
    override suspend fun joinRoom(invite: String) = error("Unused")
    override suspend fun leaveRoom() = error("Unused")
    override suspend fun refreshInvite() = error("Unused")
    override suspend fun syncNow() = error("Unused")
    override suspend fun afterLocalMutation() {
        mutationCalls.incrementAndGet()
        onMutation()
    }
    override suspend fun clearAccountData() = error("Unused")
    override fun onForeground() = Unit
}
