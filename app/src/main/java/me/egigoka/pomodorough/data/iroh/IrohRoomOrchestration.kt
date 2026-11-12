package me.egigoka.pomodorough.data.iroh

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.CorruptStateCategory
import me.egigoka.pomodorough.data.CorruptStateReporter
import me.egigoka.pomodorough.data.local.IrohRoomEntity
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.ReplicationSettingsEntity

internal data class IrohRoomServicePort(
    val state: StateFlow<IrohNetworkState>,
    val publishState: (IrohNetworkState) -> Unit,
    val transformState: ((IrohNetworkState) -> IrohNetworkState) -> Unit,
    val start: suspend (IrohServiceContext, Boolean) -> String,
    val stop: suspend () -> Unit,
    val close: suspend () -> Unit,
    val stopIf: suspend (() -> Boolean) -> Unit,
    val join: suspend (IrohRoomInvite) -> IrohRoomProjection,
    val endpointIdForTicket: (String) -> String,
    val syncNow: suspend () -> Unit,
    val pendingIdentityRecovery: () -> IrohIdentityRecoveryKind? = { null },
    val replaceEndpointIdentity: suspend () -> Unit = ::unsupportedRecoveryAction,
    val beginIdentityReset: suspend () -> Unit = ::unsupportedRecoveryAction,
    val completeIdentityReset: suspend () -> Unit = ::unsupportedRecoveryAction,
)

internal data class IrohRoomPersistencePort(
    val localState: suspend () -> LocalStateEntity?,
    val room: suspend (String) -> IrohRoomEntity?,
    val replicationSettings: suspend () -> ReplicationSettingsEntity,
    val discardIncompleteRooms: suspend () -> Unit,
    val setMode: suspend (ReplicationMode) -> Unit,
    val snapshot: suspend (String) -> IrohNetworkState,
    val captureLocalOperations: suspend () -> IrohRoomProjection,
    val createRoom: suspend (String?) -> Pair<IrohRoomEntity, IrohRoomProjection>,
    val activeRoom: suspend () -> IrohRoomEntity?,
    val activeRoomSecret: suspend () -> ByteArray?,
    val prepareJoinedRoom: suspend (IrohRoomInvite, String) -> Pair<IrohRoomEntity, Boolean>,
    val discardIncompleteInactiveRoom: suspend (String) -> Unit,
    val leaveActiveRoom: suspend () -> Unit,
    val clearAccountData: suspend () -> Unit,
    val scrubDeletedAccount: suspend () -> Unit = ::unsupportedRecoveryAction,
    val validateRoomSecrets: suspend () -> Unit = ::unsupportedRecoveryAction,
    val resetIdentityData: suspend () -> Unit = ::unsupportedRecoveryAction,
)

internal class IrohRoomOrchestration(
    private val service: IrohRoomServicePort,
    private val persistence: IrohRoomPersistencePort,
) {
    private val orchestrationJob: Job = SupervisorJob()
    private val scope = CoroutineScope(orchestrationJob + Dispatchers.IO)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val accountQuarantined = AtomicBoolean(false)
    private val recoveryRequested = AtomicBoolean(false)
    private val recoveryGeneration = AtomicLong()
    private val lifecycle = IrohLifecycleState()
    private var initialized = false

    @Volatile
    private var invite: String? = null

    // R54-A14: [routeBeforeJoin] is the persisted route while the join runs, until service.join
    // returns: that call activates the joined room as the IROH route, and [activated] records it.
    private class JoinPreparation(
        val roomId: String,
        val createdRoom: Boolean,
        val routeBeforeJoin: IrohNetworkState,
    ) {
        var activated = false
    }

    // R54-A10: the collector re-attaches the cached invite to the current state in one atomic
    // step. Publishing the delivered value instead overwrote any newer state written meanwhile.
    init {
        scope.launch {
            service.state.collect { attachCachedInvite() }
        }
    }

    suspend fun initialize() {
        mutex.withLock { initializeLocked() }
    }

    suspend fun setMode(mode: ReplicationMode) = runIrohAction {
        if (mode == service.state.value.mode) {
            if (mode == ReplicationMode.IROH && isForeground()) startIfNeeded()
            return@runIrohAction
        }
        publish(service.state.value.copy(transitioning = true))
        try {
            if (service.state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            persistence.setMode(mode)
            invite = null
            val room = persistence.activeRoom()
            val snapshot = room?.let { persistence.snapshot(it.roomId) }
            publish((snapshot ?: IrohNetworkState()).copy(mode = mode, transitioning = false))
            if (mode == ReplicationMode.IROH && isForeground()) startIfNeeded()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error !is IrohSecretVaultException) CrashReporter.report(error)
            recoverPersistedRoute(error, "Replication route could not be changed")
        }
    }

    suspend fun createRoom(name: String) = runIrohAction {
        publish(service.state.value.copy(transitioning = true))
        var roomId: String? = null
        try {
            if (service.state.value.mode == ReplicationMode.IROH) {
                flushActiveIrohLocked()
                service.stop()
            }
            val normalized = name.trim().ifEmpty { null }
            val (room, _) = persistence.createRoom(normalized)
            roomId = room.roomId
            publish(persistence.snapshot(room.roomId).copy(
                mode = ReplicationMode.IROH,
                status = IrohConnectionStatus.STARTING,
                transitioning = false,
            ))
            val ticket = startIfNeeded()
            val secret = checkNotNull(persistence.activeRoomSecret())
            invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
            secret.fill(0)
            publish(service.state.value.copy(invite = invite, message = null))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error !is IrohSecretVaultException) CrashReporter.report(error)
            handleCreateFailure(roomId, error)
        }
    }

    suspend fun joinRoom(encodedInvite: String) = runIrohAction {
        publish(service.state.value.copy(transitioning = true))
        var decoded: IrohRoomInvite? = null
        var preparation: JoinPreparation? = null
        try {
            decoded = IrohRoomInvite.decode(encodedInvite.trim())
            preparation = prepareJoinedRoom(decoded)
            connectJoinedRoom(decoded, preparation)
            completeJoinedRoom(decoded.roomId)
        } catch (error: CancellationException) {
            if (preparation != null) stopCancelledJoin(preparation)
            throw error
        } catch (error: Exception) {
            rollbackJoinedRoom(preparation)
            if (error !is IrohSecretVaultException) CrashReporter.report(error)
            preparation?.let(::publishKnownJoinRoute)
            recoverPersistedRoute(error, "Iroh room could not be joined")
        } finally {
            decoded?.roomSecret?.fill(0)
        }
    }

    suspend fun leaveRoom() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH) return@runIrohAction
        publish(service.state.value.copy(transitioning = true))
        try {
            flushActiveIrohLocked()
            service.stop()
            persistence.leaveActiveRoom()
            invite = null
            publish(IrohNetworkState(mode = ReplicationMode.OFFLINE, transitioning = false))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error !is IrohSecretVaultException) CrashReporter.report(error)
            recoverPersistedRoute(error, "Iroh room could not be left")
        }
    }

    suspend fun refreshInvite() = runIrohAction {
        try {
            val room = checkNotNull(persistence.activeRoom()) { "No Iroh room is active" }
            val ticket = startIfNeeded()
            val secret = checkNotNull(persistence.activeRoomSecret())
            invite = IrohRoomInvite(room.roomId, room.roomName, ticket, secret).encode()
            secret.fill(0)
            publish(service.state.value.copy(invite = invite, message = null))
        } catch (error: CancellationException) {
            throw error
        } catch (error: IrohSecretVaultException) {
            // expected-silent: vault recovery is reported through the recovery UI, not a crash.
            quarantineRecovery(error.recoveryKind)
        } catch (error: Exception) {
            // R52-A04: explicit refresh stays inside the recoverable
            // room-action boundary. The room row is untouched, so publish
            // the failure on current state without restarting the endpoint
            // a second time; retry re-enters through this same action.
            CrashReporter.report(error)
            publish(service.state.value.copy(
                status = IrohConnectionStatus.UNAVAILABLE,
                message = "Iroh invite could not be refreshed",
                transitioning = false,
            ))
        }
    }

    suspend fun syncNow() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH || !isForeground()) {
            return@runIrohAction
        }
        try {
            startIfNeeded()
            service.syncNow()
        } catch (error: CancellationException) {
            throw error
        } catch (error: IrohSecretVaultException) {
            // expected-silent: vault recovery is reported through the recovery UI, not a crash.
            quarantineRecovery(error.recoveryKind)
        } catch (_: Exception) {
            // expected-silent: explicit retry failures use recovery UI; native endpoint diagnostics
            // belong to the lifecycle. Do not duplicate them or expose exception text and secrets.
            publishSyncUnavailable()
        }
    }

    suspend fun afterLocalMutation() = runIrohAction {
        if (service.state.value.mode != ReplicationMode.IROH) return@runIrohAction
        val projection = persistence.captureLocalOperations()
        publish(service.state.value.copy(operationCount = projection.operationCount))
        if (isForeground()) {
            if (startUnlessIdentityRecoveryRequired()) service.syncNow()
        }
    }

    suspend fun confirmIdentityRecovery() {
        if (!recoveryRequested.compareAndSet(false, true)) return
        recoveryGeneration.incrementAndGet()
        var restart = false
        try {
            mutex.withLock { restart = recoverIdentityLocked() }
        } finally {
            recoveryRequested.set(false)
        }
        if (restart) restartForeground()
    }

    suspend fun clearAccountData() = mutex.withLock {
        persistence.clearAccountData()
        // R50-A03: drop cached invite before publishing so the state
        // collector cannot restore a scrubbed credential. Logout keeps
        // device-local rows (see metadata persistence); only the
        // published presentation is cleared here.
        invite = null
        publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
        initialized = true
    }

    suspend fun scrubDeletedAccount() = mutex.withLock {
        persistence.scrubDeletedAccount()
        // R50-A03: drop cached invite before publishing so the state
        // collector cannot restore a deleted room credential. Deletion
        // wipes rows and vault secrets; logout retains them.
        invite = null
        publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
        initialized = true
    }

    suspend fun quarantineAccount() {
        accountQuarantined.set(true)
        lifecycle.enterBackground()
        mutex.withLock {
            service.stopIf { true }
        }
    }

    fun releaseAccountQuarantine() {
        accountQuarantined.set(false)
    }

    // R54-A05: TimerRepository.initialize() replays foreground on every call. A replay while
    // already foreground neither renews the lifecycle generation nor binds the endpoint again.
    fun onForeground() {
        if (closed.get() || accountQuarantined.get()) return
        lifecycle.enterForegroundIfBackground() ?: return
        launchForegroundStart()
    }

    // Identity repair restarts the endpoint only while the app is still foreground. A background
    // that lands during the repair keeps the endpoint down until the next foreground: the restart
    // never forces a lifecycle transition, so it cannot bind behind a backgrounded app.
    private fun restartForeground() {
        if (closed.get() || !isForeground()) return
        launchForegroundStart()
    }

    private fun launchForegroundStart() {
        scope.launch {
            runIrohAction {
                if (service.state.value.mode == ReplicationMode.IROH) {
                    startUnlessIdentityRecoveryRequired()
                }
            }
        }
    }

    fun onBackground() {
        if (closed.get()) return
        val event = lifecycle.enterBackground()
        scope.launch {
            service.stopIf { lifecycle.permitsBackgroundStop(event.snapshot.generation) }
        }
    }

    suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        lifecycle.enterBackground()
        orchestrationJob.cancelAndJoin()
        service.close()
    }

    // Returns false when this call's first foreground endpoint start failed. The failure is
    // already published, so the caller's action must not bind or report the endpoint again.
    private suspend fun initializeLocked(): Boolean {
        if (initialized) return true
        val pendingRecovery = service.pendingIdentityRecovery()
        val (mode, room, snapshot) = readStartupRoute(discardIncomplete = pendingRecovery == null)
        pendingRecovery?.let { kind ->
            publish((snapshot ?: IrohNetworkState()).copy(mode = mode))
            quarantineRecovery(kind)
            initialized = true
            return true
        }
        if (mode == ReplicationMode.IROH && room == null) {
            persistence.setMode(ReplicationMode.OFFLINE)
            publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
            initialized = true
            return true
        }
        publish((snapshot ?: IrohNetworkState()).copy(mode = mode))
        if (!recoverLocalOperations(mode, snapshot)) return true
        initialized = true
        val startsEndpoint =
            mode == ReplicationMode.IROH && snapshot?.conflict == null && isForeground()
        return !startsEndpoint || startInitialEndpoint()
    }

    // R54-A07: a failed route read publishes nothing and leaves initialization open, so the next
    // call reads the route again. It reports one bounded category without persisted content or SQL.
    private suspend fun readStartupRoute(discardIncomplete: Boolean): IrohStartupRoute = try {
        if (discardIncomplete) persistence.discardIncompleteRooms()
        val settings = persistence.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.CENTRALIZED)
        val room = settings.activeRoomId?.let { persistence.room(it) }
        IrohStartupRoute(mode, room, room?.let { persistence.snapshot(it.roomId) })
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        CorruptStateReporter.report(CorruptStateCategory.ReplicationRouteRead)
        throw IrohRouteReadException(error)
    }

    private suspend fun startInitialEndpoint(): Boolean = try {
        startIfNeeded()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: IrohSecretVaultException) {
        // expected-silent: vault recovery is reported through the recovery UI, not a crash.
        quarantineRecovery(error.recoveryKind)
        false
    } catch (_: Exception) {
        // R54-A03: Sync now's policy. Initialization completes with the room, operations, and
        // secret kept, so Sync now or the next foreground binds again. expected-silent: the
        // endpoint lifecycle reports bind and ticket failures; the state carries no error text.
        publishSyncUnavailable()
        false
    }

    private suspend fun recoverLocalOperations(
        mode: ReplicationMode,
        snapshot: IrohNetworkState?,
    ): Boolean {
        if (mode != ReplicationMode.IROH || snapshot?.conflict != null) return true
        return runCatching { persistence.captureLocalOperations() }.fold(
            onSuccess = { true },
            onFailure = { error ->
                if (error is CancellationException) throw error
                if (error is IrohSecretVaultException) {
                    quarantineRecovery(error.recoveryKind)
                    false
                } else {
                    CrashReporter.report(error)
                    publish(service.state.value.copy(
                        status = IrohConnectionStatus.UNAVAILABLE,
                        message = "Saved Iroh operations could not be recovered",
                    ))
                    false
                }
            },
        )
    }

    private suspend fun handleCreateFailure(roomId: String?, error: Exception) {
        if (error is IrohSecretVaultException) {
            // expected-silent: vault recovery is reported through the recovery UI, not a crash.
            quarantineRecovery(error.recoveryKind)
            return
        }
        if (roomId == null) {
            recoverPersistedRoute(error, "Iroh room could not be created")
        } else {
            publish(service.state.value.copy(
                mode = ReplicationMode.IROH,
                status = IrohConnectionStatus.UNAVAILABLE,
                message = "Iroh endpoint could not start",
                transitioning = false,
            ))
        }
    }

    private suspend fun prepareJoinedRoom(invite: IrohRoomInvite): JoinPreparation {
        val endpointId = service.endpointIdForTicket(invite.endpointTicket)
        if (service.state.value.mode == ReplicationMode.IROH) {
            flushActiveIrohLocked()
            service.stop()
        }
        val routeBeforeJoin = service.state.value
        val createdRoom = persistence.prepareJoinedRoom(invite, endpointId).second
        publish(IrohNetworkState(
            mode = service.state.value.mode,
            status = IrohConnectionStatus.STARTING,
            roomId = invite.roomId,
            roomName = invite.roomName,
            transitioning = true,
        ))
        return JoinPreparation(invite.roomId, createdRoom, routeBeforeJoin)
    }

    private suspend fun connectJoinedRoom(invite: IrohRoomInvite, preparation: JoinPreparation) {
        val local = checkNotNull(persistence.localState())
        val owner = lifecycle.snapshot().generation
        startService(
            IrohServiceContext(invite.roomId, invite.roomSecret.copyOf(), local.deviceId, null),
            startPeriodicSync = false,
            owner = owner,
        )
        service.join(invite)
        preparation.activated = true
        startService(
            IrohServiceContext(invite.roomId, invite.roomSecret.copyOf(), local.deviceId, null),
            owner = owner,
        )
    }

    private suspend fun completeJoinedRoom(roomId: String) {
        invite = null
        publish(persistence.snapshot(roomId).copy(
            mode = ReplicationMode.IROH,
            status = service.state.value.status,
            transitioning = false,
        ))
    }

    // R54-A12: once the join has stopped the previous endpoint and prepared its room, any running
    // endpoint is the join's own, so a cancelled join stops it. Otherwise it stays bound behind the
    // route the cancellation restores. The prepared room is left to the startup discard, as before.
    // The stop is non-cancellable because the join's job is the cancelled one.
    // R54-A14: it then publishes the route the join knows is persisted, so the cancellation's
    // route read falls back to that route instead of the failed room.
    private suspend fun stopCancelledJoin(preparation: JoinPreparation) {
        withContext(NonCancellable) {
            try {
                service.stop()
            } catch (error: Exception) {
                // The caller rethrows its own cancellation next, so a failed stop is reported here.
                CrashReporter.report(error)
            }
        }
        publishKnownJoinRoute(preparation)
    }

    // R54-A14: a failed or cancelled join publishes the route it knows is persisted before it reads
    // that route, so the fallback for an unreadable route is that route instead of the failed
    // room. Once activated, that is the joined room on the IROH route; before, it is the route
    // the join replaced. A pending identity recovery keeps its prompt.
    private fun publishKnownJoinRoute(preparation: JoinPreparation) {
        val current = service.state.value
        if (current.identityRecovery != null) return
        val route =
            if (preparation.activated) current.copy(mode = ReplicationMode.IROH) else preparation.routeBeforeJoin
        publish(route.copy(transitioning = true))
    }

    private suspend fun rollbackJoinedRoom(preparation: JoinPreparation?) {
        service.stop()
        if (preparation?.createdRoom == true) {
            // A31: best-effort cleanup, stays silent. The join failure that
            // triggered rollback is reported via recoverPersistedRoute below;
            // a discard failure carries no new signal and must not mask it.
            // Cancellation still propagates: a cancelled discard must not
            // read as a successful rollback.
            runCatching { persistence.discardIncompleteInactiveRoom(preparation.roomId) }
                .onFailure { error ->
                    if (error is CancellationException) throw error
                }
        }
    }

    private suspend fun startIfNeeded(): String {
        val room = checkNotNull(persistence.activeRoom()) { "No Iroh room is active" }
        check(persistence.snapshot(room.roomId).conflict == null) { "Iroh room requires repair" }
        val secret = checkNotNull(persistence.activeRoomSecret()) {
            "Iroh room secret is unavailable"
        }
        return try {
            val local = checkNotNull(persistence.localState())
            startService(
                IrohServiceContext(room.roomId, secret.copyOf(), local.deviceId, null),
                owner = lifecycle.snapshot().generation,
            )
        } finally {
            secret.fill(0)
        }
    }

    private suspend fun startService(
        context: IrohServiceContext,
        startPeriodicSync: Boolean = true,
        owner: Long,
    ): String {
        if (!permitsEndpoint(owner)) {
            context.roomSecret.fill(0)
            throw CancellationException("Iroh endpoint requires active account foreground")
        }
        val ticket = service.start(context, startPeriodicSync)
        if (!permitsEndpoint(owner)) {
            // A31: quarantine raced start; stop is best-effort cleanup of a
            // discarded endpoint and stays silent. Cancellation is the
            // signal (not a crash); the quarantine itself is already
            // published via permitsEndpoint/accountQuarantined.
            service.stop()
            throw CancellationException("Iroh endpoint stopped by account or lifecycle quarantine")
        }
        return ticket
    }

    private suspend fun flushActiveIrohLocked() {
        if (service.state.value.mode != ReplicationMode.IROH ||
            service.state.value.conflict != null
        ) return
        val projection = persistence.captureLocalOperations()
        publish(service.state.value.copy(operationCount = projection.operationCount))
    }

    // R54-A13: the state carries the action's bounded message, never exception text.
    private suspend fun recoverPersistedRoute(error: Exception, message: String) {
        if (error is IrohSecretVaultException) {
            // expected-silent: vault recovery is reported through the recovery UI, not a crash.
            quarantineRecovery(error.recoveryKind)
            return
        }
        // R54-A11: an unreadable route still ends the action on the current state, and nothing
        // restarts: the next foreground or Sync now binds again.
        val route = persistedRouteOrNull()
        publish((route ?: service.state.value).copy(
            status = IrohConnectionStatus.UNAVAILABLE,
            message = message,
            transitioning = false,
        ))
        if (route != null && route.mode == ReplicationMode.IROH && route.conflict == null &&
            isForeground()
        ) {
            if (!startUnlessIdentityRecoveryRequired()) return
            publish(service.state.value.copy(
                message = message,
                transitioning = false,
            ))
        }
    }

    // The action's own failure is already reported, so a failed route read is reported here as a
    // second failure. A cancelled read still cancels the action.
    private suspend fun persistedRouteOrNull(): IrohNetworkState? = try {
        persistedRoute()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        CrashReporter.report(error)
        null
    }

    private suspend fun persistedRoute(): IrohNetworkState {
        val settings = persistence.replicationSettings()
        val mode = runCatching { ReplicationMode.valueOf(settings.mode) }
            .getOrDefault(ReplicationMode.OFFLINE)
        val snapshot = settings.activeRoomId?.let { roomId -> persistence.snapshot(roomId) }
        return (snapshot ?: IrohNetworkState(mode = mode)).copy(mode = mode)
    }

    // R54-A06: creating, joining or leaving a room, changing the route, and identity recovery
    // publish `transitioning`, which disables the network actions and blocks timer mutations.
    // Outside the Iroh route no later publish clears it, so a cancelled exit, including one from
    // the failure handling, clears it here before the cancellation propagates. An action that
    // never set the flag keeps its state. A pending identity recovery keeps its prompt; any
    // other state becomes the persisted route without an error message. Nothing restarts: the
    // next foreground or Sync now binds again. The reads are non-cancellable because the
    // caller's job is usually the cancelled one.
    private suspend fun clearCancelledTransition() {
        if (!service.state.value.transitioning) return
        withContext(NonCancellable) {
            val current = service.state.value
            val settled =
                if (current.identityRecovery == null) persistedRouteOrCurrent() else current
            publish(settled.copy(transitioning = false))
        }
    }

    private suspend fun persistedRouteOrCurrent(): IrohNetworkState = try {
        persistedRoute()
    } catch (error: Exception) {
        // The caller rethrows its own cancellation next, so a failed route read is reported here
        // (CrashReporter skips a cancelled read) and only the flag changes on the current state.
        CrashReporter.report(error)
        service.state.value
    }

    private suspend fun startUnlessIdentityRecoveryRequired(): Boolean = try {
        startIfNeeded()
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: IrohSecretVaultException) {
        // expected-silent: vault recovery is reported through the recovery UI, not a crash.
        quarantineRecovery(error.recoveryKind)
        false
    } catch (error: Exception) {
        // Preconditions and a closed lifecycle are reported only here. A bind or ticket failure
        // arrives as the instance the lifecycle already reported, which Sentry deduplicates.
        CrashReporter.report(error)
        // R54-A05: the state carries the Sync now notice, never exception text.
        publishSyncUnavailable()
        true
    }

    private fun permitsEndpoint(owner: Long): Boolean =
        !accountQuarantined.get() &&
            !recoveryRequested.get() &&
            service.state.value.identityRecovery == null &&
            lifecycle.permitsEndpoint(owner)

    private fun isForeground(): Boolean =
        !accountQuarantined.get() && lifecycle.snapshot().foreground

    private fun publish(value: IrohNetworkState) {
        if (!closed.get()) service.publishState(value)
    }

    private fun attachCachedInvite() {
        if (closed.get()) return
        service.transformState { current ->
            val cached = invite
            if (cached != null && current.invite == null) current.copy(invite = cached) else current
        }
    }

    private fun publishSyncUnavailable() {
        publish(service.state.value.copy(
            status = IrohConnectionStatus.UNAVAILABLE,
            message = "Iroh sync could not be completed. Try again.",
            transitioning = false,
        ))
    }

    private suspend fun runIrohAction(action: suspend () -> Unit) {
        val generation = recoveryGeneration.get()
        if (!permitsIrohAction(generation)) return
        mutex.withLock {
            if (!permitsIrohAction(generation)) return@withLock
            if (!initializeForAction()) return@withLock
            if (!permitsIrohAction(generation)) return@withLock
            try {
                action()
            } catch (error: CancellationException) {
                clearCancelledTransition()
                throw error
            }
        }
    }

    // R54-A07: an action that cannot read the route does not run. The read is already reported,
    // and initialization stays open for the next action or initialize().
    private suspend fun initializeForAction(): Boolean = try {
        initializeLocked()
    } catch (_: IrohRouteReadException) {
        // expected-silent: readStartupRoute sent the bounded route-read report.
        false
    }

    private fun permitsIrohAction(generation: Long): Boolean =
        !closed.get() &&
            !accountQuarantined.get() &&
            !recoveryRequested.get() &&
            recoveryGeneration.get() == generation &&
            service.state.value.identityRecovery == null

    private suspend fun recoverIdentityLocked(): Boolean {
        val kind = service.state.value.identityRecovery ?: return false
        publishRecovery(kind, failed = false, transitioning = true)
        return try {
            when (kind) {
                IrohIdentityRecoveryKind.ENDPOINT_CORRUPTED -> {
                    repairEndpointIdentity()
                    service.state.value.mode == ReplicationMode.IROH
                }
                IrohIdentityRecoveryKind.KEY_INVALIDATED_OR_MISSING -> {
                    resetIrohIdentity()
                    false
                }
            }
        } catch (error: CancellationException) {
            clearCancelledTransition()
            throw error
        } catch (error: IrohSecretVaultException) {
            // expected-silent: vault recovery is reported through the recovery UI, not a crash.
            quarantineRecovery(error.recoveryKind, failed = true)
            false
        } catch (error: Exception) {
            CrashReporter.report(error)
            publishRecovery(kind, failed = true)
            false
        }
    }

    private suspend fun repairEndpointIdentity() {
        service.stop()
        persistence.validateRoomSecrets()
        service.replaceEndpointIdentity()
        invite = null
        publish(service.state.value.copy(
            status = IrohConnectionStatus.STOPPED,
            invite = null,
            message = null,
            transitioning = false,
            identityRecovery = null,
            recoveryAttemptFailed = false,
        ))
    }

    private suspend fun resetIrohIdentity() {
        service.stop()
        service.beginIdentityReset()
        persistence.resetIdentityData()
        service.completeIdentityReset()
        invite = null
        publish(IrohNetworkState(mode = ReplicationMode.OFFLINE))
    }

    private suspend fun quarantineRecovery(
        kind: IrohIdentityRecoveryKind,
        failed: Boolean = false,
    ) {
        // A31: stop is quarantine teardown, not a new failure. Vault
        // recovery surfaces via the recovery UI (identityRecovery state);
        // a stop failure here propagates to the caller's existing
        // CrashReporter path instead of reporting a second event.
        try {
            service.stop()
        } finally {
            invite = null
            publishRecovery(kind, failed)
        }
    }

    private fun publishRecovery(
        kind: IrohIdentityRecoveryKind,
        failed: Boolean,
        transitioning: Boolean = false,
    ) {
        publish(service.state.value.copy(
            status = IrohConnectionStatus.UNAVAILABLE,
            invite = null,
            endpointMark = null,
            message = null,
            transitioning = transitioning,
            identityRecovery = kind,
            recoveryAttemptFailed = failed,
        ))
    }
}

private suspend fun unsupportedRecoveryAction(): Nothing =
    throw UnsupportedOperationException("Iroh identity recovery is unavailable")
