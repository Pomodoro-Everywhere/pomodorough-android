package me.egigoka.pomodorough.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.api.BootstrapConflictKind
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.PendingAutoStartOperationEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.domain.SettingsReducer
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.TimerReducer
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import me.egigoka.pomodorough.timer.SystemTimerCompletionNotifier
import me.egigoka.pomodorough.timer.shouldStopCompletionAlert
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

enum class AuthStatus { Loading, SignedOut, SigningIn, SignedIn }

enum class SyncStatus { Checking, Synced, Queued, Syncing, Retrying, Offline, Conflict }

data class AppState(
    val ready: Boolean = false,
    val authStatus: AuthStatus = AuthStatus.Loading,
    val user: User? = null,
    val timer: CanonicalTimer? = null,
    val history: List<HistoryItem> = emptyList(),
    val tasks: List<FocusTask> = emptyList(),
    val knownTasks: List<FocusTask> = emptyList(),
    val taskSummaries: List<TaskDailySummary> = emptyList(),
    val selectedTaskId: String? = null,
    val settings: TimerSettings = TimerSettings(),
    val pendingCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.Checking,
    val historyResolution: HistoryResolutionState? = null,
    val accountSwitch: AccountSwitchState? = null,
    val conflict: String? = null,
    val notice: String? = null,
    val deviceId: String = "",
    val network: IrohNetworkState = IrohNetworkState(),
)

private data class SyncAttempt(
    val accountGeneration: Long,
    val request: SyncRequest,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
    val selectedPhaseAtSend: String,
    val selectedPhaseGenerationAtSend: Long,
)

private data class BootstrapResolutionAttempt(
    val accountGeneration: Long,
    val request: BootstrapResolutionRequest,
    val sentPhysicalMs: Long,
    val sentElapsedRealtimeMs: Long,
)

private data class ServerClockSample(
    val offsetMs: Long,
    val uncertaintyMs: Long,
    val serverTimeMs: Long,
    val midpointPhysicalMs: Long,
    val midpointElapsedRealtimeMs: Long,
)

private data class RequestTiming(
    val uncertaintyMs: Long,
    val midpointPhysicalMs: Long,
    val midpointElapsedRealtimeMs: Long,
)

private data class RebasedMutationState(
    val local: LocalStateEntity,
    val commands: List<TimerCommand>,
    val taskOperations: List<TaskOperation>,
    val durationOperations: List<DurationOperation>,
    val autoStartOperations: List<AutoStartOperation>,
)

private data class ClockedMutation(
    val key: String,
    val wallMs: Long,
    val counter: Long,
    val id: String,
)

private data class MutationReservation(
    val stamps: List<SyncWireBounds.MutationStamp>,
    val uuids: List<UUID>,
    val lastUuidV7: String,
)

private data class DecodedLocalJson(
    val settings: TimerSettings,
    val canonicalTimer: CanonicalTimer?,
    val history: List<HistoryItem>,
    val tasks: List<FocusTask>,
    val knownTasks: List<FocusTask>,
    val user: User?,
)

private data class GeneratedCommandResolution(
    val released: List<TimerCommand>,
    val discarded: List<TimerCommand>,
    val discardedSourceTimerIds: Set<String>,
)

private data class PendingAccountSwitch(
    val profile: User,
    val bootstrap: SyncResponse,
    val clockSample: ServerClockSample,
)

private data class RepositoryAttemptIdentity(
    val accountGeneration: Long,
    val requestId: String?,
)

private class SyncProtocolException(message: String) : Exception(message)
private class ProfileProtocolException(message: String) : Exception(message)

class TimerRepository(
    context: Context,
    private val dao: TimerDao,
    private val api: PomodoroughService,
    private val auth: AuthSession,
    private val json: Json,
    private val networkAvailable: (() -> Boolean)? = null,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
    private val bootId: () -> String? = ::readBootId,
    private val uuidEntropy: () -> ByteArray = UuidV7::secureEntropy,
    private val initialSyncRetryDelayMs: Long = 1_000L,
    private val remoteSyncIntervalMs: Long = 15_000L,
    private val replication: IrohReplicationController? = null,
    workspaceCoordinator: LocalWorkspaceCoordinator =
        (replication as? me.egigoka.pomodorough.data.iroh.IrohReplicationRepository)
            ?.workspaceCoordinator ?: LocalWorkspaceCoordinator(),
) : TimerRepositoryContract {
    private val appContext = context.applicationContext
    private val strictJson = Json(from = json) { ignoreUnknownKeys = false }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initializeMutex = Mutex()
    private val actionMutex = workspaceCoordinator
    private val streamMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val networkInitializationStarted = AtomicBoolean(false)
    private val signInInFlight = AtomicBoolean(false)
    private val syncSignals = Channel<Unit>(Channel.CONFLATED)
    private val streamLifecycleSignals = Channel<Boolean>(Channel.UNLIMITED)
    private val forceSync = AtomicBoolean(false)
    private val alarmScheduler = TimerAlarmScheduler(appContext)
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var local: LocalStateEntity
    private var pending = emptyList<TimerCommand>()
    private var commandDependencies = emptyMap<String, String>()
    private var pendingDurationOperations = emptyList<DurationOperation>()
    private var pendingTaskOperations = emptyList<TaskOperation>()
    private var pendingAutoStartOperations = emptyList<AutoStartOperation>()
    private var pendingBootstrapResolution: PendingBootstrapResolutionEntity? = null
    private var canonicalTimer: CanonicalTimer? = null
    private var canonicalHistory = emptyList<HistoryItem>()
    private var canonicalTasks = emptyList<FocusTask>()
    private var canonicalAutoStartBreaks = false
    private var knownTasks = emptyMap<String, FocusTask>()
    private var tasks = emptyList<FocusTask>()
    private var projection = TimerProjection(null, emptyList())
    private var settings = TimerSettings()
    private var user: User? = null
    private var authStatus = AuthStatus.Loading
    private var syncing = false
    private var retrying = false
    private var terminalSyncError: String? = null
    private var conflict: String? = null
    private var notice: String? = null
    private var online = currentOnlineState()
    @Volatile private var foreground = false
    @Volatile private var streamLifecycleGeneration = 0L
    private var eventSource: EventSource? = null
    private var accountGeneration = 0L
    private var bootstrapSnapshot: SyncResponse? = null
    private var bootstrapClockSample: ServerClockSample? = null
    private var historyResolution: HistoryResolutionState? = null
    private var pendingAccountSwitch: PendingAccountSwitch? = null
    private var accountSwitch: AccountSwitchState? = null
    private var localMutationCorrupted = false
    private var mutationFailure: String? = null
    private var trustedAnchorServerMs: Long? = null
    private var trustedAnchorElapsedRealtimeMs: Long? = null
    private var selectedPhaseGeneration = 0L
    private var networkState = IrohNetworkState()

    private val _state = MutableStateFlow(AppState())
    override val state: StateFlow<AppState> = _state.asStateFlow()

    init {
        require(remoteSyncIntervalMs > 0) { "Remote sync interval must be positive" }
        scope.launch { syncLoop() }
        scope.launch {
            while (isActive) {
                delay(remoteSyncIntervalMs)
                if (foreground && online && authStatus == AuthStatus.SignedIn &&
                    replicationMode() == ReplicationMode.CENTRALIZED &&
                    historyResolution == null && accountSwitch == null
                ) {
                    requestSync(force = true)
                }
            }
        }
        replication?.let { controller ->
            scope.launch {
                controller.state.collectLatest { next ->
                    networkState = next
                    publish()
                }
            }
        }
        scope.launch {
            for (shouldOpen in streamLifecycleSignals) {
                try {
                    if (shouldOpen) openRevisionStream() else closeRevisionStream()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (shouldOpen) {
                        val reconnectGeneration = streamLifecycleGeneration
                        scope.launch {
                            delay(5_000)
                            if (foreground && streamLifecycleGeneration == reconnectGeneration) {
                                streamLifecycleSignals.trySend(true)
                            }
                        }
                    }
                }
            }
        }
        if (networkAvailable == null) {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = updateNetworkState()
                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) =
                    updateNetworkState()
                override fun onLost(network: Network) = updateNetworkState()
            })
        }
    }

    override suspend fun initialize() {
        ensureLocalInitialized()
        if (localMutationCorrupted) return
        replication?.initialize()
        if (replicationMode() != ReplicationMode.CENTRALIZED) {
            reloadWorkspace(replicationMode())
            if (authStatus == AuthStatus.Loading) {
                authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
                publish()
            }
            return
        }
        if (!networkInitializationStarted.compareAndSet(false, true)) return
        if (auth.hasTokens()) {
            restoreProfile()
            if (authStatus == AuthStatus.SignedIn && historyResolution == null && accountSwitch == null) {
                requestSync(force = true)
                if (foreground) streamLifecycleSignals.trySend(true)
            }
        }
    }

    private suspend fun ensureLocalInitialized() {
        if (initialized.isCompleted) return
        initializeMutex.withLock {
            if (initialized.isCompleted) return
            val stored = dao.localState()
            local = stored ?: LocalStateEntity(
                deviceId = UUID.randomUUID().toString(),
                settingsJson = json.encodeToString(TimerSettings()),
            ).also { dao.insertState(it) }
            val pendingCommandEntities = dao.pendingCommands()
            pending = pendingCommandEntities.map(PendingCommandEntity::toModel)
            commandDependencies = pendingCommandEntities.mapNotNull { entity ->
                entity.generatedByFinishCommandId?.let { entity.id to it }
            }.toMap()
            val pendingById = pending.associateBy(TimerCommand::id)
            pendingDurationOperations = dao.pendingDurationOperations()
                .map(PendingDurationOperationEntity::toModel)
            pendingTaskOperations = dao.pendingTaskOperations().map(PendingTaskOperationEntity::toModel)
            pendingAutoStartOperations = dao.pendingAutoStartOperations()
                .map(PendingAutoStartOperationEntity::toModel)
            pendingBootstrapResolution = dao.pendingBootstrapResolution()
            val decodedLocal = try {
                val decodedUser = local.userJson?.let { raw ->
                    requireNotNull(strictJson.decodeFromString<User?>(raw)) {
                        "Persisted account JSON is null"
                    }.also(::validateUser)
                }
                DecodedLocalJson(
                    settings = json.decodeFromString(local.settingsJson),
                    canonicalTimer = local.canonicalTimerJson?.let(json::decodeFromString),
                    history = json.decodeFromString(local.historyJson),
                    tasks = json.decodeFromString(local.tasksJson),
                    knownTasks = json.decodeFromString(local.knownTasksJson),
                    user = decodedUser,
                )
            } catch (_: Exception) {
                localMutationCorrupted = true
                terminalSyncError = LocalStateCorruptedError
                conflict = LocalStateCorruptedError
                authStatus = AuthStatus.SignedOut
                historyResolution = HistoryResolutionState(
                    localHistoryCount = 0,
                    remoteHistoryCount = 0,
                    corrupted = true,
                    error = LocalStateCorruptedError,
                )
                alarmScheduler.cancel()
                initialized.complete(Unit)
                publish()
                return@withLock
            }
            val legacyRepairError = runCatching { repairLegacyMutationQueues() }.exceptionOrNull()
            if (legacyRepairError != null) {
                localMutationCorrupted = true
                terminalSyncError = LocalClockRangeError
                conflict = LocalClockRangeError
                authStatus = AuthStatus.SignedOut
                initialized.complete(Unit)
                publish()
                return@withLock
            }
            val rangeError = runCatching { validatePersistedMutationRanges() }.exceptionOrNull()
            if (rangeError != null) {
                localMutationCorrupted = true
                terminalSyncError = LocalClockRangeError
                conflict = LocalClockRangeError
                authStatus = AuthStatus.SignedOut
                initialized.complete(Unit)
                publish()
                return@withLock
            }
            invalidateStaleElapsedAnchor()
            val invalidDependentEntities = pendingCommandEntities.filter { entity ->
                val sourceId = entity.generatedByFinishCommandId ?: return@filter false
                val source = pendingById[sourceId]
                source?.type != CommandType.Finish || entity.deviceSequence <= source.deviceSequence
            }
            if (invalidDependentEntities.isNotEmpty()) {
                dao.deleteCommands(invalidDependentEntities)
                val invalidIds = invalidDependentEntities.map(PendingCommandEntity::id).toSet()
                pending = pending.filterNot { it.id in invalidIds }
                commandDependencies = commandDependencies - invalidIds
            }
            val highestSequence = pending.maxOfOrNull(TimerCommand::deviceSequence) ?: 0L
            if (highestSequence > local.deviceSequence) {
                local = local.copy(deviceSequence = highestSequence)
                dao.updateState(local)
            }
            settings = decodedLocal.settings
            settings = replayDurationOperations(settings, pendingDurationOperations)
            canonicalAutoStartBreaks = local.canonicalAutoStartBreaks
            settings = settings.copy(
                autoStartBreaks = replayAutoStartOperations(
                    canonicalAutoStartBreaks,
                    pendingAutoStartOperations,
                ),
            )
            canonicalTimer = decodedLocal.canonicalTimer
            canonicalHistory = decodedLocal.history
            canonicalTasks = decodedLocal.tasks
            knownTasks = decodedLocal.knownTasks
                .plus(canonicalTasks)
                .associateBy(FocusTask::id)
            user = decodedLocal.user
            if (local.ownerUserId == null && user != null) {
                local = local.copy(ownerUserId = user?.id)
                dao.updateState(local)
            }
            rebuildProjections()
            if (pendingBootstrapResolution != null) {
                restorePendingResolutionForSignedOut(
                    "Sign in to finish the saved history choice before making more changes.",
                )
            }
            if (local.ownedTimerId != null &&
                projection.timer?.takeIf { it.status in activeStatuses }?.id != local.ownedTimerId
            ) {
                local = local.copy(ownedTimerId = null)
                dao.updateState(local)
            }
            authStatus = if (auth.hasTokens()) {
                AuthStatus.Loading
            } else {
                AuthStatus.SignedOut
            }
            if (authStatus == AuthStatus.SignedOut) {
                restorePendingResolutionForSignedOut(
                    "Sign in to finish the saved history choice before making more changes.",
                )
            }
            initialized.complete(Unit)
            publish()
            scheduleAlarm()
        }
    }

    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) {
        initialize()
        if (replicationMode() != ReplicationMode.CENTRALIZED) {
            replication?.setMode(ReplicationMode.CENTRALIZED)
            if (replicationMode() != ReplicationMode.CENTRALIZED) {
                notice = replication?.state?.value?.message
                    ?: "Could not restore centralized workspace for sign-in."
                publish()
                return
            }
            reloadWorkspace(ReplicationMode.CENTRALIZED)
        }
        if (localMutationCorrupted) return
        if (!signInInFlight.compareAndSet(false, true)) return
        var identity: RepositoryAttemptIdentity? = null
        try {
            actionMutex.withLock {
                authStatus = AuthStatus.SigningIn
                notice = null
                identity = currentAttemptIdentity()
                publish()
            }
            val attemptIdentity = checkNotNull(identity)
            auth.signIn(credentialProvider, local.deviceId)
            val profile = fetchValidatedProfile()
            val sentPhysicalMs = currentTimeMillis()
            val sentElapsedRealtimeMs = elapsedRealtimeMillis()
            val bootstrap = auth.authorized(api::bootstrap)
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            val clockSample = serverClockSample(
                bootstrap,
                sentPhysicalMs,
                sentElapsedRealtimeMs,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            )
            if (!completeAuthentication(profile, bootstrap, attemptIdentity, clockSample)) return
            if (historyResolution == null && accountSwitch == null) {
                requestSync(force = true)
                if (foreground) streamLifecycleSignals.trySend(true)
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                actionMutex.withLock {
                    if (authStatus != AuthStatus.SigningIn) return@withLock
                    runCatching(auth::clear)
                    authStatus = AuthStatus.SignedOut
                    user = null
                    notice = null
                    restorePendingResolutionForSignedOut(
                        "Sign in again to retry the exact saved history choice.",
                    )
                    publish()
                }
            }
            throw error
        } catch (_: AuthenticationRequired) {
            identity?.let {
                handleAuthenticationRequired(it, "Session expired during sign-in bootstrap.")
            }
        } catch (error: ProfileProtocolException) {
            actionMutex.withLock {
                val attemptIdentity = identity ?: return@withLock
                if (!isCurrent(attemptIdentity)) return@withLock
                auth.clear()
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                val attemptIdentity = identity ?: return@withLock
                if (!isCurrent(attemptIdentity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Google sign-in did not complete"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } finally {
            signInInFlight.set(false)
        }
    }

    override suspend fun logout() {
        initialize()
        if (replicationMode() == ReplicationMode.IROH) {
            runCatching { auth.logout() }
            auth.clear()
            val clearFailure = runCatching {
                replication?.clearAccountData()
                reloadWorkspace(ReplicationMode.IROH)
            }.exceptionOrNull()
            actionMutex.withLock {
                accountGeneration += 1
                authStatus = AuthStatus.SignedOut
                user = null
                notice = clearFailure?.message
                    ?: clearFailure?.let { "Account signed out, but saved account data could not be cleared." }
                publish()
            }
            return
        }
        if (localMutationCorrupted) return
        actionMutex.withLock {
            accountGeneration += 1
            syncing = false
            retrying = false
            publish()
        }
        try {
            auth.logout()
            closeRevisionStream()
            actionMutex.withLock {
                if (local.ownerUserId == null) {
                    user = null
                    authStatus = AuthStatus.SignedOut
                    pendingAccountSwitch = null
                    accountSwitch = null
                    bootstrapSnapshot = null
                    bootstrapClockSample = null
                    conflict = null
                    terminalSyncError = null
                    restorePendingResolutionForSignedOut(
                        "Sign in again to retry the exact saved history choice.",
                    )
                    publish()
                    return@withLock
                }
                val clearedSettings = settings.withDurations(DurationsMs()).copy(autoStartBreaks = false)
                val nextLocal = local.copy(
                    revision = 0,
                    canonicalTimerJson = null,
                    historyJson = "[]",
                    tasksJson = "[]",
                    knownTasksJson = "[]",
                    selectedTaskId = null,
                    settingsJson = json.encodeToString(clearedSettings),
                    userJson = null,
                    ownerUserId = null,
                    canonicalAutoStartBreaks = false,
                    ownedTimerId = null,
                    serverClockOffsetMs = null,
                    serverClockUncertaintyMs = null,
                    serverClockSamplePhysicalMs = null,
                    serverClockSampleElapsedRealtimeMs = null,
                    serverClockBootId = null,
                )
                dao.clearAccount(nextLocal)
                trustedAnchorServerMs = null
                trustedAnchorElapsedRealtimeMs = null
                local = nextLocal
                canonicalTimer = null
                canonicalHistory = emptyList()
                canonicalTasks = emptyList()
                knownTasks = emptyMap()
                tasks = emptyList()
                projection = TimerProjection(null, emptyList())
                pending = emptyList()
                commandDependencies = emptyMap()
                pendingDurationOperations = emptyList()
                pendingTaskOperations = emptyList()
                pendingAutoStartOperations = emptyList()
                settings = clearedSettings
                canonicalAutoStartBreaks = false
                user = null
                pendingBootstrapResolution = null
                historyResolution = null
                pendingAccountSwitch = null
                accountSwitch = null
                bootstrapSnapshot = null
                bootstrapClockSample = null
                conflict = null
                terminalSyncError = null
                authStatus = AuthStatus.SignedOut
                alarmScheduler.cancel()
                publish()
            }
        } catch (error: Exception) {
            notice = error.message ?: "Sign out failed. Local timer data was kept."
            publish()
        }
    }

    override suspend fun confirmAccountSwitch() {
        initialize()
        if (localMutationCorrupted) return
        val candidate = actionMutex.withLock {
            val value = pendingAccountSwitch ?: return@withLock null
            val state = accountSwitch ?: return@withLock null
            if (state.submitting || authStatus != AuthStatus.SignedIn) return@withLock null
            accountSwitch = state.copy(submitting = true, error = null)
            publish()
            value
        } ?: return

        try {
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate || authStatus != AuthStatus.SignedIn) {
                    return@withLock
                }
                validateUser(candidate.profile)
                validateCanonicalResponse(
                    candidate.bootstrap,
                    "Bootstrap",
                    requireEmptyAcknowledgements = true,
                )
                accountGeneration += 1
                user = candidate.profile
                bootstrapSnapshot = candidate.bootstrap
                installBootstrap(
                    candidate.profile,
                    candidate.bootstrap,
                    clearLocal = true,
                    clockSample = candidate.clockSample,
                )
                pendingAccountSwitch = null
                accountSwitch = null
                authStatus = AuthStatus.SignedIn
                publish()
                scheduleAlarm()
            }
            if (foreground) streamLifecycleSignals.trySend(true)
        } catch (error: Exception) {
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate) return@withLock
                accountSwitch = accountSwitch?.copy(
                    submitting = false,
                    error = error.message ?: "Could not switch accounts without risking local data.",
                )
                publish()
            }
        }
    }

    override suspend fun cancelAccountSwitch() {
        initialize()
        if (localMutationCorrupted) return
        val candidate = actionMutex.withLock {
            val value = pendingAccountSwitch ?: return@withLock null
            val state = accountSwitch ?: return@withLock null
            if (state.submitting) return@withLock null
            accountGeneration += 1
            accountSwitch = state.copy(submitting = true, error = null)
            publish()
            value
        } ?: return

        val logoutError = runCatching { auth.logout() }.exceptionOrNull()
        auth.clear()
        actionMutex.withLock {
            if (pendingAccountSwitch !== candidate) return@withLock
            pendingAccountSwitch = null
            accountSwitch = null
            bootstrapSnapshot = null
            bootstrapClockSample = null
            authStatus = AuthStatus.SignedOut
            user = null
            syncing = false
            retrying = false
            restorePendingResolutionForSignedOut(
                "Sign in with the account that owns this local data to continue syncing.",
            )
            notice = logoutError?.let {
                "New account was removed from this device, but server logout failed: ${it.message.orEmpty()}"
            }
            publish()
        }
        closeRevisionStream()
    }

    override suspend fun toggleTimer() {
        initialize()
        when (projection.timer?.status) {
            TimerStatus.Running -> issueCommand(CommandType.Pause)
            TimerStatus.Paused -> issueCommand(CommandType.Resume)
            else -> issueCommand(CommandType.Start)
        }
    }

    override suspend fun finishTimer() {
        initialize()
        finishLocalTimer(onlyIfExpired = false, allowWhileLoading = false)
    }

    suspend fun cancelTimer() {
        initialize()
        issueCommand(CommandType.Cancel)
    }

    override suspend fun cancelAndClearTimer() {
        initialize()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked()) return@withLock
            val current = projection.timer ?: return@withLock
            val types = when {
                validTransition(CommandType.Cancel, current) ->
                    listOf(CommandType.Cancel, CommandType.Clear)
                validTransition(CommandType.Clear, current) -> listOf(CommandType.Clear)
                else -> return@withLock
            }
            val reservation = reserveMutation(count = types.size, withDeviceSequences = true)
                ?: return@withLock
            val physicalNowMs = currentTimeMillis()
            val elapsedMs = TimerReducer.elapsedAt(current, physicalNowMs)
            val commands = types.mapIndexed { index, type ->
                val stamp = reservation.stamps[index]
                TimerCommand(
                    id = reservation.uuids[index].toString(),
                    deviceSequence = requireNotNull(stamp.deviceSequence),
                    timerId = current.id,
                    type = type,
                    phase = current.phase,
                    plannedDurationMs = current.plannedDurationMs,
                    occurredAt = stamp.occurredAt,
                    hlcWallMs = stamp.wallMs,
                    hlcCounter = stamp.counter,
                    observedElapsedMs = elapsedMs,
                    taskId = null,
                    physicalOccurredAt = Instant.ofEpochMilli(physicalNowMs).toString(),
                )
            }
            val dependency = dependencyForTimer(current.id)
            val dependencies = dependency?.let { source ->
                commands.associate { it.id to source }
            }.orEmpty()
            val nextProjection = TimerReducer.replay(projection.timer, projection.history, commands)
            val completionApplied = nextProjection.history.any {
                it.timerId == current.id && it.status == TimerStatus.Completed
            }
            val nextPhase = if (completionApplied) {
                if (current.phase == TimerPhase.Focus) {
                    nextBreakPhase(
                        nextProjection.history
                            .filter {
                                it.status == TimerStatus.Completed && it.phase == TimerPhase.Focus
                            }
                            .map(HistoryItem::timerId)
                            .toSet()
                            .size,
                    )
                } else {
                    TimerPhase.Focus
                }
            } else {
                settings.selectedPhase
            }
            val nextSettings = settings.copy(selectedPhase = nextPhase)
            val finalStamp = reservation.stamps.last()
            val nextLocal = local.copy(
                deviceSequence = requireNotNull(finalStamp.deviceSequence),
                hlcWallMs = finalStamp.wallMs,
                hlcCounter = finalStamp.counter,
                settingsJson = json.encodeToString(nextSettings),
                lastUuidV7 = reservation.lastUuidV7,
            )
            dao.persistCommands(
                commands.map { PendingCommandEntity.from(it, dependencies[it.id]) },
                nextLocal,
            )
            local = nextLocal
            if (nextPhase != settings.selectedPhase) selectedPhaseGeneration += 1L
            settings = nextSettings
            pending = pending + commands
            commandDependencies = commandDependencies + dependencies
            rebuildProjections()
            publish()
            scheduleAlarm()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun clearTimer() {
        initialize()
        issueCommand(CommandType.Clear)
    }

    override suspend fun selectPhase(phase: String) {
        initialize()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || phase !in TimerPhase.all ||
                projection.timer?.status in activeStatuses
            ) return@withLock
            if (phase == settings.selectedPhase) return@withLock
            selectedPhaseGeneration += 1L
            settings = settings.copy(selectedPhase = phase)
            local = local.copy(settingsJson = json.encodeToString(settings))
            dao.updateState(local)
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun changeDuration(phase: String, delta: Int) {
        initialize()
        if (phase !in TimerPhase.all) return
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || projection.timer?.status in activeStatuses) return@withLock
            val currentDurationMs = settings.durationMsFor(phase)
            val nextDurationMs = (
                currentDurationMs / DurationLimits.MinuteMs + delta.toLong()
            ).coerceIn(1L, DurationLimits.MaxMs / DurationLimits.MinuteMs) *
                DurationLimits.MinuteMs
            if (nextDurationMs == currentDurationMs) return@withLock

            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val stamp = reservation.stamps.single()
            val operation = DurationOperation(
                id = "duration-operation-${reservation.uuids.single()}",
                phase = phase,
                durationMs = nextDurationMs,
                occurredAt = stamp.occurredAt,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
            )
            val nextSettings = settings.withDuration(phase, nextDurationMs)
            val nextLocal = local.copy(
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                settingsJson = json.encodeToString(nextSettings),
                lastUuidV7 = reservation.lastUuidV7,
            )
            dao.persistDurationOperation(PendingDurationOperationEntity.from(operation), nextLocal)
            local = nextLocal
            settings = nextSettings
            pendingDurationOperations = pendingDurationOperations
                .filterNot { it.phase == phase } + operation
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun setAutoStart(enabled: Boolean) {
        initialize()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || settings.autoStartBreaks == enabled) return@withLock
            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val stamp = reservation.stamps.single()
            val operation = AutoStartOperation(
                id = reservation.uuids.single().toString(),
                deviceId = local.deviceId,
                enabled = enabled,
                occurredAt = stamp.occurredAt,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
            )
            val nextSettings = settings.copy(autoStartBreaks = enabled)
            val nextLocal = local.copy(
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                settingsJson = json.encodeToString(nextSettings),
                lastUuidV7 = reservation.lastUuidV7,
            )
            dao.persistAutoStartOperation(
                PendingAutoStartOperationEntity.from(operation),
                nextLocal,
            )
            local = nextLocal
            settings = nextSettings
            pendingAutoStartOperations = pendingAutoStartOperations + operation
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun selectTask(taskId: String?) {
        initialize()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || projection.timer?.status in activeStatuses) return@withLock
            if (taskId != null && tasks.none { it.id == taskId }) return@withLock
            if (taskId == local.selectedTaskId) return@withLock
            local = local.copy(selectedTaskId = taskId)
            dao.updateState(local)
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun addTask(title: String) {
        initialize()
        val task = TaskReducer.taskFromTitle(title)
        if (task == null) {
            notice = "Task must contain printable text and fit within 512 bytes."
            publish()
            return
        }
        val existing = tasks.firstOrNull { it.id == task.id }
        if (existing != null) {
            selectTask(existing.id)
            return
        }
        issueTaskOperation(TaskOperationType.Upsert, task, select = true)
    }

    override suspend fun deleteTask(taskId: String) {
        initialize()
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        issueTaskOperation(TaskOperationType.Delete, task)
    }

    override suspend fun finishExpiredTimer(): Boolean {
        ensureLocalInitialized()
        val timer = projection.timer ?: return false
        replication?.initialize()
        if (replicationMode() != ReplicationMode.CENTRALIZED) {
            reloadWorkspace(replicationMode())
        }
        if (timer.status == TimerStatus.Running &&
            TimerReducer.elapsedAt(timer) >= timer.plannedDurationMs
        ) {
            if (replicationMode() == ReplicationMode.IROH) {
                return try {
                    replication?.afterLocalMutation()
                    reloadWorkspace(ReplicationMode.IROH)
                    val completed = projection.timer?.status == TimerStatus.Completed
                    if (completed && timer.phase == TimerPhase.Focus && settings.autoStartBreaks &&
                        timer.id == local.ownedTimerId
                    ) {
                        val nextPhase = nextBreakPhase(
                            projection.history.count {
                                it.status == TimerStatus.Completed && it.phase == TimerPhase.Focus
                            },
                        )
                        issueCommand(CommandType.Start, nextPhase)
                    }
                    completed
                } catch (error: Exception) {
                    conflict = error.message ?: "Iroh room projection could not be refreshed"
                    publish()
                    false
                }
            }
            return finishLocalTimer(onlyIfExpired = true, allowWhileLoading = true)
        }
        return false
    }

    suspend fun rescheduleAlarmFromLocal() {
        ensureLocalInitialized()
        scheduleAlarm()
    }

    override fun dismissConflict() {
        if (localMutationCorrupted) return
        val shouldRetry = terminalSyncError != null
        terminalSyncError = null
        conflict = null
        publish()
        if (shouldRetry) requestSync(force = true)
    }

    override fun dismissNotice() {
        notice = null
        publish()
    }

    override suspend fun setReplicationMode(mode: ReplicationMode) {
        initialize()
        val controller = replication ?: return
        accountGeneration += 1
        controller.setMode(mode)
        reloadWorkspace(mode)
        if (mode == ReplicationMode.CENTRALIZED && authStatus == AuthStatus.SignedIn) {
            requestSync(force = true)
            if (foreground) streamLifecycleSignals.trySend(true)
        } else {
            streamLifecycleSignals.trySend(false)
        }
    }

    override suspend fun createIrohRoom(name: String) {
        initialize()
        val controller = replication ?: return
        controller.createRoom(name)
        reloadWorkspace(ReplicationMode.IROH)
        streamLifecycleSignals.trySend(false)
    }

    override suspend fun joinIrohRoom(invite: String) {
        initialize()
        val controller = replication ?: return
        controller.joinRoom(invite)
        reloadWorkspace(controller.mode)
        if (controller.mode != ReplicationMode.CENTRALIZED) streamLifecycleSignals.trySend(false)
    }

    override suspend fun leaveIrohRoom() {
        initialize()
        val controller = replication ?: return
        controller.leaveRoom()
        reloadWorkspace(ReplicationMode.OFFLINE)
    }

    override suspend fun refreshIrohInvite() {
        replication?.refreshInvite()
    }

    override suspend fun syncIrohNow() {
        replication?.syncNow()
    }

    override suspend fun resolveHistory(strategy: BootstrapStrategy) {
        initialize()
        if (localMutationCorrupted) return
        val refreshBootstrap = actionMutex.withLock {
            pendingBootstrapResolution == null && (
                bootstrapSnapshot == null || bootstrapClockSample?.let(::bootstrapClockSampleIsStale) != false
                )
        }
        if (refreshBootstrap) {
            val refreshIdentity = actionMutex.withLock { currentAttemptIdentity() }
            val sentPhysicalMs = currentTimeMillis()
            val sentElapsedRealtimeMs = elapsedRealtimeMillis()
            val refreshed = try {
                auth.authorized(api::bootstrap)
            } catch (_: AuthenticationRequired) {
                handleAuthenticationRequired(
                    refreshIdentity,
                    "Session expired while refreshing remote history.",
                )
                return
            } catch (error: Exception) {
                actionMutex.withLock {
                    if (!isCurrent(refreshIdentity)) return@withLock
                    historyResolution = historyResolution?.copy(
                        submitting = false,
                        error = error.message ?: "Could not refresh remote history.",
                    )
                    publish()
                }
                return
            }
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            val refreshAccepted = actionMutex.withLock {
                if (!isCurrent(refreshIdentity)) return@withLock false
                validateCanonicalResponse(refreshed, "Bootstrap", requireEmptyAcknowledgements = true)
                val clockSample = serverClockSample(
                    refreshed,
                    sentPhysicalMs,
                    sentElapsedRealtimeMs,
                    receivedPhysicalMs,
                    receivedElapsedRealtimeMs,
                )
                bootstrapSnapshot = refreshed
                bootstrapClockSample = clockSample
                historyResolution = (historyResolution ?: HistoryResolutionState(0, 0)).copy(
                    localHistoryCount = visibleHistoryCount(projection.history),
                    remoteHistoryCount = visibleHistoryCount(refreshed.history),
                    error = null,
                )
                publish()
                true
            }
            if (!refreshAccepted) return
        }

        val attempt = actionMutex.withLock {
            if (authStatus != AuthStatus.SignedIn || historyResolution?.submitting == true) {
                return@withLock null
            }
            val profile = user ?: return@withLock null
            val stored = pendingBootstrapResolution
            if (stored == null) {
                val bootstrap = bootstrapSnapshot ?: return@withLock null
                val clockSample = bootstrapClockSample ?: return@withLock null
                return@withLock prepareBootstrapResolution(
                    profile,
                    strategy,
                    bootstrap,
                    clockSample,
                )
            }
            if (stored.ownerUserId != profile.id) {
                historyResolution = corruptedResolutionState()
                publish()
                return@withLock null
            }
            val request = try {
                stored.toRequestStrict()
            } catch (_: Exception) {
                historyResolution = corruptedResolutionState()
                publish()
                return@withLock null
            }
            if (request.strategy != strategy) {
                notice = "Retry the pending ${request.strategy.displayName()} choice before choosing another option."
                publish()
                return@withLock null
            }
            historyResolution = (historyResolution ?: HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(bootstrapSnapshot?.history.orEmpty()),
            )).copy(
                pendingStrategy = request.strategy,
                requestId = request.requestId,
                submitting = true,
                corrupted = false,
                error = null,
            )
            publish()
            newBootstrapResolutionAttempt(request)
        } ?: return

        performBootstrapResolution(attempt)
    }

    override suspend fun recoverCorruptedResolution() {
        initialize()
        if (localMutationCorrupted) return
        val recovery = actionMutex.withLock {
            val resolution = historyResolution ?: return@withLock null
            val profile = user ?: return@withLock null
            if (authStatus != AuthStatus.SignedIn ||
                resolution.recovery != ResolutionRecovery.Repreview ||
                resolution.submitting
            ) return@withLock null

            dao.deleteBootstrapResolution()
            pendingBootstrapResolution = null
            bootstrapSnapshot = null
            bootstrapClockSample = null
            accountGeneration += 1
            historyResolution = resolution.copy(
                submitting = true,
                error = "Refreshing account history without the corrupted saved request.",
            )
            publish()
            profile to currentAttemptIdentity()
        } ?: return

        val (profile, identity) = recovery
        try {
            val sentPhysicalMs = currentTimeMillis()
            val sentElapsedRealtimeMs = elapsedRealtimeMillis()
            val bootstrap = auth.authorized(api::bootstrap)
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            val clockSample = serverClockSample(
                bootstrap,
                sentPhysicalMs,
                sentElapsedRealtimeMs,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            )
            completeAuthentication(
                profile,
                bootstrap,
                identity,
                clockSample,
                repreviewResolution = true,
            )
        } catch (_: AuthenticationRequired) {
            var shouldCloseStream = false
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                auth.clear()
                accountGeneration += 1
                authStatus = AuthStatus.SignedOut
                user = null
                bootstrapSnapshot = null
                bootstrapClockSample = null
                syncing = false
                retrying = false
                historyResolution = HistoryResolutionState(
                    localHistoryCount = visibleHistoryCount(projection.history),
                    remoteHistoryCount = 0,
                    corrupted = true,
                    recovery = ResolutionRecovery.Repreview,
                    error = "Session expired. Sign in again to re-check account history.",
                )
                publish()
                shouldCloseStream = true
            }
            if (shouldCloseStream) closeRevisionStream()
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "Could not refresh account history.",
                )
                publish()
            }
        }
    }

    private suspend fun prepareBootstrapResolution(
        profile: User,
        strategy: BootstrapStrategy,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
    ): BootstrapResolutionAttempt? {
        val includeLocal = strategy != BootstrapStrategy.KeepRemote
        val (mergedWall, mergedCounter) = mergedClock(bootstrap, clockSample)
        val sampledLocal = local.copy(
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
            serverClockOffsetMs = clockSample.offsetMs,
            serverClockUncertaintyMs = clockSample.uncertaintyMs,
            serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
            serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
            serverClockBootId = bootId(),
        )
        val rebased = if (includeLocal) {
            rebaseMutationState(
                trustedNowMs(clockSample),
                sampledLocal,
                bootstrap.serverHlcWallMs to bootstrap.serverHlcCounter,
                true,
                pending,
                pendingTaskOperations,
                pendingDurationOperations,
                pendingAutoStartOperations,
            )
        } else {
            RebasedMutationState(
                sampledLocal,
                pending,
                pendingTaskOperations,
                pendingDurationOperations,
                pendingAutoStartOperations,
            )
        }
        val eligibleCommands = rebased.commands.filter { it.id !in commandDependencies }
        val request = BootstrapResolutionRequest(
            requestId = "bootstrap-${UUID.randomUUID()}",
            deviceId = local.deviceId,
            expectedRevision = bootstrap.revision,
            strategy = strategy,
            commands = eligibleCommands.takeIf { includeLocal }.orEmpty().map(::forTrustedWire),
            taskOperations = rebased.taskOperations.takeIf { includeLocal }.orEmpty().map(::forTrustedWire),
            durationOperations = rebased.durationOperations.takeIf { includeLocal }.orEmpty()
                .map(::forTrustedWire),
            autoStartOperations = rebased.autoStartOperations.takeIf { includeLocal }.orEmpty()
                .map(::forTrustedWire),
        )
        val validationError = runCatching { validateResolutionEnvelope(request) }.exceptionOrNull()
        if (validationError != null) {
            historyResolution = HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(bootstrap.history),
                corrupted = true,
                recovery = ResolutionRecovery.KeepRemote.takeIf { includeLocal },
                error = validationError.message ?: "Queued bootstrap resolution is invalid",
            )
            publish()
            return null
        }
        val resolution = request.toEntity(profile)
        dao.persistBootstrapPreparation(
            rebased.local,
            rebased.commands.map { command ->
                PendingCommandEntity.from(command, commandDependencies[command.id])
            },
            rebased.taskOperations.map(PendingTaskOperationEntity::from),
            rebased.durationOperations.map(PendingDurationOperationEntity::from),
            rebased.autoStartOperations.map(PendingAutoStartOperationEntity::from),
            resolution,
        )
        installTrustedAnchor(clockSample)
        local = rebased.local
        pending = rebased.commands
        pendingTaskOperations = rebased.taskOperations
        pendingDurationOperations = rebased.durationOperations
        pendingAutoStartOperations = rebased.autoStartOperations
        pendingBootstrapResolution = resolution
        rebuildProjections()
        historyResolution = HistoryResolutionState(
            localHistoryCount = visibleHistoryCount(projection.history),
            remoteHistoryCount = visibleHistoryCount(bootstrap.history),
            pendingStrategy = request.strategy,
            requestId = request.requestId,
            submitting = true,
        )
        return newBootstrapResolutionAttempt(request)
    }

    private suspend fun performBootstrapResolution(attempt: BootstrapResolutionAttempt) {
        val identity = RepositoryAttemptIdentity(
            attempt.accountGeneration,
            attempt.request.requestId,
        )
        try {
            val response = auth.authorized { api.resolveBootstrap(it, attempt.request) }
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            var applied = false
            var shouldSyncRetainedOperations = false
            actionMutex.withLock {
                if (!isCurrent(identity) || authStatus != AuthStatus.SignedIn) return@withLock
                validateCanonicalResponse(response, "Bootstrap resolution")
                validateServerClock(response)
                val canonicalResponse = bootstrapSnapshot
                    ?.takeIf { it.revision > response.revision }
                    ?.copy(
                        acknowledgements = response.acknowledgements,
                        taskAcknowledgements = response.taskAcknowledgements,
                        durationAcknowledgements = response.durationAcknowledgements,
                        autoStartAcknowledgements = response.autoStartAcknowledgements,
                    )
                    ?: response
                validateCanonicalResponse(canonicalResponse, "Bootstrap resolution canonical state")
                val clockSample = advancedBootstrapClockSample(
                    bootstrapClockSample
                        ?: throw SyncProtocolException("Bootstrap clock sample is unavailable"),
                    canonicalResponse,
                    attempt.sentPhysicalMs,
                    attempt.sentElapsedRealtimeMs,
                    receivedPhysicalMs,
                    receivedElapsedRealtimeMs,
                )
                if (canonicalResponse.revision < attempt.request.expectedRevision ||
                    canonicalResponse.revision < local.revision
                ) {
                    throw SyncProtocolException("Bootstrap resolution returned a regressed revision")
                }
                validateAcknowledgements(
                    attempt.request.commands.map(TimerCommand::id),
                    response.acknowledgements.map(Acknowledgement::commandId),
                    "command",
                )
                validateAcknowledgements(
                    attempt.request.taskOperations.map(TaskOperation::id),
                    response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
                    "task",
                )
                validateAcknowledgements(
                    attempt.request.durationOperations.map(DurationOperation::id),
                    response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
                    "duration",
                )
                validateAcknowledgements(
                    attempt.request.autoStartOperations.orEmpty().map(AutoStartOperation::id),
                    response.autoStartAcknowledgements.map(AutoStartAcknowledgement::operationId),
                    "auto-start",
                )
                applyBootstrapResolution(
                    attempt.request,
                    canonicalResponse,
                    response,
                    clockSample,
                )
                applied = true
                shouldSyncRetainedOperations = pendingAutoStartOperations.isNotEmpty() ||
                    eligiblePendingCommands().isNotEmpty()
            }
            if (applied) {
                if (shouldSyncRetainedOperations) requestSync(force = true)
                if (foreground) streamLifecycleSignals.trySend(true)
            }
        } catch (error: BootstrapConflictException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                dao.deleteBootstrapResolution()
                pendingBootstrapResolution = null
                bootstrapSnapshot = null
                bootstrapClockSample = null
                val detail = when (error.kind) {
                    BootstrapConflictKind.Revision -> "Remote history changed before this choice was applied. Choose again to refresh it."
                    BootstrapConflictKind.RequestId -> "Server rejected the saved request identity. Choose again with a new request."
                    BootstrapConflictKind.Unknown -> error.message ?: "History resolution conflicted. Choose again."
                }
                historyResolution = historyResolution?.copy(
                    pendingStrategy = null,
                    requestId = null,
                    submitting = false,
                    error = detail,
                )
                publish()
            }
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(
                identity,
                "Session expired. Sign in again to retry the exact saved history choice.",
            )
        } catch (error: ApiException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                if (error.isRetryable()) {
                    historyResolution = historyResolution?.copy(
                        submitting = false,
                        error = error.message
                            ?: "Could not finish history resolution. Retry uses the same saved request.",
                    )
                } else {
                    dao.deleteBootstrapResolution()
                    pendingBootstrapResolution = null
                    bootstrapSnapshot = null
                    bootstrapClockSample = null
                    historyResolution = corruptedResolutionState().copy(
                        error = "Server permanently rejected the saved history request (${error.statusCode}). Re-check account history without deleting local queues.",
                    )
                }
                publish()
            }
        } catch (error: IOException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "Could not finish history resolution. Retry uses the same saved request.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message ?: "History resolution failed without changing local data.",
                )
                publish()
            }
        }
    }

    override fun onForeground() {
        foreground = true
        replication?.onForeground()
        streamLifecycleGeneration += 1L
        if (replicationMode() == ReplicationMode.CENTRALIZED) {
            requestSync(force = true)
            streamLifecycleSignals.trySend(true)
        }
    }

    override fun onBackground() {
        foreground = false
        replication?.onBackground()
        streamLifecycleGeneration += 1L
        streamLifecycleSignals.trySend(false)
    }

    private suspend fun restoreProfile() {
        val identity = actionMutex.withLock { currentAttemptIdentity() }
        try {
            val profile = fetchValidatedProfile()
            val sentPhysicalMs = currentTimeMillis()
            val sentElapsedRealtimeMs = elapsedRealtimeMillis()
            val bootstrap = auth.authorized(api::bootstrap)
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            val clockSample = serverClockSample(
                bootstrap,
                sentPhysicalMs,
                sentElapsedRealtimeMs,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            )
            completeAuthentication(profile, bootstrap, identity, clockSample)
        } catch (error: IOException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Could not verify signed-in account"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(identity, "Session expired while refreshing account bootstrap.")
        } catch (error: ProfileProtocolException) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                auth.clear()
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                authStatus = AuthStatus.SignedOut
                user = null
                notice = error.message ?: "Could not validate account bootstrap"
                restorePendingResolutionForSignedOut(
                    "Sign in again to retry the exact saved history choice.",
                )
                publish()
            }
        }
    }

    private suspend fun completeAuthentication(
        profile: User,
        bootstrap: SyncResponse,
        identity: RepositoryAttemptIdentity,
        clockSample: ServerClockSample,
        repreviewResolution: Boolean = false,
    ): Boolean {
        var automaticAttempt: BootstrapResolutionAttempt? = null
        var accountMismatch = false
        var staleAttempt = false
        actionMutex.withLock {
            if (!isCurrent(identity)) {
                staleAttempt = true
                return@withLock
            }
            validateUser(profile)
            validateCanonicalResponse(bootstrap, "Bootstrap", requireEmptyAcknowledgements = true)
            val storedResolution = pendingBootstrapResolution
            val boundOwnerId = local.ownerUserId ?: storedResolution?.ownerUserId
            if (boundOwnerId != null && boundOwnerId != profile.id) {
                accountGeneration += 1
                pendingAccountSwitch = PendingAccountSwitch(profile, bootstrap, clockSample)
                accountSwitch = AccountSwitchState(
                    localAccount = user?.email ?: boundOwnerId,
                    incomingAccount = profile.email,
                )
                user = null
                historyResolution = null
                authStatus = AuthStatus.SignedIn
                syncing = false
                retrying = false
                publish()
                accountMismatch = true
                return@withLock
            }

            bootstrapSnapshot = bootstrap
            bootstrapClockSample = clockSample
            accountGeneration += 1
            user = profile
            pendingAccountSwitch = null
            accountSwitch = null
            conflict = null
            terminalSyncError = null

            when {
                storedResolution != null -> {
                    historyResolution = try {
                        val request = storedResolution.toRequestStrict()
                        HistoryResolutionState(
                            localHistoryCount = visibleHistoryCount(projection.history),
                            remoteHistoryCount = visibleHistoryCount(bootstrap.history),
                            pendingStrategy = request.strategy,
                            requestId = request.requestId,
                            error = "Previous history choice still needs a response from the server.",
                        )
                    } catch (_: Exception) {
                        corruptedResolutionState()
                    }
                }
                !repreviewResolution && local.ownerUserId == profile.id -> {
                    installBootstrap(profile, bootstrap, clearLocal = false, clockSample)
                }
                else -> {
                    val localHistoryCount = visibleHistoryCount(projection.history)
                    val remoteHistoryCount = visibleHistoryCount(bootstrap.history)
                    val localStateExists = hasLocalSyncState()
                    val remoteStateExists = hasRemoteSyncState(bootstrap)
                    val automaticStrategy = when {
                        localHistoryCount > 0 && remoteStateExists -> null
                        remoteHistoryCount > 0 && localStateExists -> null
                        localHistoryCount > 0 -> BootstrapStrategy.ReplaceRemote
                        remoteHistoryCount > 0 -> BootstrapStrategy.KeepRemote
                        localStateExists -> BootstrapStrategy.Merge
                        else -> BootstrapStrategy.KeepRemote
                    }
                    if (automaticStrategy == null) {
                        historyResolution = HistoryResolutionState(
                            localHistoryCount = localHistoryCount,
                            remoteHistoryCount = remoteHistoryCount,
                        )
                    } else {
                        automaticAttempt = prepareBootstrapResolution(
                            profile,
                            automaticStrategy,
                            bootstrap,
                            clockSample,
                        )
                    }
                }
            }
            authStatus = AuthStatus.SignedIn
            publish()
            scheduleAlarm()
        }
        if (staleAttempt) return false
        if (accountMismatch) return true
        automaticAttempt?.let { performBootstrapResolution(it) }
        return true
    }

    private suspend fun installBootstrap(
        profile: User,
        response: SyncResponse,
        clearLocal: Boolean,
        clockSample: ServerClockSample,
    ) {
        if (!clearLocal && response.revision < local.revision) {
            throw SyncProtocolException("Bootstrap revision regressed from ${local.revision} to ${response.revision}")
        }
        val retainedDurationOperations = if (clearLocal) emptyList() else pendingDurationOperations
        val retainedTaskOperations = if (clearLocal) emptyList() else pendingTaskOperations
        val retainedAutoStartOperations = if (clearLocal) emptyList() else pendingAutoStartOperations
        val retainedCommands = if (clearLocal) emptyList() else pending
        val (mergedWall, mergedCounter) = mergedClock(response, clockSample)
        val sampledLocal = local.copy(
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
            serverClockOffsetMs = clockSample.offsetMs,
            serverClockUncertaintyMs = clockSample.uncertaintyMs,
            serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
            serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
            serverClockBootId = bootId(),
        )
        val rebased = if (clearLocal) {
            RebasedMutationState(
                sampledLocal,
                retainedCommands,
                retainedTaskOperations,
                retainedDurationOperations,
                retainedAutoStartOperations,
            )
        } else {
            rebaseMutationState(
                trustedNowMs(clockSample),
                sampledLocal,
                response.serverHlcWallMs to response.serverHlcCounter,
                false,
                retainedCommands,
                retainedTaskOperations,
                retainedDurationOperations,
                retainedAutoStartOperations,
            )
        }
        val nextKnownTasks = if (clearLocal) {
            response.tasks.associateBy(FocusTask::id)
        } else {
            (knownTasks.values + response.tasks).associateBy(FocusTask::id)
        }
        val nextTasks = TaskReducer.replay(response.tasks, rebased.taskOperations)
        val nextSettings = replayDurationOperations(
            settings.withDurations(response.durationsMs),
            rebased.durationOperations,
        ).copy(
            autoStartBreaks = replayAutoStartOperations(
                response.autoStartBreaks,
                rebased.autoStartOperations,
            ),
        )
        val nextCanonicalTimer = localizedCanonicalTimer(
            response.canonicalTimer,
            rebased.commands,
            responsePhysicalDelta(clockSample),
        )
        val nextCanonicalHistory = localizedHistory(
            response.history,
            rebased.commands,
            responsePhysicalDelta(clockSample),
        )
        val nextProjection = TimerReducer.replay(
            nextCanonicalTimer,
            nextCanonicalHistory,
            rebased.commands,
        )
        val nextLocal = rebased.local.copy(
            revision = response.revision,
            canonicalTimerJson = nextCanonicalTimer?.let { json.encodeToString(it) },
            historyJson = json.encodeToString(nextCanonicalHistory),
            tasksJson = json.encodeToString(response.tasks),
            knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                nextTasks.any { it.id == selected }
            },
            settingsJson = json.encodeToString(nextSettings),
            userJson = json.encodeToString(profile),
            ownerUserId = profile.id,
            canonicalAutoStartBreaks = response.autoStartBreaks,
            ownedTimerId = local.ownedTimerId?.takeIf {
                !clearLocal &&
                    nextProjection.timer?.status in activeStatuses &&
                    nextProjection.timer?.id == it
            },
        )
        if (clearLocal) {
            dao.clearAccount(nextLocal)
        } else {
            dao.updateMutationState(
                nextLocal,
                rebased.commands.map { command ->
                    PendingCommandEntity.from(command, commandDependencies[command.id])
                },
                rebased.taskOperations.map(PendingTaskOperationEntity::from),
                rebased.durationOperations.map(PendingDurationOperationEntity::from),
                rebased.autoStartOperations.map(PendingAutoStartOperationEntity::from),
            )
        }
        installTrustedAnchor(clockSample)
        local = nextLocal
        settings = nextSettings
        canonicalTimer = nextCanonicalTimer
        canonicalHistory = nextCanonicalHistory
        canonicalTasks = response.tasks
        canonicalAutoStartBreaks = response.autoStartBreaks
        knownTasks = nextKnownTasks
        if (clearLocal) {
            pending = emptyList()
            pendingDurationOperations = emptyList()
            pendingTaskOperations = emptyList()
            pendingAutoStartOperations = emptyList()
            commandDependencies = emptyMap()
            pendingBootstrapResolution = null
        } else {
            pending = rebased.commands
            pendingDurationOperations = rebased.durationOperations
            pendingTaskOperations = rebased.taskOperations
            pendingAutoStartOperations = rebased.autoStartOperations
        }
        historyResolution = null
        rebuildProjections()
    }

    private suspend fun finishLocalTimer(
        onlyIfExpired: Boolean,
        allowWhileLoading: Boolean,
    ): Boolean {
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked(allowWhileLoading)) return@withLock
            val current = projection.timer ?: return@withLock
            if (current.status !in activeStatuses) return@withLock
            if (onlyIfExpired && current.id != local.ownedTimerId) return@withLock
            if (onlyIfExpired && (
                    current.status != TimerStatus.Running ||
                        TimerReducer.elapsedAt(current) < current.plannedDurationMs
                    )
            ) return@withLock

            val generatesBreak = current.phase == TimerPhase.Focus &&
                settings.autoStartBreaks &&
                current.id == local.ownedTimerId
            val reservation = reserveMutation(
                count = if (generatesBreak) 2 else 1,
                withDeviceSequences = true,
            ) ?: return@withLock
            val stamps = reservation.stamps
            val finishStamp = stamps.first()
            val physicalNowMs = currentTimeMillis()
            val physicalOccurredAt = Instant.ofEpochMilli(physicalNowMs).toString()
            val finish = TimerCommand(
                id = reservation.uuids.first().toString(),
                deviceSequence = requireNotNull(finishStamp.deviceSequence),
                timerId = current.id,
                type = CommandType.Finish,
                phase = current.phase,
                plannedDurationMs = current.plannedDurationMs,
                occurredAt = finishStamp.occurredAt,
                hlcWallMs = finishStamp.wallMs,
                hlcCounter = finishStamp.counter,
                observedElapsedMs = TimerReducer.elapsedAt(current, physicalNowMs),
                physicalOccurredAt = physicalOccurredAt,
            )
            val commands = mutableListOf(finish)
            val dependencies = mutableMapOf<String, String>()
            dependencyForTimer(current.id)?.let { dependencies[finish.id] = it }
            val nextPhase = if (current.phase == TimerPhase.Focus) {
                val projected = TimerReducer.replay(
                    canonicalTimer,
                    canonicalHistory,
                    pending + finish,
                )
                val completedFocusCount = TimerReducer.completedFocusCountForDay(
                    projected.history,
                    Instant.ofEpochMilli(physicalNowMs),
                )
                nextBreakPhase(completedFocusCount)
            } else {
                TimerPhase.Focus
            }
            val nextSettings = settings.copy(selectedPhase = nextPhase)
            if (generatesBreak) {
                val startStamp = stamps.last()
                val generatedStart = TimerCommand(
                    id = reservation.uuids.last().toString(),
                    deviceSequence = requireNotNull(startStamp.deviceSequence),
                    timerId = UUID.randomUUID().toString(),
                    type = CommandType.Start,
                    phase = nextPhase,
                    plannedDurationMs = settings.durationMsFor(nextPhase),
                    occurredAt = startStamp.occurredAt,
                    hlcWallMs = startStamp.wallMs,
                    hlcCounter = startStamp.counter,
                    observedElapsedMs = 0,
                    physicalOccurredAt = physicalOccurredAt,
                )
                commands += generatedStart
                dependencies[generatedStart.id] = finish.id
            }
            val provisionalBreak = commands.lastOrNull()?.takeIf { it.type == CommandType.Start }
            val nextLocal = local.copy(
                deviceSequence = commands.last().deviceSequence,
                hlcWallMs = stamps.last().wallMs,
                hlcCounter = stamps.last().counter,
                ownedTimerId = provisionalBreak?.timerId ?: local.ownedTimerId,
                settingsJson = json.encodeToString(nextSettings),
                lastUuidV7 = reservation.lastUuidV7,
            )
            dao.persistCommands(
                commands.map { command ->
                    PendingCommandEntity.from(command, dependencies[command.id])
                },
                nextLocal,
            )
            local = nextLocal
            if (nextPhase != settings.selectedPhase) selectedPhaseGeneration += 1L
            settings = nextSettings
            pending = pending + commands
            commandDependencies = commandDependencies + dependencies
            rebuildProjections()
            publish()
            scheduleAlarm()
            saved = true
        }
        if (saved) afterLocalMutation()
        return saved
    }

    private suspend fun issueCommand(type: String, startingPhase: String? = null): Boolean {
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked()) return@withLock
            val current = projection.timer
            val starting = type == CommandType.Start
            val timerId = if (starting) UUID.randomUUID().toString() else current?.id
            if (timerId == null || !validTransition(type, current)) return@withLock
            val phase = if (starting) {
                startingPhase ?: settings.selectedPhase
            } else {
                current?.phase ?: return@withLock
            }
            val durationMs = if (starting) {
                settings.durationMsFor(phase)
            } else {
                current?.plannedDurationMs ?: return@withLock
            }
            val reservation = reserveMutation(count = 1, withDeviceSequences = true)
                ?: return@withLock
            val stamp = reservation.stamps.single()
            val physicalNowMs = currentTimeMillis()
            val command = TimerCommand(
                id = reservation.uuids.single().toString(),
                deviceSequence = requireNotNull(stamp.deviceSequence),
                timerId = timerId,
                type = type,
                phase = phase,
                plannedDurationMs = durationMs,
                occurredAt = stamp.occurredAt,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                observedElapsedMs = if (starting) 0 else TimerReducer.elapsedAt(current, physicalNowMs),
                taskId = if (starting && phase == TimerPhase.Focus) {
                    local.selectedTaskId?.takeIf { selected -> tasks.any { it.id == selected } }
                } else {
                    null
                },
                physicalOccurredAt = Instant.ofEpochMilli(physicalNowMs).toString(),
            )
            val nextLocal = local.copy(
                deviceSequence = command.deviceSequence,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                ownedTimerId = timerId.takeIf { starting } ?: local.ownedTimerId,
                lastUuidV7 = reservation.lastUuidV7,
            )
            val dependency = if (starting) null else dependencyForTimer(command.timerId)
            dao.persistCommand(PendingCommandEntity.from(command, dependency), nextLocal)
            local = nextLocal
            pending = pending + command
            if (dependency != null) {
                commandDependencies = commandDependencies + (command.id to dependency)
            }
            rebuildProjections()
            publish()
            scheduleAlarm()
            saved = true
        }
        if (saved) afterLocalMutation()
        return saved
    }

    private suspend fun issueTaskOperation(
        type: String,
        task: FocusTask,
        select: Boolean = false,
    ) {
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || select && projection.timer?.status in activeStatuses) return@withLock
            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val stamp = reservation.stamps.single()
            val operation = TaskOperation(
                id = "task-operation-${reservation.uuids.single()}",
                taskId = task.id,
                type = type,
                title = task.title.takeIf { type == TaskOperationType.Upsert },
                occurredAt = stamp.occurredAt,
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
            )
            val nextKnownTasks = knownTasks + (task.id to task)
            val nextLocal = local.copy(
                hlcWallMs = stamp.wallMs,
                hlcCounter = stamp.counter,
                selectedTaskId = when {
                    select -> task.id
                    type == TaskOperationType.Delete && local.selectedTaskId == task.id -> null
                    else -> local.selectedTaskId
                },
                knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
                lastUuidV7 = reservation.lastUuidV7,
            )
            dao.persistTaskOperation(PendingTaskOperationEntity.from(operation), nextLocal)
            local = nextLocal
            knownTasks = nextKnownTasks
            pendingTaskOperations = pendingTaskOperations + operation
            rebuildProjections()
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    private fun validTransition(type: String, timer: CanonicalTimer?): Boolean = when (type) {
        CommandType.Start -> true
        CommandType.Pause -> timer?.status == TimerStatus.Running
        CommandType.Resume -> timer?.status == TimerStatus.Paused || timer?.status == TimerStatus.Superseded
        CommandType.Finish, CommandType.Cancel -> timer?.status in activeStatuses
        CommandType.Clear -> timer?.status in setOf(TimerStatus.Completed, TimerStatus.Cancelled)
        else -> false
    }

    private fun nextBreakPhase(completedFocus: Int): String {
        return if (completedFocus > 0 && completedFocus % 4 == 0) {
            TimerPhase.LongBreak
        } else {
            TimerPhase.ShortBreak
        }
    }

    private fun resolveGeneratedCommands(
        sentCommands: List<TimerCommand>,
        acknowledgementResponse: SyncResponse,
        canonicalResponse: SyncResponse,
        nextSettings: TimerSettings,
    ): GeneratedCommandResolution {
        val sentById = sentCommands.associateBy(TimerCommand::id)
        val acknowledgements = acknowledgementResponse.acknowledgements
            .associateBy(Acknowledgement::commandId)
        val groups = pending
            .filter { it.id !in sentById && commandDependencies[it.id] in sentById }
            .groupBy { requireNotNull(commandDependencies[it.id]) }
        val released = mutableListOf<TimerCommand>()
        val discarded = mutableListOf<TimerCommand>()
        val discardedSourceTimerIds = mutableSetOf<String>()

        groups.forEach { (sourceId, commands) ->
            val source = sentById[sourceId]
            val acknowledgement = acknowledgements[sourceId]
            val generatedStart = commands.firstOrNull { it.type == CommandType.Start }
            val exactCompletionEvidence = source != null && (
                acknowledgementResponse.history.any {
                    it.timerId == source.timerId &&
                        it.commandId == source.id &&
                        it.phase == TimerPhase.Focus &&
                        it.status == TimerStatus.Completed
                } || acknowledgementResponse.canonicalTimer?.let {
                    it.id == source.timerId &&
                        it.lastIntent?.commandId == source.id &&
                        it.phase == TimerPhase.Focus &&
                        it.status == TimerStatus.Completed
                } == true
                )
            val accepted = source?.type == CommandType.Finish && (
                acknowledgement?.outcome in setOf("applied", "ignored") &&
                    exactCompletionEvidence
                )
            val supersededByManualStart = generatedStart != null && pending.any { command ->
                command.type == CommandType.Start &&
                    command.id !in commandDependencies &&
                    command.deviceSequence > generatedStart.deviceSequence
            }

            if (!accepted || generatedStart == null || supersededByManualStart) {
                discarded += commands
                if (!accepted && source != null) discardedSourceTimerIds += source.timerId
                return@forEach
            }
            val acceptedSource = source ?: return@forEach

            val completedFocusTimerIds = completedFocusTimerIds(
                canonicalResponse,
                completionReference(canonicalResponse, acceptedSource),
            )
            completedFocusTimerIds += acceptedSource.timerId
            val phase = nextBreakPhase(completedFocusTimerIds.size)
            val durationMs = nextSettings.durationMsFor(phase)
            val generatedBreakCompleted = projection.history.any {
                it.timerId == generatedStart.timerId && it.status == TimerStatus.Completed
            }
            released += if (generatedBreakCompleted) {
                commands
            } else {
                commands.map { command ->
                    command.copy(
                        phase = phase,
                        plannedDurationMs = durationMs,
                        observedElapsedMs = command.observedElapsedMs.coerceIn(0, durationMs),
                    )
                }
            }
        }
        return GeneratedCommandResolution(released, discarded, discardedSourceTimerIds)
    }

    private fun resolvedOwnedTimerId(
        nextProjection: TimerProjection,
        resolution: GeneratedCommandResolution,
    ): String? {
        val activeTimerId = nextProjection.timer
            ?.takeIf { it.status in activeStatuses }
            ?.id
            ?: return null
        return activeTimerId.takeIf {
            it == local.ownedTimerId || it in resolution.discardedSourceTimerIds
        }
    }

    private fun reconciledSelectedPhase(
        currentPhase: String,
        selectedPhaseAtSend: String?,
        selectedPhaseGenerationAtSend: Long?,
        sentCommands: List<TimerCommand>,
        acknowledgementResponse: SyncResponse,
        canonicalResponse: SyncResponse,
        nextProjection: TimerProjection,
    ): String {
        if (selectedPhaseAtSend != null && (
                currentPhase != selectedPhaseAtSend ||
                    selectedPhaseGenerationAtSend != selectedPhaseGeneration
                )
        ) return currentPhase
        val acknowledgements = acknowledgementResponse.acknowledgements
            .associateBy(Acknowledgement::commandId)
        return sentCommands.asSequence()
            .filter { it.type == CommandType.Finish }
            .sortedWith(compareBy(TimerCommand::deviceSequence, TimerCommand::id))
            .fold(currentPhase) { selectedPhase, finish ->
                val acknowledgement = acknowledgements[finish.id] ?: return@fold selectedPhase
                val canonicallyCompleted = acknowledgementResponse.history.any {
                    it.timerId == finish.timerId && it.status == TimerStatus.Completed
                } || acknowledgementResponse.canonicalTimer?.let {
                    it.id == finish.timerId && it.status == TimerStatus.Completed
                } == true
                if (acknowledgement.outcome != "applied" && !canonicallyCompleted) {
                    return@fold canonicalResponse.canonicalTimer
                        ?.takeIf { it.id == finish.timerId }
                        ?.phase
                        ?: finish.phase
                }
                val generatedStart = pending.firstOrNull { command ->
                    commandDependencies[command.id] == finish.id && command.type == CommandType.Start
                }
                when {
                    canonicallyCompleted && generatedStart != null -> {
                        val completed = completedFocusTimerIds(
                            canonicalResponse,
                            completionReference(canonicalResponse, finish),
                        )
                        completed += finish.timerId
                        nextBreakPhase(completed.size)
                    }
                    canonicallyCompleted && finish.phase == TimerPhase.Focus -> {
                        val completed = completedFocusTimerIds(
                            canonicalResponse,
                            completionReference(canonicalResponse, finish),
                        )
                        completed += finish.timerId
                        nextBreakPhase(completed.size)
                    }
                    canonicallyCompleted && finish.phase != TimerPhase.Focus -> TimerPhase.Focus
                    nextProjection.timer?.lastIntent?.commandId == finish.id ->
                        nextProjection.timer.phase
                    else -> selectedPhase
                }
            }
    }

    private fun completionReference(response: SyncResponse, source: TimerCommand): Instant {
        val sourceTimestamp = response.history.firstOrNull {
            it.timerId == source.timerId &&
                it.status == TimerStatus.Completed &&
                (it.commandId == source.id || it.commandId == null)
        }?.let { it.completedAt ?: it.endedAt }
            ?: response.canonicalTimer?.takeIf {
                it.id == source.timerId && it.status == TimerStatus.Completed
            }?.anchorAt
            ?: source.physicalOccurredAt
            ?: source.occurredAt
        return runCatching { Instant.parse(sourceTimestamp) }
            .getOrElse { Instant.parse(source.occurredAt) }
    }

    private fun completedFocusTimerIds(
        response: SyncResponse,
        reference: Instant,
    ): MutableSet<String> =
        response.history.asSequence()
            .filter {
                it.phase == TimerPhase.Focus &&
                    it.status == TimerStatus.Completed &&
                    TimerReducer.occursOnLocalDay(it.completedAt ?: it.endedAt, reference)
            }
            .map(HistoryItem::timerId)
            .toMutableSet()
            .also { completed ->
                response.canonicalTimer?.takeIf {
                    it.phase == TimerPhase.Focus &&
                        it.status == TimerStatus.Completed &&
                        TimerReducer.occursOnLocalDay(it.anchorAt, reference)
                }?.let { completed += it.id }
            }

    private fun requestSync(force: Boolean = false) {
        if (replicationMode() != ReplicationMode.CENTRALIZED) return
        if (force) forceSync.set(true)
        syncSignals.trySend(Unit)
    }

    private suspend fun afterLocalMutation() {
        if (replicationMode() == ReplicationMode.IROH) {
            try {
                replication?.afterLocalMutation()
                reloadWorkspace(ReplicationMode.IROH)
            } catch (error: Exception) {
                conflict = error.message ?: "Iroh room operation could not be recorded"
                publish()
            }
        } else if (replicationMode() == ReplicationMode.CENTRALIZED) {
            requestSync()
        }
    }

    private fun replicationMode(): ReplicationMode = replication?.mode ?: ReplicationMode.CENTRALIZED

    suspend fun reloadWorkspace(mode: ReplicationMode = replicationMode()) {
        actionMutex.withLock {
            if (!initialized.isCompleted) return@withLock
            val stored = checkNotNull(dao.localState()) { "Local workspace is missing" }
            local = stored
            val commandEntities = dao.pendingCommands()
            pending = commandEntities.map(PendingCommandEntity::toModel)
            commandDependencies = commandEntities.mapNotNull { entity ->
                entity.generatedByFinishCommandId?.let { entity.id to it }
            }.toMap()
            pendingTaskOperations = dao.pendingTaskOperations().map(PendingTaskOperationEntity::toModel)
            pendingDurationOperations = dao.pendingDurationOperations()
                .map(PendingDurationOperationEntity::toModel)
            pendingAutoStartOperations = dao.pendingAutoStartOperations()
                .map(PendingAutoStartOperationEntity::toModel)
            pendingBootstrapResolution = dao.pendingBootstrapResolution()
            settings = strictJson.decodeFromString(stored.settingsJson)
            canonicalTimer = stored.canonicalTimerJson?.let(strictJson::decodeFromString)
            canonicalHistory = strictJson.decodeFromString(stored.historyJson)
            canonicalTasks = strictJson.decodeFromString(stored.tasksJson)
            canonicalAutoStartBreaks = stored.canonicalAutoStartBreaks
            knownTasks = strictJson.decodeFromString<List<FocusTask>>(stored.knownTasksJson)
                .plus(canonicalTasks)
                .associateBy(FocusTask::id)
            user = stored.userJson?.let(strictJson::decodeFromString)
            authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
            conflict = networkState.conflict?.let { "Iroh room has an immutable operation conflict." }
            rebuildProjections()
            publish()
            scheduleAlarm()
        }
        if (mode == ReplicationMode.CENTRALIZED && foreground) streamLifecycleSignals.trySend(true)
    }

    fun scheduleWorkspaceReload() {
        scope.launch { reloadWorkspace(ReplicationMode.IROH) }
    }

    override fun refresh() {
        if (replicationMode() == ReplicationMode.IROH) {
            scope.launch { replication?.syncNow() }
        } else if (replicationMode() == ReplicationMode.CENTRALIZED && authStatus == AuthStatus.SignedIn) {
            requestSync(force = true)
        }
    }

    private suspend fun syncLoop() {
        for (signal in syncSignals) {
            initialized.await()
            var forced = forceSync.getAndSet(false)
            var retryDelay = initialSyncRetryDelayMs
            while (scope.isActive && authStatus == AuthStatus.SignedIn &&
                replicationMode() == ReplicationMode.CENTRALIZED
            ) {
                if (historyResolution != null || accountSwitch != null) {
                    syncing = false
                    retrying = false
                    publish()
                    break
                }
                if (terminalSyncError != null) {
                    publish()
                    break
                }
                if (!online) {
                    publish()
                    break
                }
                if (!forced &&
                    pending.isEmpty() &&
                    pendingTaskOperations.isEmpty() &&
                    pendingDurationOperations.isEmpty() &&
                    pendingAutoStartOperations.isEmpty()
                ) {
                    retrying = false
                    publish()
                    break
                }
                try {
                    syncOnce()
                    retryDelay = initialSyncRetryDelayMs
                    forced = false
                    if (pending.isEmpty() &&
                        pendingTaskOperations.isEmpty() &&
                        pendingDurationOperations.isEmpty() &&
                        pendingAutoStartOperations.isEmpty()
                    ) break
                } catch (_: AuthenticationRequired) {
                    actionMutex.withLock {
                        accountGeneration += 1
                        authStatus = AuthStatus.SignedOut
                        user = null
                        syncing = false
                        retrying = false
                        publish()
                    }
                    break
                } catch (error: SyncProtocolException) {
                    markTerminalSyncError(error.message ?: "Sync protocol validation failed")
                    break
                } catch (error: SerializationException) {
                    markTerminalSyncError(error.message ?: "Sync returned a malformed response")
                    break
                } catch (error: ApiException) {
                    syncing = false
                    if (!error.isRetryable()) {
                        markTerminalSyncError(error.message ?: "Sync rejected (${error.statusCode})")
                        break
                    }
                    retrying = true
                    publish()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                    forced = true
                } catch (_: IOException) {
                    syncing = false
                    retrying = true
                    publish()
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(60_000L)
                    forced = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    syncing = false
                    retrying = false
                    notice = error.message ?: "Sync stopped after a local failure"
                    publish()
                    break
                }
            }
        }
    }

    private suspend fun syncOnce() {
        val attempt = actionMutex.withLock {
            if (historyResolution != null || accountSwitch != null) return@withLock null
            validatePendingSyncQueues(
                pending,
                pendingTaskOperations,
                pendingDurationOperations,
                pendingAutoStartOperations,
            )
            val sent = eligiblePendingCommands().asSequence()
                .take(MaxCommandsPerSync)
                .toList()
            val sentTaskOperations = pendingTaskOperations.take(MaxTaskOperationsPerSync)
            val sentDurationOperations = pendingDurationOperations
                .sortedWith(durationOperationComparator)
                .take(MaxDurationOperationsPerSync)
            val sentAutoStartOperations = pendingAutoStartOperations
                .sortedWith(autoStartOperationComparator)
                .take(MaxAutoStartOperationsPerSync)
            syncing = true
            retrying = false
            publish()
            SyncAttempt(
                accountGeneration = accountGeneration,
                request = SyncRequest(
                    deviceId = local.deviceId,
                    lastRevision = local.revision,
                    commands = sent.map(::forTrustedWire),
                    durationOperations = sentDurationOperations.map(::forTrustedWire),
                    taskOperations = sentTaskOperations.map(::forTrustedWire),
                    autoStartOperations = sentAutoStartOperations.map(::forTrustedWire),
                ),
                sentPhysicalMs = currentTimeMillis(),
                sentElapsedRealtimeMs = elapsedRealtimeMillis(),
                selectedPhaseAtSend = settings.selectedPhase,
                selectedPhaseGenerationAtSend = selectedPhaseGeneration,
            )
        } ?: return
        val response = auth.authorized { api.sync(it, attempt.request) }
        val receivedPhysicalMs = currentTimeMillis()
        val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
        actionMutex.withLock {
            if (attempt.accountGeneration != accountGeneration || authStatus != AuthStatus.SignedIn ||
                replicationMode() != ReplicationMode.CENTRALIZED
            ) {
                syncing = false
                publish()
                return@withLock
            }
            val sentCommandIds = validateAcknowledgements(
                attempt.request.commands.map(TimerCommand::id),
                response.acknowledgements.map(Acknowledgement::commandId),
                "command",
            )
            val sentTaskOperationIds = validateAcknowledgements(
                attempt.request.taskOperations.map(TaskOperation::id),
                response.taskAcknowledgements.map(TaskAcknowledgement::operationId),
                "task",
            )
            val sentDurationOperationIds = validateAcknowledgements(
                attempt.request.durationOperations.map(DurationOperation::id),
                response.durationAcknowledgements.map(DurationAcknowledgement::operationId),
                "duration",
            )
            val sentAutoStartOperationIds = validateAcknowledgements(
                attempt.request.autoStartOperations.map(AutoStartOperation::id),
                response.autoStartAcknowledgements.map(AutoStartAcknowledgement::operationId),
                "auto-start",
            )
            validateCanonicalResponse(response, "Sync")
            val clockSample = serverClockSample(
                response,
                attempt.sentPhysicalMs,
                attempt.sentElapsedRealtimeMs,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            )
            if (response.revision < local.revision) {
                throw SyncProtocolException("Sync revision regressed from ${local.revision} to ${response.revision}")
            }
            val acknowledgedEntities = attempt.request.commands.map(PendingCommandEntity::from)
            val acknowledgedTaskEntities = attempt.request.taskOperations
                .map(PendingTaskOperationEntity::from)
            val nextPendingTaskOperations = pendingTaskOperations
                .filterNot { it.id in sentTaskOperationIds }
            val nextPendingDurationOperations = pendingDurationOperations
                .filterNot { it.id in sentDurationOperationIds }
            val acknowledgedAutoStartEntities = attempt.request.autoStartOperations
                .map(PendingAutoStartOperationEntity::from)
            val nextPendingAutoStartOperations = pendingAutoStartOperations
                .filterNot { it.id in sentAutoStartOperationIds }
            val responsePhysicalDeltaMs = responsePhysicalDelta(clockSample)
            val nextCanonicalTimer = localizedCanonicalTimer(
                response.canonicalTimer,
                attempt.request.commands,
                responsePhysicalDeltaMs,
            )
            val nextCanonicalHistory = localizedHistory(
                response.history,
                attempt.request.commands,
                responsePhysicalDeltaMs,
            )
            val nextCanonicalTasks = response.tasks
            val nextKnownTasks = (knownTasks.values + nextCanonicalTasks).associateBy(FocusTask::id)
            val nextTasks = TaskReducer.replay(nextCanonicalTasks, nextPendingTaskOperations)
            val projectedSettings = replayDurationOperations(
                settings.withDurations(response.durationsMs),
                nextPendingDurationOperations,
            ).copy(
                autoStartBreaks = replayAutoStartOperations(
                    response.autoStartBreaks,
                    nextPendingAutoStartOperations,
                ),
            )
            val generatedResolution = resolveGeneratedCommands(
                attempt.request.commands,
                response,
                response,
                projectedSettings,
            )
            val discardedCommandIds = generatedResolution.discarded.map(TimerCommand::id).toSet()
            val releasedCommandsById = generatedResolution.released.associateBy(TimerCommand::id)
            val nextPending = pending
                .filterNot { it.id in sentCommandIds || it.id in discardedCommandIds }
                .map { releasedCommandsById[it.id] ?: it }
            val nextCommandDependencies = commandDependencies -
                (generatedResolution.released + generatedResolution.discarded).map(TimerCommand::id).toSet()
            val nextProjection = TimerReducer.replay(
                nextCanonicalTimer,
                nextCanonicalHistory,
                nextPending,
            )
            val nextSettings = projectedSettings.copy(
                selectedPhase = reconciledSelectedPhase(
                    projectedSettings.selectedPhase,
                    attempt.selectedPhaseAtSend,
                    attempt.selectedPhaseGenerationAtSend,
                    attempt.request.commands,
                    response,
                    response,
                    nextProjection,
                ),
            )
            val nextOwnedTimerId = resolvedOwnedTimerId(nextProjection, generatedResolution)
            val (mergedWall, mergedCounter) = mergedClock(response, clockSample)
            val sampledLocal = local.copy(
                hlcWallMs = mergedWall,
                hlcCounter = mergedCounter,
                serverClockOffsetMs = clockSample.offsetMs,
                serverClockUncertaintyMs = clockSample.uncertaintyMs,
                serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
                serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
                serverClockBootId = bootId(),
            )
            val rebased = rebaseMutationState(
                trustedNowMs(clockSample),
                sampledLocal,
                response.serverHlcWallMs to response.serverHlcCounter,
                true,
                nextPending,
                nextPendingTaskOperations,
                nextPendingDurationOperations,
                nextPendingAutoStartOperations,
            )
            val nextLocal = rebased.local.copy(
                revision = response.revision,
                canonicalTimerJson = nextCanonicalTimer?.let { json.encodeToString(it) },
                historyJson = json.encodeToString(nextCanonicalHistory),
                tasksJson = json.encodeToString(nextCanonicalTasks),
                knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
                selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                    nextTasks.any { it.id == selected }
                },
                settingsJson = json.encodeToString(nextSettings),
                canonicalAutoStartBreaks = response.autoStartBreaks,
                ownedTimerId = nextOwnedTimerId,
            )
            dao.applyFullSync(
                acknowledgedCommands = acknowledgedEntities,
                acknowledgedTaskOperations = acknowledgedTaskEntities,
                acknowledgedDurationOperationIds = sentDurationOperationIds.toList(),
                state = nextLocal,
                acknowledgedAutoStartOperations = acknowledgedAutoStartEntities,
                updatedCommands = rebased.commands.map { command ->
                    PendingCommandEntity.from(command, nextCommandDependencies[command.id])
                },
                updatedTaskOperations = rebased.taskOperations.map(PendingTaskOperationEntity::from),
                updatedDurationOperations = rebased.durationOperations.map(
                    PendingDurationOperationEntity::from,
                ),
                updatedAutoStartOperations = rebased.autoStartOperations.map(
                    PendingAutoStartOperationEntity::from,
                ),
                discardedCommands = generatedResolution.discarded.map { command ->
                    PendingCommandEntity.from(command, commandDependencies[command.id])
                },
            )
            installTrustedAnchor(clockSample)
            pending = rebased.commands
            commandDependencies = nextCommandDependencies
            pendingTaskOperations = rebased.taskOperations
            pendingDurationOperations = rebased.durationOperations
            pendingAutoStartOperations = rebased.autoStartOperations
            canonicalTimer = nextCanonicalTimer
            canonicalHistory = nextCanonicalHistory
            canonicalTasks = nextCanonicalTasks
            canonicalAutoStartBreaks = response.autoStartBreaks
            knownTasks = nextKnownTasks
            settings = nextSettings
            local = nextLocal
            reconcileSyncOutcomeConflict(response)
            rebuildProjections()
            syncing = false
            retrying = false
            publish()
            scheduleAlarm()
        }
    }

    private fun ApiException.isRetryable(): Boolean =
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500

    private fun markTerminalSyncError(message: String) {
        syncing = false
        retrying = false
        terminalSyncError = message
        conflict = message
        publish()
    }

    private fun validateAcknowledgements(
        sentIds: List<String>,
        acknowledgedIds: List<String>,
        kind: String,
    ): Set<String> {
        val sent = sentIds.toSet()
        val acknowledged = acknowledgedIds.toSet()
        if (sent.size != sentIds.size ||
            acknowledged.size != acknowledgedIds.size ||
            acknowledged != sent
        ) {
            throw SyncProtocolException("Sync returned an invalid $kind acknowledgement set")
        }
        return sent
    }

    private fun syncOutcomeConflict(response: SyncResponse): String? {
        val outcomes = buildList {
            response.acknowledgements
                .filter { it.outcome != "applied" }
                .forEach { add(Triple("Command", it.outcome, it.reason)) }
            response.taskAcknowledgements
                .filter { it.outcome != "applied" }
                .forEach { add(Triple("Task", it.outcome, it.reason)) }
            response.durationAcknowledgements
                .filter { it.outcome != "applied" }
                .forEach { add(Triple("Duration", it.outcome, it.reason)) }
            response.autoStartAcknowledgements
                .filter { it.outcome != "applied" }
                .forEach { add(Triple("Auto-start", it.outcome, it.reason)) }
        }
        return when (outcomes.size) {
            0 -> null
            1 -> outcomes.single().let { (kind, outcome, reason) ->
                reason.ifBlank { "$kind outcome: $outcome" }
            }
            else -> outcomes.joinToString("\n") { (kind, outcome, reason) ->
                "$kind: ${reason.ifBlank { outcome }}"
            }
        }
    }

    private fun reconcileSyncOutcomeConflict(response: SyncResponse) {
        val outcomeConflict = syncOutcomeConflict(response)
        val converged = pending.isEmpty() &&
            pendingTaskOperations.isEmpty() &&
            pendingDurationOperations.isEmpty() &&
            pendingAutoStartOperations.isEmpty()
        if (outcomeConflict != null || converged) conflict = outcomeConflict
    }

    private suspend fun openRevisionStream() {
        initialized.await()
        streamMutex.withLock {
            if (!foreground || !online || authStatus != AuthStatus.SignedIn ||
                replicationMode() != ReplicationMode.CENTRALIZED ||
                historyResolution != null || accountSwitch != null || eventSource != null
            ) return
            try {
                eventSource = auth.authorized { token ->
                    api.revisionStream(token, object : EventSourceListener() {
                        override fun onEvent(
                            eventSource: EventSource,
                            id: String?,
                            type: String?,
                            data: String,
                        ) {
                            val revision = data.toLongOrNull() ?: runCatching {
                                json.parseToJsonElement(data)
                                    .jsonObject["revision"]
                                    ?.jsonPrimitive
                                    ?.content
                                    ?.toLong()
                            }.getOrNull()
                            if (revision == null || revision > local.revision) requestSync(force = true)
                        }

                        override fun onClosed(eventSource: EventSource) {
                            handleRevisionStreamEnd(eventSource)
                        }

                        override fun onFailure(
                            eventSource: EventSource,
                            t: Throwable?,
                            response: Response?,
                        ) {
                            handleRevisionStreamEnd(eventSource, response?.code)
                        }
                    })
                }
            } catch (_: AuthenticationRequired) {
                actionMutex.withLock {
                    accountGeneration += 1
                    authStatus = AuthStatus.SignedOut
                    user = null
                    publish()
                }
            }
        }
    }

    private fun handleRevisionStreamEnd(source: EventSource, responseCode: Int? = null) {
        scope.launch {
            val reconnectGeneration: Long? = streamMutex.withLock {
                if (eventSource !== source) return@withLock null
                eventSource = null
                streamLifecycleGeneration.takeIf {
                    foreground && online && authStatus == AuthStatus.SignedIn &&
                        replicationMode() == ReplicationMode.CENTRALIZED
                }
            }
            if (responseCode == 401) requestSync(force = true)
            if (reconnectGeneration != null) {
                delay(5_000)
                if (foreground && replicationMode() == ReplicationMode.CENTRALIZED &&
                    streamLifecycleGeneration == reconnectGeneration
                ) {
                    streamLifecycleSignals.trySend(true)
                }
            }
        }
    }

    private suspend fun closeRevisionStream() {
        streamMutex.withLock {
            val source = eventSource
            eventSource = null
            source?.cancel()
        }
    }

    private fun updateNetworkState() {
        val nowOnline = currentOnlineState()
        val restored = !online && nowOnline
        online = nowOnline
        publish()
        if (restored) {
            requestSync(force = true)
            streamLifecycleSignals.trySend(true)
        } else if (!online) {
            streamLifecycleSignals.trySend(false)
        }
    }

    private fun currentOnlineState(): Boolean {
        networkAvailable?.let { return it() }
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scheduleAlarm() {
        alarmScheduler.update(
            projection.timer?.takeIf { it.id == local.ownedTimerId },
        )
    }

    private fun dependencyForTimer(timerId: String): String? = pending.firstNotNullOfOrNull { command ->
        commandDependencies[command.id]?.takeIf { command.timerId == timerId }
    }

    private fun eligiblePendingCommands(): List<TimerCommand> =
        pending.filter { it.id !in commandDependencies }

    private fun rebuildProjections() {
        val previousTimerID = projection.timer?.id
        projection = TimerReducer.replay(canonicalTimer, canonicalHistory, pending)
        if (shouldStopCompletionAlert(previousTimerID, projection.timer)) {
            SystemTimerCompletionNotifier.cancel(appContext)
        }
        tasks = TaskReducer.replay(canonicalTasks, pendingTaskOperations)
        val pendingUpserts = pendingTaskOperations.mapNotNull { operation ->
            operation.title
                ?.takeIf { operation.type == TaskOperationType.Upsert }
                ?.let(TaskReducer::taskFromTitle)
        }
        knownTasks = (knownTasks.values + canonicalTasks + pendingUpserts).associateBy(FocusTask::id)
        if (local.selectedTaskId != null && tasks.none { it.id == local.selectedTaskId }) {
            local = local.copy(selectedTaskId = null)
        }
    }

    private fun replayDurationOperations(
        base: TimerSettings,
        operations: List<DurationOperation>,
    ): TimerSettings = SettingsReducer.replayDurations(base, operations)

    private fun replayAutoStartOperations(
        base: Boolean,
        operations: List<AutoStartOperation>,
    ): Boolean = SettingsReducer.replayAutoStart(
        base,
        operations.filter { it.deviceId == local.deviceId },
    )

    private fun DurationOperation.isValidDurationOperation(): Boolean = SettingsReducer.isValid(this)

    private fun AutoStartOperation.isValidAutoStartOperation(): Boolean =
        SettingsReducer.isValid(this) &&
            deviceId == local.deviceId &&
            parseInstant(occurredAt)

    private suspend fun fetchValidatedProfile(): User {
        val profile = try {
            auth.authorized(api::me).user
        } catch (error: SerializationException) {
            throw ProfileProtocolException("Account profile response is malformed: ${error.message.orEmpty()}")
        }
        validateUser(profile)
        return profile
    }

    private fun validateUser(value: User) {
        if (value.id.isBlank() || value.id != value.id.trim() || value.id.utf8Size() > 512 ||
            value.id.any(Char::isISOControl)
        ) {
            throw ProfileProtocolException("Account profile ID is invalid")
        }
        val at = value.email.indexOf('@')
        if (value.email != value.email.trim() || value.email.utf8Size() > 320 ||
            at <= 0 || at != value.email.lastIndexOf('@') || at == value.email.lastIndex ||
            value.email.any { it.isWhitespace() || it.isISOControl() }
        ) {
            throw ProfileProtocolException("Account profile email is invalid")
        }
        if (value.name.utf8Size() > 512 || value.name.any(Char::isISOControl)) {
            throw ProfileProtocolException("Account profile name is invalid")
        }
        if (value.avatarUrl.isNotBlank()) {
            val avatar = runCatching { URI(value.avatarUrl) }.getOrNull()
            if (value.avatarUrl.utf8Size() > 2_048 || avatar?.scheme != "https" || avatar.host.isNullOrBlank()) {
                throw ProfileProtocolException("Account profile avatar URL is invalid")
            }
        }
    }

    private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size

    private fun validateResolutionEnvelope(request: BootstrapResolutionRequest) {
        require(request.requestId.isNotBlank()) { "Saved bootstrap request ID is invalid" }
        require(request.deviceId == local.deviceId) { "Saved bootstrap device does not match this device" }
        require(request.expectedRevision in 0..SyncWireBounds.MaxSafeInteger) {
            "Saved bootstrap revision is invalid"
        }
        require(request.commands.size <= MaxBootstrapOperations) {
            "Saved bootstrap commands exceed the 4096 item limit"
        }
        require(request.taskOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap task operations exceed the 4096 item limit"
        }
        require(request.durationOperations.size <= MaxBootstrapOperations) {
            "Saved bootstrap duration operations exceed the 4096 item limit"
        }
        require(request.autoStartOperations == null ||
            request.autoStartOperations.size <= MaxBootstrapOperations
        ) { "Saved bootstrap auto-start operations exceed the 4096 item limit" }
        require(request.commands.map(TimerCommand::id).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate IDs"
        }
        require(request.commands.map(TimerCommand::deviceSequence).toSet().size == request.commands.size) {
            "Saved bootstrap commands contain duplicate device sequences"
        }
        require(request.taskOperations.map(TaskOperation::id).toSet().size == request.taskOperations.size) {
            "Saved bootstrap task operations contain duplicate IDs"
        }
        require(
            request.durationOperations.map(DurationOperation::id).toSet().size ==
                request.durationOperations.size,
        ) { "Saved bootstrap duration operations contain duplicate IDs" }
        require(
            request.autoStartOperations == null ||
                request.autoStartOperations.map(AutoStartOperation::id).toSet().size ==
                request.autoStartOperations.size,
        ) { "Saved bootstrap auto-start operations contain duplicate IDs" }
        request.commands.forEach(::validateTimerCommand)
        request.taskOperations.forEach(::validateTaskOperation)
        request.durationOperations.forEach(::validateDurationOperation)
        request.autoStartOperations?.forEach(::validateAutoStartOperation)
    }

    private fun validateTimerCommand(command: TimerCommand) {
        require(command.id.isNotBlank() && command.timerId.isNotBlank()) {
            "Saved timer command identity is invalid"
        }
        require(command.deviceSequence in 1..SyncWireBounds.MaxSafeInteger) {
            "Saved timer command sequence is invalid"
        }
        require(command.type in commandTypes) { "Saved timer command type is invalid" }
        require(command.phase in TimerPhase.all) { "Saved timer command phase is invalid" }
        require(command.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs) {
            "Saved timer command duration is invalid"
        }
        SyncWireBounds.requireOperationClock(
            command.occurredAt,
            command.hlcWallMs,
            command.hlcCounter,
            allowLegacySentinel = false,
        )
        require(command.taskId == null || command.taskId.isNotBlank()) {
            "Saved timer command task is invalid"
        }
        require(command.observedElapsedMs in 0..command.plannedDurationMs) {
            "Saved timer command elapsed time is invalid"
        }
        require(command.type != CommandType.Start || command.observedElapsedMs == 0L) {
            "Saved start command elapsed time is invalid"
        }
        require(
            command.taskId == null ||
                (isUuid(command.taskId) && command.type == CommandType.Start && command.phase == TimerPhase.Focus),
        ) { "Saved timer command task is invalid" }
    }

    private fun validateTaskOperation(operation: TaskOperation) {
        require(operation.id.isNotBlank() && operation.taskId.isNotBlank()) {
            "Saved task operation identity is invalid"
        }
        require(isUuid(operation.taskId)) { "Saved task operation task ID is invalid" }
        require(operation.type in setOf(TaskOperationType.Upsert, TaskOperationType.Delete)) {
            "Saved task operation type is invalid"
        }
        SyncWireBounds.requireOperationClock(
            operation.occurredAt,
            operation.hlcWallMs,
            operation.hlcCounter,
            allowLegacySentinel = false,
        )
        when (operation.type) {
            TaskOperationType.Upsert -> {
                val task = operation.title?.let(TaskReducer::taskFromTitle)
                require(task != null && task.id == operation.taskId) {
                    "Saved task upsert title or identity is invalid"
                }
            }
            TaskOperationType.Delete -> require(operation.title == null) {
                "Saved task delete must not contain a title"
            }
        }
    }

    private fun validateDurationOperation(operation: DurationOperation) {
        require(operation.id.isNotBlank() && operation.isValidDurationOperation()) {
            "Saved duration operation is invalid"
        }
        SyncWireBounds.requireOperationClock(
            operation.occurredAt,
            operation.hlcWallMs,
            operation.hlcCounter,
            allowLegacySentinel = true,
        )
    }

    private fun validateAutoStartOperation(operation: AutoStartOperation) {
        require(operation.isValidAutoStartOperation()) {
            "Saved auto-start operation is invalid"
        }
        SyncWireBounds.requireOperationClock(
            operation.occurredAt,
            operation.hlcWallMs,
            operation.hlcCounter,
            allowLegacySentinel = true,
        )
    }

    private suspend fun applyBootstrapResolution(
        request: BootstrapResolutionRequest,
        response: SyncResponse,
        acknowledgementResponse: SyncResponse,
        clockSample: ServerClockSample,
    ) {
        val profile = user ?: throw AuthenticationRequired()
        val retainedAutoStartOperations = if (request.autoStartOperations == null) {
            pendingAutoStartOperations
        } else {
            emptyList()
        }
        val nextKnownTasks = if (request.strategy == BootstrapStrategy.KeepRemote) {
            response.tasks.associateBy(FocusTask::id)
        } else {
            (knownTasks.values + response.tasks).associateBy(FocusTask::id)
        }
        val projectedSettings = settings.withDurations(response.durationsMs).copy(
            autoStartBreaks = replayAutoStartOperations(
                response.autoStartBreaks,
                retainedAutoStartOperations,
            ),
        )
        val generatedResolution = if (request.strategy == BootstrapStrategy.KeepRemote) {
            GeneratedCommandResolution(emptyList(), emptyList(), emptySet())
        } else {
            resolveGeneratedCommands(
                request.commands,
                acknowledgementResponse,
                response,
                projectedSettings,
            )
        }
        val retainedCommands = generatedResolution.released
        val responsePhysicalDeltaMs = responsePhysicalDelta(clockSample)
        val nextCanonicalTimer = localizedCanonicalTimer(
            response.canonicalTimer,
            request.commands,
            responsePhysicalDeltaMs,
        )
        val nextCanonicalHistory = localizedHistory(
            response.history,
            request.commands,
            responsePhysicalDeltaMs,
        )
        val (mergedWall, mergedCounter) = mergedClock(response, clockSample)
        val sampledLocal = local.copy(
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
            serverClockOffsetMs = clockSample.offsetMs,
            serverClockUncertaintyMs = clockSample.uncertaintyMs,
            serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
            serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
            serverClockBootId = bootId(),
        )
        val rebased = rebaseMutationState(
            trustedNowMs(clockSample),
            sampledLocal,
            response.serverHlcWallMs to response.serverHlcCounter,
            true,
            retainedCommands,
            emptyList(),
            emptyList(),
            retainedAutoStartOperations,
        )
        val rebasedProjection = TimerReducer.replay(
            nextCanonicalTimer,
            nextCanonicalHistory,
            rebased.commands,
        )
        val rebasedSettings = projectedSettings.copy(
            autoStartBreaks = replayAutoStartOperations(
                response.autoStartBreaks,
                rebased.autoStartOperations,
            ),
        )
        val nextSettings = rebasedSettings.copy(
            selectedPhase = reconciledSelectedPhase(
                rebasedSettings.selectedPhase,
                null,
                null,
                request.commands,
                acknowledgementResponse,
                response,
                rebasedProjection,
            ),
        )
        val nextOwnedTimerId = if (request.strategy == BootstrapStrategy.KeepRemote) {
            null
        } else {
            resolvedOwnedTimerId(rebasedProjection, generatedResolution)
        }
        val nextLocal = rebased.local.copy(
            revision = response.revision,
            canonicalTimerJson = nextCanonicalTimer?.let { json.encodeToString(it) },
            historyJson = json.encodeToString(nextCanonicalHistory),
            tasksJson = json.encodeToString(response.tasks),
            knownTasksJson = json.encodeToString(nextKnownTasks.values.sortedBy(FocusTask::id)),
            selectedTaskId = local.selectedTaskId?.takeIf { selected ->
                response.tasks.any { it.id == selected }
            },
            settingsJson = json.encodeToString(nextSettings),
            userJson = json.encodeToString(profile),
            ownerUserId = profile.id,
            canonicalAutoStartBreaks = response.autoStartBreaks,
            ownedTimerId = nextOwnedTimerId,
            serverClockOffsetMs = clockSample.offsetMs,
            serverClockUncertaintyMs = clockSample.uncertaintyMs,
            serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
            serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
            serverClockBootId = bootId(),
        )
        dao.applyBootstrapResolution(
            nextLocal,
            clearAutoStartOperations = request.autoStartOperations != null,
            retainedCommands = rebased.commands.map(PendingCommandEntity::from),
            retainedAutoStartOperations = rebased.autoStartOperations
                .map(PendingAutoStartOperationEntity::from),
        )
        installTrustedAnchor(clockSample)
        local = nextLocal
        pending = rebased.commands
        commandDependencies = emptyMap()
        pendingTaskOperations = emptyList()
        pendingDurationOperations = emptyList()
        pendingAutoStartOperations = rebased.autoStartOperations
        pendingBootstrapResolution = null
        canonicalTimer = nextCanonicalTimer
        canonicalHistory = nextCanonicalHistory
        canonicalTasks = response.tasks
        canonicalAutoStartBreaks = response.autoStartBreaks
        knownTasks = nextKnownTasks
        settings = nextSettings
        historyResolution = null
        bootstrapSnapshot = response
        bootstrapClockSample = clockSample
        syncing = false
        retrying = false
        reconcileSyncOutcomeConflict(response)
        rebuildProjections()
        publish()
        scheduleAlarm()
    }

    private fun validateCanonicalResponse(
        response: SyncResponse,
        source: String,
        requireEmptyAcknowledgements: Boolean = false,
    ) {
        protocolRequire(
            response.revision in 0..SyncWireBounds.MaxSafeInteger,
            "$source returned an invalid revision",
        )
        if (!response.durationsMs.isValid()) {
            throw SyncProtocolException("$source returned invalid canonical durations")
        }
        if (requireEmptyAcknowledgements && (
                response.acknowledgements.isNotEmpty() ||
                    response.taskAcknowledgements.isNotEmpty() ||
                    response.durationAcknowledgements.isNotEmpty()
                    || response.autoStartAcknowledgements.isNotEmpty()
                )
        ) {
            throw SyncProtocolException("$source returned acknowledgements for a read-only request")
        }
        protocolRequire(
            parseInstant(response.serverTime),
            "$source returned an invalid server timestamp",
        )
        protocolRequire(
            SyncWireBounds.isClockTuple(
                response.serverHlcWallMs,
                response.serverHlcCounter,
                allowLegacySentinel = false,
            ),
            "$source returned an invalid server clock",
        )
        response.canonicalTimer?.let { timer ->
            protocolRequire(timer.id.isNotBlank(), "$source returned an invalid timer identity")
            protocolRequire(timer.phase in TimerPhase.all, "$source returned an invalid timer phase")
            protocolRequire(timer.status in timerStatuses, "$source returned an invalid timer status")
            protocolRequire(
                timer.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
                "$source returned an invalid timer duration",
            )
            protocolRequire(
                timer.elapsedAtAnchorMs in 0..timer.plannedDurationMs,
                "$source returned invalid timer elapsed time",
            )
            protocolRequire(parseInstant(timer.anchorAt), "$source returned an invalid timer timestamp")
            timer.taskId?.let { validateCanonicalTaskId(it, source) }
            timer.lastIntent?.let { intent ->
                protocolRequire(
                    intent.type in commandTypes && intent.commandId.isNotBlank(),
                    "$source returned an invalid timer intent",
                )
                protocolRequire(
                    parseInstant(intent.occurredAt),
                    "$source returned an invalid timer intent timestamp",
                )
            }
        }
        protocolRequire(
            response.history.map(HistoryItem::id).toSet().size == response.history.size,
            "$source returned duplicate history identities",
        )
        protocolRequire(
            response.history.map(HistoryItem::timerId).toSet().size == response.history.size,
            "$source returned duplicate history timer identities",
        )
        val historyCommandIds = response.history.mapNotNull(HistoryItem::commandId)
        protocolRequire(
            historyCommandIds.toSet().size == historyCommandIds.size,
            "$source returned duplicate history command identities",
        )
        response.history.forEach { item ->
            protocolRequire(
                item.id.isNotBlank() && item.timerId.isNotBlank() &&
                    (item.commandId == null || item.commandId.isNotBlank()),
                "$source returned an invalid history identity",
            )
            protocolRequire(item.phase in TimerPhase.all, "$source returned an invalid history phase")
            protocolRequire(
                item.status in historyStatuses,
                "$source returned an invalid history status",
            )
            protocolRequire(
                item.plannedDurationMs in DurationLimits.MinMs..MaxTimerDurationMs,
                "$source returned an invalid history duration",
            )
            protocolRequire(!item.pending, "$source returned pending canonical history")
            if (item.status == TimerStatus.Completed) {
                protocolRequire(
                    item.completedAt != null && parseInstant(item.completedAt),
                    "$source returned an invalid history completion timestamp",
                )
                if (item.endedAt != null) {
                    protocolRequire(
                        parseInstant(item.endedAt),
                        "$source returned an invalid history end timestamp",
                    )
                }
            } else {
                protocolRequire(
                    item.endedAt != null && parseInstant(item.endedAt),
                    "$source returned an invalid history end timestamp",
                )
            }
            if (item.status != TimerStatus.Completed && item.completedAt != null) {
                protocolRequire(
                    parseInstant(item.completedAt),
                    "$source returned an invalid history completion timestamp",
                )
            }
            item.taskId?.let { validateCanonicalTaskId(it, source) }
        }
        protocolRequire(
            response.tasks.map(FocusTask::id).toSet().size == response.tasks.size,
            "$source returned duplicate task identities",
        )
        response.tasks.forEach { task ->
            validateCanonicalTaskId(task.id, source)
            protocolRequire(
                TaskReducer.taskFromTitle(task.title) == task,
                "$source returned an invalid canonical task",
            )
        }
        response.acknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.commandId,
                acknowledgement.outcome,
                source,
                "command",
            )
        }
        response.taskAcknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.operationId,
                acknowledgement.outcome,
                source,
                "task",
            )
        }
        response.durationAcknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.operationId,
                acknowledgement.outcome,
                source,
                "duration",
            )
        }
        response.autoStartAcknowledgements.forEach { acknowledgement ->
            validateAcknowledgement(
                acknowledgement.operationId,
                acknowledgement.outcome,
                source,
                "auto-start",
            )
        }
    }

    private fun validateCanonicalTaskId(taskId: String, source: String) {
        protocolRequire(
            isUuid(taskId),
            "$source returned an invalid task identity",
        )
    }

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private fun validateAcknowledgement(id: String, outcome: String, source: String, kind: String) {
        protocolRequire(
            id.isNotBlank() && outcome in acknowledgementOutcomes,
            "$source returned an invalid $kind acknowledgement",
        )
    }

    private fun protocolRequire(condition: Boolean, message: String) {
        if (!condition) throw SyncProtocolException(message)
    }

    private fun parseInstant(value: String): Boolean = runCatching { Instant.parse(value) }.isSuccess

    private fun mergedClock(
        response: SyncResponse,
        clockSample: ServerClockSample,
    ): Pair<Long, Long> {
        val retainedClock = retainedHlc()
        return try {
            SyncWireBounds.merge(
                nowMs = trustedNowMs(clockSample),
                localWallMs = retainedClock.first,
                localCounter = retainedClock.second,
                serverWallMs = response.serverHlcWallMs,
                serverCounter = response.serverHlcCounter,
            )
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Sync returned a hybrid clock outside trusted-time bounds")
        }
    }

    private fun reserveMutation(
        count: Int,
        withDeviceSequences: Boolean,
    ): MutationReservation? {
        val reservation = try {
            val retainedClock = retainedHlc()
            val stamps = SyncWireBounds.reserve(
                nowMs = trustedNowMs(),
                retainedWallMs = retainedClock.first,
                retainedCounter = retainedClock.second,
                retainedDeviceSequence = local.deviceSequence,
                count = count,
                withDeviceSequences = withDeviceSequences,
            )
            val previous = previousUuidV7()
            val uuids = UuidV7.reserve(
                timestampMs = stamps.first().wallMs,
                count = count,
                previous = previous,
                entropy = uuidEntropy,
            )
            MutationReservation(stamps, uuids, uuids.last().toString())
        } catch (error: IllegalArgumentException) {
            mutationFailure = error.message ?: LocalClockRangeError
            notice = mutationFailure
            publish()
            return null
        }
        if (notice == mutationFailure) notice = null
        mutationFailure = null
        return reservation
    }

    private fun previousUuidV7(): UUID? {
        val stored = local.lastUuidV7?.let(UuidV7::parse)
        val queued = (
            pending.map(TimerCommand::id) +
                pendingTaskOperations.map(TaskOperation::id) +
                pendingDurationOperations.map(DurationOperation::id) +
                pendingAutoStartOperations.map(AutoStartOperation::id)
            ).mapNotNull(UuidV7::payload)
            .maxWithOrNull(UuidV7::compare)
        require(stored == null || queued == null || UuidV7.compare(stored, queued) >= 0) {
            "Persisted UUIDv7 cursor is behind queued mutations"
        }
        return stored ?: queued
    }

    private fun validatePersistedMutationRanges() {
        SyncWireBounds.requirePersistedState(
            local.deviceSequence,
            local.hlcWallMs,
            local.hlcCounter,
        )
        validateStoredClockSample()
        require(local.revision in 0..SyncWireBounds.MaxSafeInteger) {
            "Persisted revision is invalid"
        }
        pending.forEach { command ->
            require(command.deviceSequence in 1..SyncWireBounds.MaxSafeInteger)
            SyncWireBounds.requireOperationClock(
                command.occurredAt,
                command.hlcWallMs,
                command.hlcCounter,
                allowLegacySentinel = false,
            )
            command.physicalOccurredAt?.let { physicalOccurredAt ->
                require(supportedPhysicalOccurrence(physicalOccurredAt)) {
                    "Persisted timer command physical occurrence is invalid"
                }
            }
        }
        pendingTaskOperations.forEach { operation ->
            SyncWireBounds.requireOperationClock(
                operation.occurredAt,
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = false,
            )
        }
        pendingDurationOperations.forEach { operation ->
            SyncWireBounds.requireOperationClock(
                operation.occurredAt,
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = true,
            )
        }
        pendingAutoStartOperations.forEach { operation ->
            SyncWireBounds.requireOperationClock(
                operation.occurredAt,
                operation.hlcWallMs,
                operation.hlcCounter,
                allowLegacySentinel = true,
            )
        }
        require(pending.map(TimerCommand::id).toSet().size == pending.size) {
            "Persisted timer commands contain duplicate IDs"
        }
        require(pending.map(TimerCommand::deviceSequence).toSet().size == pending.size) {
            "Persisted timer commands contain duplicate sequences"
        }
        require(pendingTaskOperations.map(TaskOperation::id).toSet().size == pendingTaskOperations.size) {
            "Persisted task operations contain duplicate IDs"
        }
        require(
            pendingDurationOperations.map(DurationOperation::id).toSet().size ==
                pendingDurationOperations.size,
        ) { "Persisted duration operations contain duplicate IDs" }
        require(
            pendingAutoStartOperations.map(AutoStartOperation::id).toSet().size ==
                pendingAutoStartOperations.size,
        ) { "Persisted auto-start operations contain duplicate IDs" }
    }

    private suspend fun repairLegacyMutationQueues() {
        val canRepairWirePayload = pendingBootstrapResolution == null
        val repairedCommands = pending.map { command ->
            val occurrenceMs = runCatching { Instant.parse(command.occurredAt).toEpochMilli() }
                .getOrNull()
            command.copy(
                hlcWallMs = occurrenceMs?.takeIf { canRepairWirePayload &&
                    command.hlcWallMs !in (it - SyncWireBounds.MaxClockSkewMs)..
                        (it + SyncWireBounds.MaxClockSkewMs)
                } ?: command.hlcWallMs,
                physicalOccurredAt = command.physicalOccurredAt
                    ?.takeIf(::supportedPhysicalOccurrence)
                    ?: command.occurredAt,
            )
        }
        val repairedTasks = pendingTaskOperations.map { operation ->
            if (!canRepairWirePayload) return@map operation
            val occurrenceMs = runCatching { Instant.parse(operation.occurredAt).toEpochMilli() }
                .getOrNull() ?: return@map operation
            if (operation.hlcWallMs in (occurrenceMs - SyncWireBounds.MaxClockSkewMs)..
                (occurrenceMs + SyncWireBounds.MaxClockSkewMs)
            ) operation else operation.copy(hlcWallMs = occurrenceMs)
        }
        val repairedDurations = pendingDurationOperations.map { operation ->
            if (!canRepairWirePayload) return@map operation
            if (operation.hlcWallMs == 0L) return@map operation
            val occurrenceMs = runCatching { Instant.parse(operation.occurredAt).toEpochMilli() }
                .getOrNull() ?: return@map operation
            if (operation.hlcWallMs in (occurrenceMs - SyncWireBounds.MaxClockSkewMs)..
                (occurrenceMs + SyncWireBounds.MaxClockSkewMs)
            ) operation else operation.copy(hlcWallMs = occurrenceMs)
        }
        val repairedAutoStart = pendingAutoStartOperations.map { operation ->
            if (!canRepairWirePayload) return@map operation
            if (operation.hlcWallMs == 0L) return@map operation
            val occurrenceMs = runCatching { Instant.parse(operation.occurredAt).toEpochMilli() }
                .getOrNull() ?: return@map operation
            if (operation.hlcWallMs in (occurrenceMs - SyncWireBounds.MaxClockSkewMs)..
                (occurrenceMs + SyncWireBounds.MaxClockSkewMs)
            ) operation else operation.copy(hlcWallMs = occurrenceMs)
        }
        val repairedClocks = repairedCommands.map { it.hlcWallMs to it.hlcCounter } +
            repairedTasks.map { it.hlcWallMs to it.hlcCounter } +
            repairedDurations.filter { it.hlcWallMs > 0 }.map { it.hlcWallMs to it.hlcCounter } +
            repairedAutoStart.filter { it.hlcWallMs > 0 }.map { it.hlcWallMs to it.hlcCounter }
        val repairedLocal = if (SyncWireBounds.isClockTuple(
                local.hlcWallMs,
                local.hlcCounter,
                allowLegacySentinel = true,
            )
        ) local else {
            val retained = repairedClocks.maxWithOrNull(
                compareBy<Pair<Long, Long>>({ it.first }, { it.second }),
            ) ?: (0L to 0L)
            local.copy(hlcWallMs = retained.first, hlcCounter = retained.second)
        }
        if (repairedLocal == local && repairedCommands == pending &&
            repairedTasks == pendingTaskOperations &&
            repairedDurations == pendingDurationOperations &&
            repairedAutoStart == pendingAutoStartOperations
        ) return
        dao.updateMutationState(
            repairedLocal,
            repairedCommands.map { command ->
                PendingCommandEntity.from(command, commandDependencies[command.id])
            },
            repairedTasks.map(PendingTaskOperationEntity::from),
            repairedDurations.map(PendingDurationOperationEntity::from),
            repairedAutoStart.map(PendingAutoStartOperationEntity::from),
        )
        local = repairedLocal
        pending = repairedCommands
        pendingTaskOperations = repairedTasks
        pendingDurationOperations = repairedDurations
        pendingAutoStartOperations = repairedAutoStart
    }

    private suspend fun invalidateStaleElapsedAnchor() {
        val persistedElapsedMs = local.serverClockSampleElapsedRealtimeMs ?: return
        val persistedBootId = local.serverClockBootId
        if (persistedBootId != null && persistedBootId == bootId() &&
            elapsedRealtimeMillis() >= persistedElapsedMs
        ) return
        local = local.copy(
            serverClockOffsetMs = null,
            serverClockUncertaintyMs = null,
            serverClockSamplePhysicalMs = null,
            serverClockSampleElapsedRealtimeMs = null,
            serverClockBootId = null,
        ).also { dao.updateState(it) }
    }

    private fun rebaseMutationState(
        trustedNowMs: Long,
        baseLocal: LocalStateEntity,
        canonicalClock: Pair<Long, Long>,
        strictlyAfterCanonical: Boolean,
        commands: List<TimerCommand>,
        taskOperations: List<TaskOperation>,
        durationOperations: List<DurationOperation>,
        autoStartOperations: List<AutoStartOperation>,
    ): RebasedMutationState {
        val uncertaintyMs = baseLocal.serverClockUncertaintyMs
            ?.coerceIn(0L, SyncWireBounds.MaxClockSkewMs)
            ?: 0L
        val effectiveSkewMs = SyncWireBounds.MaxClockSkewMs - uncertaintyMs
        val minimumMs = (trustedNowMs - effectiveSkewMs).coerceAtLeast(1L)
        val maximumMs = (trustedNowMs + effectiveSkewMs)
            .coerceAtMost(SyncWireBounds.MaxSafeInteger)
        val baseWallMs = canonicalClock.first
        val baseCounter = canonicalClock.second
        if (baseWallMs !in minimumMs..maximumMs ||
            baseCounter !in 0..SyncWireBounds.MaxSafeInteger
        ) {
            throw SyncProtocolException("Canonical server clock leaves no safe rebase headroom")
        }
        val replacements = mutableMapOf<String, Pair<Long, Long>>()
        fun rebaseDomain(clocks: List<ClockedMutation>) {
            var cursor: Pair<Long, Long>? = if (strictlyAfterCanonical) {
                baseWallMs to baseCounter
            } else {
                null
            }
            clocks.forEach { clock ->
                val followsCursor = cursor?.let { (wallMs, counter) ->
                    clock.wallMs > wallMs || clock.wallMs == wallMs && clock.counter > counter
                } ?: true
                val canRemain = clock.wallMs in minimumMs..maximumMs &&
                    clock.counter in 0..SyncWireBounds.MaxSafeInteger && followsCursor
                if (canRemain) {
                    cursor = clock.wallMs to clock.counter
                    return@forEach
                }
                val (cursorWall, cursorCounter) = cursor ?: (
                    clock.wallMs.coerceIn(minimumMs, maximumMs) to -1L
                    )
                val next = if (cursorCounter >= SyncWireBounds.MaxSafeInteger) {
                    if (cursorWall >= maximumMs) {
                        throw SyncProtocolException("Retained operation clock has no safe rebase headroom")
                    }
                    checkedTrustedTime(cursorWall, 1L) to 0L
                } else {
                    cursorWall to (cursorCounter + 1L)
                }
                replacements[clock.key] = next
                cursor = next
            }
        }
        val commandClocks = commands
            .sortedWith(compareBy(TimerCommand::deviceSequence, TimerCommand::id))
            .map { ClockedMutation("command:${it.id}", it.hlcWallMs, it.hlcCounter, it.id) }
        val taskClocks = taskOperations
            .sortedWith(compareBy(TaskOperation::hlcWallMs, TaskOperation::hlcCounter, TaskOperation::id))
            .map { ClockedMutation("task:${it.id}", it.hlcWallMs, it.hlcCounter, it.id) }
        val durationClocks = durationOperations.filter { it.hlcWallMs > 0 }
            .sortedWith(compareBy(DurationOperation::hlcWallMs, DurationOperation::hlcCounter, DurationOperation::id))
            .map { ClockedMutation("duration:${it.id}", it.hlcWallMs, it.hlcCounter, it.id) }
        val autoStartClocks = autoStartOperations.filter { it.hlcWallMs > 0 }
            .sortedWith(compareBy(AutoStartOperation::hlcWallMs, AutoStartOperation::hlcCounter, AutoStartOperation::id))
            .map { ClockedMutation("auto:${it.id}", it.hlcWallMs, it.hlcCounter, it.id) }
        val clocks = commandClocks + taskClocks + durationClocks + autoStartClocks
        rebaseDomain(commandClocks)
        rebaseDomain(taskClocks)
        rebaseDomain(durationClocks)
        rebaseDomain(autoStartClocks)
        fun clock(key: String, original: Pair<Long, Long>) = replacements[key] ?: original
        fun occurrence(original: String, nextWallMs: Long): String {
            val originalMs = runCatching { Instant.parse(original).toEpochMilli() }.getOrNull()
            val remainsValid = originalMs != null && originalMs in minimumMs..maximumMs &&
                kotlin.math.abs(nextWallMs - originalMs) <= SyncWireBounds.MaxClockSkewMs
            return if (remainsValid) original else Instant.ofEpochMilli(nextWallMs).toString()
        }
        val rebasedCommands = commands.map { command ->
            val (nextWall, nextCounter) = clock(
                "command:${command.id}",
                command.hlcWallMs to command.hlcCounter,
            )
            if (nextWall == command.hlcWallMs && nextCounter == command.hlcCounter) command else command.copy(
                occurredAt = occurrence(command.occurredAt, nextWall),
                hlcWallMs = nextWall,
                hlcCounter = nextCounter,
            )
        }
        val rebasedTasks = taskOperations.map { operation ->
            val (nextWall, nextCounter) = clock(
                "task:${operation.id}",
                operation.hlcWallMs to operation.hlcCounter,
            )
            if (nextWall == operation.hlcWallMs && nextCounter == operation.hlcCounter) operation else operation.copy(
                occurredAt = occurrence(operation.occurredAt, nextWall),
                hlcWallMs = nextWall,
                hlcCounter = nextCounter,
            )
        }
        val rebasedDurations = durationOperations.map { operation ->
            val (nextWall, nextCounter) = clock(
                "duration:${operation.id}",
                operation.hlcWallMs to operation.hlcCounter,
            )
            if (nextWall == operation.hlcWallMs && nextCounter == operation.hlcCounter) operation else operation.copy(
                occurredAt = occurrence(operation.occurredAt, nextWall),
                hlcWallMs = nextWall,
                hlcCounter = nextCounter,
            )
        }
        val rebasedAutoStart = autoStartOperations.map { operation ->
            val (nextWall, nextCounter) = clock(
                "auto:${operation.id}",
                operation.hlcWallMs to operation.hlcCounter,
            )
            if (nextWall == operation.hlcWallMs && nextCounter == operation.hlcCounter) operation else operation.copy(
                occurredAt = occurrence(operation.occurredAt, nextWall),
                hlcWallMs = nextWall,
                hlcCounter = nextCounter,
            )
        }
        val retained = (clocks.map { mutation ->
            clock(mutation.key, mutation.wallMs to mutation.counter)
        } + (baseLocal.hlcWallMs to baseLocal.hlcCounter))
            .maxWithOrNull(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))
            ?: (baseLocal.hlcWallMs to baseLocal.hlcCounter)
        return RebasedMutationState(
            baseLocal.copy(hlcWallMs = retained.first, hlcCounter = retained.second),
            rebasedCommands,
            rebasedTasks,
            rebasedDurations,
            rebasedAutoStart,
        )
    }

    private fun retainedHlc(): Pair<Long, Long> {
        val latestTrustedWallMs = sampledTrustedNowMsOrNull()?.plus(SyncWireBounds.MaxClockSkewMs)
        return (
        listOf(local.hlcWallMs to local.hlcCounter) +
            pending.map { it.hlcWallMs to it.hlcCounter } +
            pendingTaskOperations.map { it.hlcWallMs to it.hlcCounter } +
            pendingDurationOperations.filter { it.hlcWallMs > 0 }
                .map { it.hlcWallMs to it.hlcCounter } +
            pendingAutoStartOperations.filter { it.hlcWallMs > 0 }
                .map { it.hlcWallMs to it.hlcCounter }
        ).filter { latestTrustedWallMs == null || it.first <= latestTrustedWallMs }
            .maxWithOrNull(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))
            ?: (0L to 0L)
    }

    private fun serverClockSample(
        response: SyncResponse,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): ServerClockSample {
        val serverMs = validateServerClock(response)
        val timing = requestTiming(
            sentPhysicalMs,
            sentElapsedRealtimeMs,
            receivedPhysicalMs,
            receivedElapsedRealtimeMs,
        )
        val offsetMs = try {
            Math.subtractExact(serverMs, timing.midpointPhysicalMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        if (offsetMs !in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        return ServerClockSample(
            offsetMs,
            timing.uncertaintyMs,
            serverMs,
            timing.midpointPhysicalMs,
            timing.midpointElapsedRealtimeMs,
        )
    }

    private fun requestTiming(
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): RequestTiming {
        if (sentPhysicalMs !in 1..SyncWireBounds.MaxSafeInteger ||
            receivedPhysicalMs !in 1..SyncWireBounds.MaxSafeInteger ||
            sentElapsedRealtimeMs < 0L || receivedElapsedRealtimeMs < sentElapsedRealtimeMs
        ) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val roundTripMs = receivedElapsedRealtimeMs - sentElapsedRealtimeMs
        val halfRoundTripMs = try {
            Math.addExact(roundTripMs, 1L) / 2L
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val physicalDeltaMs = try {
            Math.subtractExact(receivedPhysicalMs, sentPhysicalMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val disagreementMs = try {
            val difference = Math.subtractExact(physicalDeltaMs, roundTripMs)
            if (difference == Long.MIN_VALUE) throw ArithmeticException()
            kotlin.math.abs(difference)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val uncertaintyMs = try {
            Math.addExact(halfRoundTripMs, disagreementMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        if (uncertaintyMs > MaxServerClockUncertaintyMs) {
            throw SyncProtocolException("Server clock sample uncertainty exceeds 30000ms")
        }
        val midpointPhysicalMs = checkedTimeAdd(sentPhysicalMs, physicalDeltaMs / 2L)
        val midpointElapsedRealtimeMs = checkedTimeAdd(sentElapsedRealtimeMs, roundTripMs / 2L)
        return RequestTiming(
            uncertaintyMs,
            midpointPhysicalMs,
            midpointElapsedRealtimeMs,
        )
    }

    private fun advancedBootstrapClockSample(
        sample: ServerClockSample,
        response: SyncResponse,
        sentPhysicalMs: Long,
        sentElapsedRealtimeMs: Long,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): ServerClockSample {
        validateServerClock(response)
        val timing = requestTiming(
            sentPhysicalMs,
            sentElapsedRealtimeMs,
            receivedPhysicalMs,
            receivedElapsedRealtimeMs,
        )
        if (receivedElapsedRealtimeMs < sample.midpointElapsedRealtimeMs) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
        val advancedServerMs = try {
            checkedTrustedTime(
                sample.serverTimeMs,
                receivedElapsedRealtimeMs - sample.midpointElapsedRealtimeMs,
            )
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Bootstrap clock sample is outside supported range")
        }
        val offsetMs = try {
            Math.subtractExact(advancedServerMs, receivedPhysicalMs)
        } catch (_: ArithmeticException) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        if (offsetMs !in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server clock offset is outside supported range")
        }
        return ServerClockSample(
            offsetMs = offsetMs,
            uncertaintyMs = maxOf(sample.uncertaintyMs, timing.uncertaintyMs),
            serverTimeMs = advancedServerMs,
            midpointPhysicalMs = receivedPhysicalMs,
            midpointElapsedRealtimeMs = receivedElapsedRealtimeMs,
        )
    }

    private fun validateServerClock(response: SyncResponse): Long {
        val serverMs = try {
            Instant.parse(response.serverTime).toEpochMilli()
        } catch (_: Exception) {
            throw SyncProtocolException("Server returned an invalid server timestamp")
        }
        if (serverMs !in 1..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Server timestamp is outside supported range")
        }
        try {
            SyncWireBounds.requirePhysicalSkew(serverMs, response.serverHlcWallMs)
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Server HLC disagrees with server timestamp")
        }
        return serverMs
    }

    private fun trustedNowMs(sample: ServerClockSample? = null): Long {
        val elapsedNowMs = elapsedRealtimeMillis()
        require(elapsedNowMs >= 0L) { "Elapsed time is outside supported range" }
        if (sample != null) {
            require(elapsedNowMs >= sample.midpointElapsedRealtimeMs) {
                "Elapsed time moved backwards during request"
            }
            return checkedTrustedTime(
                sample.serverTimeMs,
                elapsedNowMs - sample.midpointElapsedRealtimeMs,
            )
        }
        val anchorServerMs = trustedAnchorServerMs
        val anchorElapsedMs = trustedAnchorElapsedRealtimeMs
        if (anchorServerMs != null && anchorElapsedMs != null && elapsedNowMs >= anchorElapsedMs) {
            return checkedTrustedTime(anchorServerMs, elapsedNowMs - anchorElapsedMs)
        }
        val offsetMs = local.serverClockOffsetMs ?: return checkedTrustedTime(currentTimeMillis(), 0L)
        val persistedPhysicalMs = local.serverClockSamplePhysicalMs
        val persistedElapsedMs = local.serverClockSampleElapsedRealtimeMs
        if (persistedPhysicalMs != null && persistedElapsedMs != null &&
            elapsedNowMs >= persistedElapsedMs
        ) {
            val persistedServerMs = try {
                Math.addExact(persistedPhysicalMs, offsetMs)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Trusted time is outside supported range")
            }
            val continuedServerMs = checkedTrustedTime(
                persistedServerMs,
                elapsedNowMs - persistedElapsedMs,
            )
            trustedAnchorServerMs = continuedServerMs
            trustedAnchorElapsedRealtimeMs = elapsedNowMs
            return continuedServerMs
        }
        val restartedServerMs = try {
            Math.addExact(currentTimeMillis(), offsetMs)
        } catch (_: ArithmeticException) {
            throw IllegalArgumentException("Trusted time is outside supported range")
        }
        require(restartedServerMs in 1..SyncWireBounds.MaxSafeInteger) {
            "Trusted time is outside supported range"
        }
        trustedAnchorServerMs = restartedServerMs
        trustedAnchorElapsedRealtimeMs = elapsedNowMs
        return restartedServerMs
    }

    private fun installTrustedAnchor(sample: ServerClockSample) {
        trustedAnchorServerMs = sample.serverTimeMs
        trustedAnchorElapsedRealtimeMs = sample.midpointElapsedRealtimeMs
    }

    private fun sampledTrustedNowMsOrNull(): Long? {
        local.serverClockOffsetMs ?: return null
        return runCatching(::trustedNowMs).getOrNull()
    }

    private fun forTrustedWire(command: TimerCommand): TimerCommand =
        command.copy(physicalOccurredAt = null)

    private fun forTrustedWire(operation: TaskOperation): TaskOperation = operation

    private fun forTrustedWire(operation: DurationOperation): DurationOperation = operation

    private fun forTrustedWire(operation: AutoStartOperation): AutoStartOperation = operation

    private fun localizedCanonicalTimer(
        timer: CanonicalTimer?,
        commands: List<TimerCommand>,
        defaultDeltaMs: Long,
    ): CanonicalTimer? {
        val canonical = timer ?: return null
        val command = canonical.lastIntent?.commandId?.let { commandId ->
            commands.firstOrNull { it.id == commandId }?.let(::withPersistedPhysicalTime)
        }
        val anchorDeltaMs = anchorPhysicalDelta(canonical.id, commands)
        val existingDeltaMs = existingTimerPhysicalDelta(canonical)
        val claimedAutomaticCompletion = canonical.status == TimerStatus.Completed &&
            command?.type == CommandType.Finish &&
            instantDoesNotFollow(canonical.anchorAt, command.occurredAt)
        val deltaMs = (
            if (claimedAutomaticCompletion) {
                anchorDeltaMs ?: existingDeltaMs
            } else {
                command?.physicalDeltaMs() ?: existingDeltaMs ?: anchorDeltaMs
            }
            ) ?: defaultDeltaMs
        return canonical.copy(
            anchorAt = translatePhysicalInstant(canonical.anchorAt, deltaMs),
        )
    }

    private fun localizedHistory(
        history: List<HistoryItem>,
        commands: List<TimerCommand>,
        defaultDeltaMs: Long,
    ): List<HistoryItem> {
        val commandsById = commands.associateBy(TimerCommand::id)
        return history.map { item ->
            val terminalCommand = item.commandId?.let(commandsById::get)?.let(::withPersistedPhysicalTime)
            val claimedAutomaticCompletion = item.status == TimerStatus.Completed &&
                terminalCommand?.type == CommandType.Finish &&
                listOfNotNull(item.completedAt, item.endedAt).any {
                    instantDoesNotFollow(it, terminalCommand.occurredAt)
                }
            val existingHistoryDeltaMs = existingHistoryPhysicalDelta(item)
            val existingTimerDeltaMs = canonicalTimer
                ?.takeIf { it.id == item.timerId }
                ?.let(::existingTimerPhysicalDelta)
            val deltaMs = when {
                claimedAutomaticCompletion -> anchorPhysicalDelta(item.timerId, commands)
                    ?: existingHistoryDeltaMs
                    ?: existingTimerDeltaMs
                terminalCommand != null -> terminalCommand.physicalDeltaMs()
                    ?: existingHistoryDeltaMs
                    ?: existingTimerDeltaMs
                else -> existingHistoryDeltaMs
                    ?: existingTimerDeltaMs
                    ?: anchorPhysicalDelta(item.timerId, commands)
            } ?: defaultDeltaMs
            item.copy(
                completedAt = item.completedAt?.let { translatePhysicalInstant(it, deltaMs) },
                endedAt = item.endedAt?.let { translatePhysicalInstant(it, deltaMs) },
            )
        }
    }

    private fun withPersistedPhysicalTime(command: TimerCommand): TimerCommand =
        command.takeIf { it.physicalOccurredAt != null }
            ?: pending.firstOrNull { it.id == command.id }
            ?: command

    private fun anchorPhysicalDelta(timerId: String, commands: List<TimerCommand>): Long? = commands
        .asSequence()
        .filter { it.timerId == timerId && it.type in setOf(CommandType.Start, CommandType.Resume) }
        .sortedWith(compareBy(TimerCommand::deviceSequence, TimerCommand::id))
        .map(::withPersistedPhysicalTime)
        .mapNotNull { it.physicalDeltaMs() }
        .lastOrNull()

    private fun existingTimerPhysicalDelta(incoming: CanonicalTimer): Long? {
        val existing = canonicalTimer?.takeIf {
            it.id == incoming.id &&
                it.status == incoming.status &&
                it.lastIntent?.commandId == incoming.lastIntent?.commandId
        } ?: return null
        val physicalAnchorMs = runCatching { Instant.parse(existing.anchorAt).toEpochMilli() }.getOrNull()
            ?: return null
        val wireAnchorMs = runCatching { Instant.parse(incoming.anchorAt).toEpochMilli() }.getOrNull()
            ?: return null
        return runCatching { Math.subtractExact(physicalAnchorMs, wireAnchorMs) }.getOrNull()
    }

    private fun existingHistoryPhysicalDelta(incoming: HistoryItem): Long? {
        val existing = canonicalHistory.firstOrNull {
            it.timerId == incoming.timerId &&
                it.commandId == incoming.commandId &&
                it.status == incoming.status &&
                it.phase == incoming.phase
        } ?: return null
        val timestamps = listOf(
            existing.completedAt to incoming.completedAt,
            existing.endedAt to incoming.endedAt,
        )
        return timestamps.firstNotNullOfOrNull { (physical, wire) ->
            if (physical == null || wire == null) return@firstNotNullOfOrNull null
            val physicalMs = runCatching { Instant.parse(physical).toEpochMilli() }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            val wireMs = runCatching { Instant.parse(wire).toEpochMilli() }.getOrNull()
                ?: return@firstNotNullOfOrNull null
            runCatching { Math.subtractExact(physicalMs, wireMs) }.getOrNull()
        }
    }

    private fun instantDoesNotFollow(left: String, right: String): Boolean = runCatching {
        !Instant.parse(left).isAfter(Instant.parse(right))
    }.getOrDefault(false)

    private fun supportedPhysicalOccurrence(value: String): Boolean = runCatching {
        Instant.parse(value).toEpochMilli() in 1..SyncWireBounds.MaxSafeInteger
    }.getOrDefault(false)

    private fun bootstrapClockSampleIsStale(sample: ServerClockSample): Boolean {
        val elapsedNow = elapsedRealtimeMillis()
        if (elapsedNow < sample.midpointElapsedRealtimeMs) return true
        val maximumAgeMs = SyncWireBounds.MaxClockSkewMs - sample.uncertaintyMs
        return elapsedNow - sample.midpointElapsedRealtimeMs > maximumAgeMs
    }

    private fun TimerCommand.physicalDeltaMs(): Long? {
        val physicalMs = physicalOccurredAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
            ?: return null
        val wireMs = runCatching { Instant.parse(occurredAt).toEpochMilli() }.getOrNull() ?: return null
        return runCatching { Math.subtractExact(physicalMs, wireMs) }.getOrNull()
    }

    private fun translatePhysicalInstant(value: String, deltaMs: Long): String = try {
        Instant.ofEpochMilli(Math.addExact(Instant.parse(value).toEpochMilli(), deltaMs)).toString()
    } catch (_: Exception) {
        throw SyncProtocolException("Canonical physical timestamp is outside supported range")
    }

    private fun responsePhysicalDelta(sample: ServerClockSample): Long = try {
        Math.negateExact(sample.offsetMs)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Server clock offset is outside supported range")
    }

    private fun validateStoredClockSample() {
        val sampleValues = listOf(
            local.serverClockOffsetMs,
            local.serverClockUncertaintyMs,
            local.serverClockSamplePhysicalMs,
            local.serverClockSampleElapsedRealtimeMs,
        )
        require(sampleValues.all { it == null } || sampleValues.all { it != null }) {
            "Persisted server clock sample is incomplete"
        }
        local.serverClockOffsetMs?.let { offsetMs ->
            require(offsetMs in -SyncWireBounds.MaxSafeInteger..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock offset is invalid"
            }
        }
        local.serverClockUncertaintyMs?.let { uncertaintyMs ->
            require(uncertaintyMs in 0..MaxServerClockUncertaintyMs) {
                "Persisted server clock uncertainty is invalid"
            }
        }
        require(
            (local.serverClockSamplePhysicalMs == null) ==
                (local.serverClockSampleElapsedRealtimeMs == null),
        ) { "Persisted server clock anchor is incomplete" }
        local.serverClockSamplePhysicalMs?.let { physicalMs ->
            require(local.serverClockOffsetMs != null && physicalMs in 1..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock anchor is invalid"
            }
        }
        local.serverClockSampleElapsedRealtimeMs?.let { elapsedMs ->
            require(elapsedMs in 0..SyncWireBounds.MaxSafeInteger) {
                "Persisted server clock anchor is invalid"
            }
        }
        require(local.serverClockBootId == null || local.serverClockSampleElapsedRealtimeMs != null) {
            "Persisted server clock boot identity is invalid"
        }
    }

    private fun checkedTimeAdd(value: Long, increment: Long): Long = try {
        Math.addExact(value, increment)
    } catch (_: ArithmeticException) {
        throw SyncProtocolException("Local receipt timing is outside supported range")
    }.also {
        if (it !in 0..SyncWireBounds.MaxSafeInteger) {
            throw SyncProtocolException("Local receipt timing is outside supported range")
        }
    }

    private fun checkedTrustedTime(value: Long, increment: Long): Long = try {
        Math.addExact(value, increment)
    } catch (_: ArithmeticException) {
        throw IllegalArgumentException("Trusted time is outside supported range")
    }.also {
        require(it in 0..SyncWireBounds.MaxSafeInteger) {
            "Trusted time is outside supported range"
        }
    }

    private fun newBootstrapResolutionAttempt(request: BootstrapResolutionRequest) =
        BootstrapResolutionAttempt(
            accountGeneration = accountGeneration,
            request = request,
            sentPhysicalMs = currentTimeMillis(),
            sentElapsedRealtimeMs = elapsedRealtimeMillis(),
        )

    private fun validatePendingSyncQueues(
        commands: List<TimerCommand>,
        taskOperations: List<TaskOperation>,
        durationOperations: List<DurationOperation>,
        autoStartOperations: List<AutoStartOperation>,
    ) {
        try {
            commands.forEach(::validateTimerCommand)
        } catch (_: Exception) {
            throw SyncProtocolException("Queued timer command is invalid")
        }
        try {
            taskOperations.forEach(::validateTaskOperation)
        } catch (_: Exception) {
            throw SyncProtocolException("Queued task operation is invalid")
        }
        try {
            durationOperations.forEach(::validateDurationOperation)
        } catch (_: Exception) {
            throw SyncProtocolException("Queued duration operation is invalid")
        }
        try {
            autoStartOperations.forEach(::validateAutoStartOperation)
        } catch (_: Exception) {
            throw SyncProtocolException("Queued auto-start operation is invalid")
        }
    }

    private fun visibleHistoryCount(history: List<HistoryItem>): Int =
        history.count { it.status == TimerStatus.Completed }

    private fun hasLocalSyncState(): Boolean =
        projection.timer != null ||
            projection.history.isNotEmpty() ||
            tasks.isNotEmpty() ||
            pending.isNotEmpty() ||
            pendingTaskOperations.isNotEmpty() ||
            pendingDurationOperations.isNotEmpty() ||
            pendingAutoStartOperations.isNotEmpty() ||
            settings.effectiveDurationsMs() != DurationsMs() ||
            settings.autoStartBreaks

    private fun hasRemoteSyncState(response: SyncResponse): Boolean =
        response.canonicalTimer != null ||
            response.history.isNotEmpty() ||
            response.tasks.isNotEmpty() ||
            response.durationsMs != DurationsMs() ||
            response.autoStartBreaks

    private fun mutationsBlocked(allowWhileLoading: Boolean = false): Boolean =
        localMutationCorrupted ||
            replication?.state?.value?.transitioning == true ||
            (replicationMode() == ReplicationMode.IROH && networkState.conflict != null) ||
            historyResolution != null ||
            accountSwitch != null ||
            (!allowWhileLoading && authStatus == AuthStatus.Loading) ||
            authStatus == AuthStatus.SigningIn

    private fun PendingBootstrapResolutionEntity.toRequestStrict(): BootstrapResolutionRequest {
        val storedUser = strictJson.decodeFromString<User>(userJson).also(::validateUser)
        require(ownerUserId.isNotBlank() && storedUser.id == ownerUserId) {
            "Saved history resolution owner is invalid"
        }
        val request = BootstrapResolutionRequest(
            requestId = requestId,
            deviceId = deviceId,
            expectedRevision = expectedRevision,
            strategy = BootstrapStrategy.valueOf(strategy),
            commands = strictJson.decodeFromString(commandsJson),
            taskOperations = strictJson.decodeFromString(taskOperationsJson),
            durationOperations = strictJson.decodeFromString(durationOperationsJson),
            autoStartOperations = autoStartOperationsJson?.let {
                strictJson.decodeFromString<List<AutoStartOperation>>(it)
            },
        )
        validateResolutionEnvelope(request)
        validateResolutionQueues(
            request,
            allowLegacyFullCommandQueue = autoStartOperationsJson == null,
        )
        return request
    }

    private fun validateResolutionQueues(
        request: BootstrapResolutionRequest,
        allowLegacyFullCommandQueue: Boolean,
    ) {
        if (request.strategy == BootstrapStrategy.KeepRemote) {
            require(
                request.commands.isEmpty() &&
                    request.taskOperations.isEmpty() &&
                    request.durationOperations.isEmpty() &&
                    request.autoStartOperations.orEmpty().isEmpty(),
            ) { "Saved Keep Remote request contains local operations" }
            return
        }
        val requestedCommands = request.commands.sortedBy(TimerCommand::deviceSequence)
        val eligibleCommands = eligiblePendingCommands().map(::forTrustedWire)
            .sortedBy(TimerCommand::deviceSequence)
        val allCommands = pending.map(::forTrustedWire).sortedBy(TimerCommand::deviceSequence)
        require(
            requestedCommands == eligibleCommands ||
                allowLegacyFullCommandQueue && requestedCommands == allCommands,
        ) { "Saved bootstrap commands do not match local queues" }
        require(request.taskOperations.sortedWith(taskOperationComparator) ==
            pendingTaskOperations.map(::forTrustedWire).sortedWith(taskOperationComparator)
        ) { "Saved bootstrap task operations do not match local queues" }
        require(request.durationOperations.sortedWith(durationOperationComparator) ==
            pendingDurationOperations.map(::forTrustedWire).sortedWith(durationOperationComparator)
        ) { "Saved bootstrap duration operations do not match local queues" }
        if (request.autoStartOperations != null) {
            require(request.autoStartOperations.sortedWith(autoStartOperationComparator) ==
                pendingAutoStartOperations.map(::forTrustedWire).sortedWith(autoStartOperationComparator)
            ) { "Saved bootstrap auto-start operations do not match local queues" }
        }
    }

    private fun BootstrapResolutionRequest.toEntity(profile: User) = PendingBootstrapResolutionEntity(
        requestId = requestId,
        deviceId = deviceId,
        expectedRevision = expectedRevision,
        strategy = strategy.name,
        commandsJson = json.encodeToString(commands),
        taskOperationsJson = json.encodeToString(taskOperations),
        durationOperationsJson = json.encodeToString(durationOperations),
        autoStartOperationsJson = autoStartOperations?.let(json::encodeToString),
        ownerUserId = profile.id,
        userJson = json.encodeToString(profile),
    )

    private fun BootstrapStrategy.displayName(): String = when (this) {
        BootstrapStrategy.KeepRemote -> "Keep Remote"
        BootstrapStrategy.ReplaceRemote -> "Keep Local"
        BootstrapStrategy.Merge -> "Keep Both"
    }

    private fun currentAttemptIdentity() = RepositoryAttemptIdentity(
        accountGeneration = accountGeneration,
        requestId = pendingBootstrapResolution?.requestId,
    )

    private fun isCurrent(identity: RepositoryAttemptIdentity): Boolean =
        identity.accountGeneration == accountGeneration &&
            identity.requestId == pendingBootstrapResolution?.requestId

    private suspend fun handleAuthenticationRequired(
        identity: RepositoryAttemptIdentity,
        message: String,
    ) {
        var shouldCloseStream = false
        actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock
            auth.clear()
            accountGeneration += 1
            authStatus = AuthStatus.SignedOut
            user = null
            bootstrapSnapshot = null
            bootstrapClockSample = null
            syncing = false
            retrying = false
            restorePendingResolutionForSignedOut(message)
            publish()
            shouldCloseStream = true
        }
        if (shouldCloseStream) closeRevisionStream()
    }

    private fun corruptedResolutionState() = HistoryResolutionState(
        localHistoryCount = visibleHistoryCount(projection.history),
        remoteHistoryCount = visibleHistoryCount(bootstrapSnapshot?.history.orEmpty()),
        corrupted = true,
        recovery = ResolutionRecovery.Repreview,
        error = "Saved history resolution is corrupted. Local data and queues were preserved.",
    )

    private fun restorePendingResolutionForSignedOut(message: String) {
        historyResolution = pendingBootstrapResolution?.let { stored ->
            runCatching { stored.toRequestStrict() }.fold(
                onSuccess = { request ->
                    HistoryResolutionState(
                        localHistoryCount = visibleHistoryCount(projection.history),
                        remoteHistoryCount = 0,
                        pendingStrategy = request.strategy,
                        requestId = request.requestId,
                        error = message,
                    )
                },
                onFailure = { corruptedResolutionState() },
            )
        }
    }

    private fun publish() {
        val syncStatus = when {
            accountSwitch != null -> SyncStatus.Conflict
            historyResolution != null -> SyncStatus.Conflict
            !online -> SyncStatus.Offline
            conflict != null -> SyncStatus.Conflict
            syncing -> SyncStatus.Syncing
            retrying -> SyncStatus.Retrying
            pending.isNotEmpty() ||
                pendingTaskOperations.isNotEmpty() ||
                pendingDurationOperations.isNotEmpty() ||
                pendingAutoStartOperations.isNotEmpty() -> SyncStatus.Queued
            !initialized.isCompleted -> SyncStatus.Checking
            else -> SyncStatus.Synced
        }
        _state.value = AppState(
            ready = initialized.isCompleted,
            authStatus = authStatus,
            user = user,
            timer = projection.timer,
            history = projection.history,
            tasks = tasks,
            knownTasks = knownTasks.values.sortedWith(compareBy<FocusTask> { it.title }.thenBy { it.id }),
            taskSummaries = TaskReducer.summariesToday(tasks, projection.history),
            selectedTaskId = if (::local.isInitialized) local.selectedTaskId else null,
            settings = settings,
            pendingCount = pending.size + pendingTaskOperations.size + pendingDurationOperations.size +
                pendingAutoStartOperations.size,
            syncStatus = syncStatus,
            historyResolution = historyResolution,
            accountSwitch = accountSwitch,
            conflict = conflict,
            notice = notice,
            deviceId = if (::local.isInitialized) local.deviceId else "",
            network = networkState,
        )
    }

    private companion object {
        const val MaxCommandsPerSync = 256
        const val MaxTaskOperationsPerSync = 256
        const val MaxDurationOperationsPerSync = 256
        const val MaxAutoStartOperationsPerSync = 256
        const val MaxBootstrapOperations = 4096
        const val MaxTimerDurationMs = 14_400_000L
        const val MaxServerClockUncertaintyMs = 30_000L
        const val LocalClockRangeError = "Local clock or sequence is outside the synchronization range."
        const val LocalStateCorruptedError =
            "Persisted timer state is corrupted. Sync and local mutations are blocked."
        val commandTypes = setOf(
            CommandType.Start,
            CommandType.Pause,
            CommandType.Resume,
            CommandType.Finish,
            CommandType.Cancel,
            CommandType.Clear,
        )
        val durationOperationComparator = SettingsReducer.durationComparator
        val taskOperationComparator = compareBy<TaskOperation>(
            TaskOperation::hlcWallMs,
            TaskOperation::hlcCounter,
            TaskOperation::id,
        )
        val autoStartOperationComparator = SettingsReducer.autoStartComparator
        val activeStatuses = setOf(TimerStatus.Running, TimerStatus.Paused)
        val timerStatuses = activeStatuses + setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val historyStatuses = setOf(
            TimerStatus.Completed,
            TimerStatus.Cancelled,
            TimerStatus.Superseded,
        )
        val acknowledgementOutcomes = setOf("applied", "ignored", "rejected")

        fun readBootId(): String? = runCatching {
            File("/proc/sys/kernel/random/boot_id").readText().trim().ifBlank { null }
        }.getOrNull()
    }
}
