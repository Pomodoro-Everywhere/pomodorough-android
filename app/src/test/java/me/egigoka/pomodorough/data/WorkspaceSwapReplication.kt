package me.egigoka.pomodorough.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.egigoka.pomodorough.data.iroh.IrohConflictEvidence
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.TimerDao

/**
 * Stands in for the Iroh route in UX round 2 S1 tests. Moving between home and a room swaps the
 * whole local workspace, as the room persistence does: the workspace that leaves the screen is
 * kept in [away] with its queue, and the other one is installed. No endpoint and no peer exist.
 */
internal class WorkspaceSwapReplication(
    private val dao: TimerDao,
    initial: ReplicationMode,
) : IrohReplicationController {
    private val route = MutableStateFlow(IrohNetworkState(mode = initial))
    override val state: StateFlow<IrohNetworkState> = route

    /** The workspace that is not on screen. A test seeds it before the first swap. */
    var away: LocalWorkspaceSnapshot? = null

    /** Copies the workspace on screen into [away], so both workspaces start with the same tasks. */
    suspend fun copyCurrentToAway() {
        away = dao.localWorkspaceSnapshot()
    }

    /** Publishes a room conflict, which blocks the room's mutations until the route changes. */
    fun reportConflict(evidence: IrohConflictEvidence) {
        route.value = route.value.copy(conflict = evidence)
    }

    override suspend fun initialize() = Unit
    override suspend fun setMode(mode: ReplicationMode) = moveTo(mode)
    override suspend fun createRoom(name: String) = moveTo(ReplicationMode.IROH)
    override suspend fun joinRoom(invite: String) = moveTo(ReplicationMode.IROH)
    override suspend fun leaveRoom() = moveTo(ReplicationMode.OFFLINE)
    override suspend fun refreshInvite() = Unit
    override suspend fun syncNow() = Unit
    override suspend fun afterLocalMutation() = Unit
    override suspend fun clearAccountData() = Unit
    override fun onForeground() = Unit

    private suspend fun moveTo(mode: ReplicationMode) {
        val inRoom = route.value.mode == ReplicationMode.IROH
        if (inRoom != (mode == ReplicationMode.IROH)) swap()
        route.value = IrohNetworkState(mode = mode)
    }

    private suspend fun swap() {
        val other = checkNotNull(away) { "seed the other workspace before the swap" }
        away = dao.localWorkspaceSnapshot()
        dao.replaceWorkspace(other)
    }
}
