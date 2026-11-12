package me.egigoka.pomodorough.data.iroh

import me.egigoka.pomodorough.data.local.IrohRoomEntity

/** The persisted replication route that initialization reads before it publishes any state. */
internal data class IrohStartupRoute(
    val mode: ReplicationMode,
    val room: IrohRoomEntity?,
    val snapshot: IrohNetworkState?,
)

/**
 * R54-A07: a storage read for the startup route failed. Initialization published nothing and
 * stays open, so the next initialize() reads the route again. The orchestration already sent
 * the bounded route-read report, without persisted content.
 */
internal class IrohRouteReadException(cause: Exception) : Exception(cause)
