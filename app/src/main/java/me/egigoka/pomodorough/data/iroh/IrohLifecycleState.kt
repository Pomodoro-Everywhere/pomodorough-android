package me.egigoka.pomodorough.data.iroh

internal data class IrohLifecycleSnapshot(
    val foreground: Boolean,
    val generation: Long,
)

internal sealed interface IrohLifecycleEvent {
    val snapshot: IrohLifecycleSnapshot

    data class EnteredForeground(
        override val snapshot: IrohLifecycleSnapshot,
    ) : IrohLifecycleEvent

    data class EnteredBackground(
        override val snapshot: IrohLifecycleSnapshot,
    ) : IrohLifecycleEvent
}

internal class IrohLifecycleState {
    private val monitor = Any()

    @Volatile
    private var current = IrohLifecycleSnapshot(foreground = false, generation = 0L)

    fun enterForeground(): IrohLifecycleEvent.EnteredForeground = synchronized(monitor) {
        IrohLifecycleEvent.EnteredForeground(transition(foreground = true))
    }

    // R54-A05: a repeated foreground keeps the current generation, so it cannot strand an
    // endpoint start that the current foreground owns. Returns null when already foreground.
    fun enterForegroundIfBackground(): IrohLifecycleEvent.EnteredForeground? = synchronized(monitor) {
        if (current.foreground) null else IrohLifecycleEvent.EnteredForeground(transition(foreground = true))
    }

    fun enterBackground(): IrohLifecycleEvent.EnteredBackground = synchronized(monitor) {
        IrohLifecycleEvent.EnteredBackground(transition(foreground = false))
    }

    fun snapshot(): IrohLifecycleSnapshot = current

    fun permitsEndpoint(owner: Long): Boolean {
        val snapshot = current
        return snapshot.foreground && snapshot.generation == owner
    }

    fun permitsBackgroundStop(owner: Long): Boolean {
        val snapshot = current
        return !snapshot.foreground && snapshot.generation == owner
    }

    private fun transition(foreground: Boolean): IrohLifecycleSnapshot {
        return IrohLifecycleSnapshot(foreground, current.generation + 1L).also { current = it }
    }
}
