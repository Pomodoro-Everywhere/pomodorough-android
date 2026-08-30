package me.egigoka.pomodorough.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import java.io.File
import java.io.IOException
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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.BootstrapConflictException
import me.egigoka.pomodorough.data.api.BootstrapConflictKind
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.auth.LogoutRevocationRetryController
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceCoordinator
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.TimerDao
import me.egigoka.pomodorough.data.storage.BootstrapPreparationStorageUpdate
import me.egigoka.pomodorough.data.storage.BootstrapResolutionStorageUpdate
import me.egigoka.pomodorough.data.storage.FullSyncStorageUpdate
import me.egigoka.pomodorough.data.storage.TimerStore
import me.egigoka.pomodorough.data.time.TrustedClock
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.protocol.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
import me.egigoka.pomodorough.domain.SettingsReducer
import me.egigoka.pomodorough.domain.TaskReducer
import me.egigoka.pomodorough.domain.TimerPresentation
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import me.egigoka.pomodorough.timer.SystemTimerCompletionNotifier

import okhttp3.sse.EventSourceListener

enum class AuthStatus { Loading, SignedOut, SigningIn, SignedIn }

enum class SyncStatus { Checking, Synced, Queued, Syncing, Retrying, Offline, Conflict }

enum class AccountStatus { Available, LocalResetRequired }

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
    val localAccountResetRequired: Boolean = false,
    val completionAlertTimerId: String? = null,
) {
    /** Named reset state; Boolean remains a constructor component for source compatibility. */
    val accountStatus: AccountStatus
        get() = if (localAccountResetRequired) {
            AccountStatus.LocalResetRequired
        } else {
            AccountStatus.Available
        }
}

private data class LocalInitializationRepair(
    val shouldPersistMutationState: Boolean,
    val invalidDependentEntities: List<PendingCommandEntity>,
)

private data class TimedBootstrap(
    val response: SyncResponse,
    val clockSample: ServerClockSample,
)

private sealed interface AuthenticationCompletion {
    data object Stale : AuthenticationCompletion
    data object Complete : AuthenticationCompletion
    data class Resolve(val attempt: BootstrapResolutionAttempt) : AuthenticationCompletion
}

private data class LegacyMutationQueueRepair(
    val local: LocalStateEntity,
    val commands: List<TimerCommand>,
    val taskOperations: List<TaskOperation>,
    val durationOperations: List<DurationOperation>,
    val autoStartOperations: List<AutoStartOperation>,
)

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
    private val sharedCoreDispatch: ((String, String) -> JsonElement)? = null,
    private val replication: IrohReplicationController? = null,
    workspaceCoordinator: LocalWorkspaceCoordinator =
        (replication as? me.egigoka.pomodorough.data.iroh.IrohReplicationRepository)
            ?.workspaceCoordinator ?: LocalWorkspaceCoordinator(),
) : TimerRepositoryContract {
    private val appContext = context.applicationContext
    private val strictJson = Json(from = json) { ignoreUnknownKeys = false }
    private val timerStore by lazy {
        TimerStore(dao, json, strictJson, TimerSyncValidation::validateUser)
    }
    private val transitionCommitter by lazy {
        RepositoryTransitionCommitter.forTimerStore(timerStore)
    }
    private val trustedClock = TrustedClock(currentTimeMillis, elapsedRealtimeMillis, bootId)
    private val statePublisher = RepositoryStatePublisher()
    private val centralizedAccountSync = CentralizedAccountSyncAggregate(
        eventSink = AccountWorkspaceEventSink {},
    )
    private val accountWorkspaceController: AccountWorkspaceController
        get() = centralizedAccountSync.workspace
    private val alarmCoordinator = AlarmCoordinator(
        scheduler = TimerAlarmSchedulerPort(TimerAlarmScheduler(appContext)),
        alertStore = SharedPreferencesCompletionAlertStore(
            appContext.getSharedPreferences(CompletionAlertPreferences, Context.MODE_PRIVATE),
            CompletionAlertTimerId,
        ),
        notificationCanceller = CompletionNotificationCanceller {
            SystemTimerCompletionNotifier.cancel(appContext)
        },
        completionAlertPolicy = CompletionAlertPolicy { alertTimerId, nextTimer ->
            alertTimerId != null && (
                nextTimer == null || nextTimer.id != alertTimerId ||
                    nextTimer.status != TimerStatus.Completed
            )
        },
        eventSink = AlarmCoordinatorEventSink(::acceptAlarmCoordinatorEvent),
    )
    private val sharedCore by lazy { SharedCore.fromAssets(appContext.assets) }
    private val coreDispatch: (String, String) -> JsonElement = { operation, input ->
        sharedCoreDispatch?.invoke(operation, input) ?: sharedCore.dispatch(operation, input)
    }
    private val coreProjection by lazy {
        CoreProjectionDispatcher(coreDispatch)
    }
    private val coreCompletion by lazy { CoreCompletionDispatcher(coreDispatch) }
    private val coreHlc by lazy { CoreHlcDispatcher(coreDispatch) }
    private val mutationCoordinator by lazy {
        TimerMutationCoordinator(json, coreProjection, coreCompletion)
    }
    private val centralizedSyncCoordinator by lazy {
        CentralizedSyncCoordinator(
            json = json,
            bootstrapDispatcher = CoreBootstrapDispatcher(coreDispatch),
            reconciliationDispatcher = CoreReconciliationDispatcher(coreDispatch, coreProjection),
            projectionDispatcher = coreProjection,
            completionDispatcher = coreCompletion,
        )
    }
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + Dispatchers.IO)
    private val logoutRevocations = LogoutRevocationRetryController(auth, scope)
    private val initializeMutex = Mutex()
    private val actionMutex = workspaceCoordinator
    private val initialized = CompletableDeferred<Unit>()
    private val networkInitializationStarted = AtomicBoolean(false)
    @Volatile private var accountAdmissionResolved = false
    private val accountPublication = AccountPublicationLinearizer()
    private val connectivity = appContext.getSystemService(ConnectivityManager::class.java)

    private lateinit var local: LocalStateEntity
    private var pending = emptyList<TimerCommand>()
    private var commandDependencies = emptyMap<String, String>()
    private var pendingDurationOperations = emptyList<DurationOperation>()
    private var pendingTaskOperations = emptyList<TaskOperation>()
    private var pendingAutoStartOperations = emptyList<AutoStartOperation>()
    private var pendingSelectedTaskOperations = emptyList<SelectedTaskOperation>()
    private var pendingBootstrapResolution: PendingBootstrapResolutionEntity? = null
    private var canonicalTimer: CanonicalTimer? = null
    private var canonicalHistory = emptyList<HistoryItem>()
    private var canonicalTasks = emptyList<FocusTask>()
    private var canonicalAutoStartBreaks = false
    private var knownTasks = emptyMap<String, FocusTask>()
    private var tasks = emptyList<FocusTask>()
    private var selectedTaskId: String? = null
    private var projection = TimerProjection(null, emptyList())
    private var settings = TimerSettings()
    private var user: User?
        get() = centralizedAccountSync.user
        set(value) { centralizedAccountSync.user = value }
    private var authStatus: AuthStatus
        get() = centralizedAccountSync.authStatus
        set(value) { centralizedAccountSync.authStatus = value }
    private var syncing: Boolean
        get() = centralizedAccountSync.syncing
        set(value) { centralizedAccountSync.syncing = value }
    private var retrying: Boolean
        get() = centralizedAccountSync.retrying
        set(value) { centralizedAccountSync.retrying = value }
    private var activeSyncAttempt: SyncAttemptIdentity? = null
    private var terminalSyncError: String?
        get() = centralizedAccountSync.terminalSyncError
        set(value) { centralizedAccountSync.terminalSyncError = value }
    private var conflict: String? = null
    private var notice: String? = null
    private var historyResolution: HistoryResolutionState?
        get() = centralizedAccountSync.historyResolution
        set(value) { centralizedAccountSync.historyResolution = value }
    private var accountSwitch: AccountSwitchState?
        get() = centralizedAccountSync.accountSwitch
        set(value) { centralizedAccountSync.accountSwitch = value }
    private var localMutationCorrupted = false
    private var credentialRecoveryRequired = false
    private var mutationFailure: String? = null
    private var selectedPhaseGeneration = 0L
    private var networkState = IrohNetworkState()

    private val centralizedSyncRuntime = CentralizedSyncRuntime(
        initialized = initialized,
        initialRetryDelayMs = initialSyncRetryDelayMs,
        remoteSyncIntervalMs = remoteSyncIntervalMs,
        currentTimeMillis = currentTimeMillis,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
        initialOnline = currentOnlineState(),
        json = json,
        host = centralizedSyncRuntimeHost(),
        executeSync = { request -> auth.authorized { api.sync(it, request) } },
        openRevisionStream = { listener: EventSourceListener ->
            auth.authorized { api.revisionStream(it, listener) }
        },
    )

    private val online: Boolean get() = centralizedSyncRuntime.online
    private val foreground: Boolean get() = centralizedSyncRuntime.foreground

    internal fun requestRevisionOpen() = centralizedSyncRuntime.requestRevisionOpen()

    internal suspend fun awaitPendingRevisionSignals() =
        centralizedSyncRuntime.awaitPendingRevisionSignals()

    private val bootstrapSnapshot: SyncResponse?
        get() = accountWorkspaceController.bootstrap?.response
    private val bootstrapClockSample: ServerClockSample?
        get() = accountWorkspaceController.bootstrap?.clockSample
    private val pendingAccountSwitch: AccountSwitchCandidate?
        get() = accountWorkspaceController.accountSwitchCandidate

    override val state: StateFlow<AppState> = statePublisher.state

    init {
        replication?.let { controller ->
            scope.launch {
                controller.state.collectLatest { next ->
                    networkState = next
                    publish()
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
        if (localMutationCorrupted || mutationFailure != null) return
        if (resolveDeletionStateForInitialization()) return
        if (resolveCredentialStateForInitialization()) return
        logoutRevocations.startIfNeeded()
        accountAdmissionResolved = true
        replication?.initialize()
        if (foreground) replication?.onForeground()
        if (initializeNonCentralizedWorkspace()) return
        initializeCentralizedAccount()
    }

    private suspend fun resolveDeletionStateForInitialization(): Boolean =
        when (local.accountDeletionState) {
            AccountDeletionRemoteCommitted -> {
                recoverCommittedAccountDeletion()
                true
            }
            AccountDeletionPrepared,
            AccountLocalScrubRequired,
            -> {
                syncing = false
                retrying = false
                authStatus = AuthStatus.SignedOut
                user = null
                notice = AccountDeletionOutcomeUnknownMessage
                publish()
                initialized.complete(Unit)
                true
            }
            null -> false
            else -> {
                localMutationCorrupted = true
                conflict = LocalStateCorruptedError
                terminalSyncError = LocalStateCorruptedError
                publish()
                true
            }
        }

    private fun resolveCredentialStateForInitialization(): Boolean = when (auth.credentialState()) {
        AuthCredentialState.Unreadable -> {
            credentialRecoveryRequired = true
            authStatus = AuthStatus.SignedOut
            user = null
            notice = UnreadableCredentialMessage
            publish()
            true
        }
        AuthCredentialState.LogoutPending -> {
            authStatus = AuthStatus.SignedOut
            notice = PendingLogoutMessage
            publish()
            logoutRevocations.startIfNeeded()
            true
        }
        else -> false
    }

    private suspend fun initializeNonCentralizedWorkspace(): Boolean {
        if (replicationMode() == ReplicationMode.CENTRALIZED) return false
        reloadWorkspace(replicationMode())
        if (authStatus == AuthStatus.Loading) {
            authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
            publish()
        }
        return true
    }

    private suspend fun initializeCentralizedAccount() {
        if (!networkInitializationStarted.compareAndSet(false, true)) return
        val recoverablePendingOwner = pendingBootstrapResolution?.let { pendingResolution ->
            historyResolution?.corrupted != true &&
                pendingResolution.ownerUserId.isNotBlank() &&
                pendingResolution.userJson.isNotBlank()
        } == true
        if (local.ownerUserId == null && local.userJson == null && auth.hasTokens() && !recoverablePendingOwner) {
            runCatching(auth::clear)
            authStatus = AuthStatus.SignedOut
            user = null
            publish()
            return
        }
        if (!auth.hasTokens()) return
        restoreProfile()
        if (authStatus == AuthStatus.SignedIn && historyResolution == null && accountSwitch == null) {
            requestSync(force = true)
            if (foreground) centralizedSyncRuntime.requestRevisionOpen()
        }
    }

    private suspend fun ensureLocalInitialized() {
        if (initialized.isCompleted) return
        initializeMutex.withLock {
            if (initialized.isCompleted) return
            if (!prepareProjectionRuntime()) return@withLock
            val loaded = loadLocalInitialization() ?: return@withLock
            installLocalInitialization(loaded)
            if (local.accountDeletionState != null) {
                installQuarantinedAccountView()
                val quarantinedProjection = mutationProjection(::projectSynchronizedState)
                if (quarantinedProjection == null) {
                    finishFailedInitialization()
                    return@withLock
                }
                installCoreProjection(quarantinedProjection)
                finishLocalInitialization()
                return@withLock
            }
            val legacyRepaired = validateLoadedMutationState() ?: return@withLock
            val repair = repairLoadedMutationState(loaded, legacyRepaired)
            val initialProjection = mutationProjection(::projectSynchronizedState)
            if (initialProjection == null) {
                finishFailedInitialization()
                return@withLock
            }
            persistLocalInitializationRepair(repair)
            installCoreProjection(initialProjection)
            finishLocalInitialization()
        }
    }

    private fun prepareProjectionRuntime(): Boolean {
        val error = runCatching {
            coreProjection.apply(CoreProjectionBase(), CoreProjectionPending(), Instant.EPOCH)
        }.exceptionOrNull() ?: return true
        mutationFailure = projectionFailureMessage(error)
        notice = mutationFailure
        finishFailedInitialization()
        return false
    }

    private suspend fun loadLocalInitialization(): LocalInitializationData? = try {
        timerStore.initialize()
    } catch (error: LocalDecodingException) {
        accountWorkspaceController.setDeletionAdmissionQuarantined(
            error.local.accountDeletionState != null,
        )
        accountPublication.transition(error.local.accountDeletionState != null)
        local = error.local
        localMutationCorrupted = true
        terminalSyncError = LocalStateCorruptedError
        conflict = LocalStateCorruptedError
        historyResolution = HistoryResolutionState(0, 0, corrupted = true, error = LocalStateCorruptedError)
        alarmCoordinator.cancelForAccountClear()
        finishFailedInitialization()
        null
    }

    private fun installLocalInitialization(data: LocalInitializationData) {
        accountWorkspaceController.setDeletionAdmissionQuarantined(
            data.local.accountDeletionState != null,
        )
        accountPublication.transition(data.local.accountDeletionState != null)
        local = data.local
        pending = data.commands
        commandDependencies = data.commandDependencies
        pendingDurationOperations = data.durationOperations
        pendingTaskOperations = data.taskOperations
        pendingAutoStartOperations = data.autoStartOperations
        pendingSelectedTaskOperations = data.selectedTaskOperations
        pendingBootstrapResolution = data.bootstrapResolution
        settings = data.decoded.settings
        canonicalAutoStartBreaks = local.canonicalAutoStartBreaks
        canonicalTimer = data.decoded.canonicalTimer
        canonicalHistory = data.decoded.history
        canonicalTasks = data.decoded.tasks
        selectedTaskId = local.selectedTaskId
        knownTasks = (data.decoded.knownTasks + canonicalTasks).associateBy(FocusTask::id)
        user = data.decoded.user
    }

    private suspend fun validateLoadedMutationState(): Boolean? {
        val legacyRepair = runCatching { repairLegacyMutationQueues(persist = false) }
        if (legacyRepair.isFailure) return failCorruptMutationState(LocalClockRangeError)
        val queueError = runCatching {
            TimerSyncValidation.validatePendingQueues(pendingSyncQueues(), local.deviceId)
        }.exceptionOrNull()
        if (queueError != null) {
            return failCorruptMutationState(queueError.message ?: LocalStateCorruptedError)
        }
        val rangeError = runCatching {
            TimerSyncValidation.validatePersistedMutationRanges(local, pendingSyncQueues())
        }.exceptionOrNull()
        if (rangeError != null) return failCorruptMutationState(LocalClockRangeError)
        return legacyRepair.getOrDefault(false)
    }

    private fun failCorruptMutationState(message: String): Boolean? {
        localMutationCorrupted = true
        terminalSyncError = message
        conflict = message
        finishFailedInitialization()
        return null
    }

    private fun repairLoadedMutationState(
        data: LocalInitializationData,
        legacyRepaired: Boolean,
    ): LocalInitializationRepair {
        val invalidatedAnchor = trustedClock.invalidateStaleElapsedAnchor(local)?.let {
            local = it
            true
        } ?: false
        val pendingById = pending.associateBy(TimerCommand::id)
        val invalidEntities = data.commandEntities.filter { entity ->
            val sourceId = entity.generatedByFinishCommandId ?: return@filter false
            val source = pendingById[sourceId]
            source?.type != CommandType.Finish || entity.deviceSequence <= source.deviceSequence
        }
        discardInvalidDependentCommands(invalidEntities)
        val highestSequence = pending.maxOfOrNull(TimerCommand::deviceSequence) ?: 0L
        if (highestSequence > local.deviceSequence) local = local.copy(deviceSequence = highestSequence)
        if (local.ownerUserId == null && user != null) local = local.copy(ownerUserId = user?.id)
        val stored = data.storedLocal
        return LocalInitializationRepair(
            legacyRepaired || invalidatedAnchor || highestSequence > (stored?.deviceSequence ?: 0L) ||
                local.ownerUserId != stored?.ownerUserId,
            invalidEntities,
        )
    }

    private fun discardInvalidDependentCommands(entities: List<PendingCommandEntity>) {
        if (entities.isEmpty()) return
        val invalidIds = entities.map(PendingCommandEntity::id).toSet()
        pending = pending.filterNot { it.id in invalidIds }
        commandDependencies = commandDependencies - invalidIds
    }

    private suspend fun persistLocalInitializationRepair(repair: LocalInitializationRepair) {
        if (repair.shouldPersistMutationState) {
            timerStore.saveMutationState(local, pendingSyncQueues(), commandDependencies)
        }
        if (repair.invalidDependentEntities.isNotEmpty()) {
            timerStore.deleteCommands(repair.invalidDependentEntities)
        }
    }

    private suspend fun finishLocalInitialization() {
        val pendingMessage = "Sign in to finish the saved history choice before making more changes."
        if (local.accountDeletionState != null) {
            authStatus = AuthStatus.SignedOut
            accountAdmissionResolved = false
            initialized.complete(Unit)
            publish()
            scheduleAlarm()
            return
        }
        if (pendingBootstrapResolution != null) restorePendingResolutionForSignedOut(pendingMessage)
        clearStaleOwnedTimer()
        authStatus = if (auth.hasTokens()) AuthStatus.Loading else AuthStatus.SignedOut
        if (auth.credentialState() == AuthCredentialState.Unreadable) {
            resolveCredentialStateForInitialization()
        }
        if (authStatus == AuthStatus.SignedOut) restorePendingResolutionForSignedOut(pendingMessage)
        initialized.complete(Unit)
        publish()
        scheduleAlarm()
    }

    private fun installQuarantinedAccountView() {
        trustedClock.clear()
        alarmCoordinator.cancelForAccountClear()
        canonicalTimer = null
        canonicalHistory = emptyList()
        canonicalTasks = emptyList()
        canonicalAutoStartBreaks = false
        knownTasks = emptyMap()
        tasks = emptyList()
        selectedTaskId = null
        projection = TimerProjection(null, emptyList())
        settings = TimerSettings()
        pending = emptyList()
        commandDependencies = emptyMap()
        pendingDurationOperations = emptyList()
        pendingTaskOperations = emptyList()
        pendingAutoStartOperations = emptyList()
        pendingSelectedTaskOperations = emptyList()
        pendingBootstrapResolution = null
        user = null
        historyResolution = null
        accountSwitch = null
        syncing = false
        retrying = false
        authStatus = AuthStatus.SignedOut
        accountAdmissionResolved = false
    }

    private suspend fun clearStaleOwnedTimer() {
        if (local.ownedTimerId == null || awaitingDurableLocalCompletion() ||
            projection.timer?.takeIf { it.status in activeStatuses }?.id == local.ownedTimerId
        ) return
        local = local.copy(ownedTimerId = null)
        timerStore.saveState(local)
    }

    private fun finishFailedInitialization() {
        authStatus = AuthStatus.SignedOut
        initialized.complete(Unit)
        publish()
    }

    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) =
        accountWorkspaceController.serialize { signInInternal(credentialProvider) }

    private suspend fun signInInternal(credentialProvider: GoogleCredentialProvider) {
        if (!prepareSignIn()) return
        var identity: AccountAttemptIdentity? = null
        try {
            identity = beginSignInAttempt()
            auth.signIn(credentialProvider, local.deviceId)
            val profile = fetchValidatedProfile()
            val bootstrap = fetchTimedBootstrap()
            finishAuthenticatedSession(profile, bootstrap, identity)
        } catch (error: CancellationException) {
            cancelSignInAttempt()
            throw error
        } catch (_: AuthenticationRequired) {
            identity?.let {
                handleAuthenticationRequired(it, "Session expired during sign-in bootstrap.")
            }
        } catch (error: ProfileProtocolException) {
            failSignIn(identity, error.message, clearCredentials = true)
        } catch (error: Exception) {
            failSignIn(
                identity,
                error.message ?: appContext.getString(R.string.google_sign_in_did_not_complete),
            )
        } finally {
            accountWorkspaceController.releaseSignIn()
        }
    }

    private suspend fun prepareSignIn(): Boolean {
        initialize()
        if (actionMutex.withLock { localMutationCorrupted || accountNetworkBlocked() }) return false
        if (replicationMode() != ReplicationMode.CENTRALIZED) {
            replication?.setMode(ReplicationMode.CENTRALIZED)
            if (replicationMode() != ReplicationMode.CENTRALIZED) {
                notice = replication?.state?.value?.message
                    ?: "Could not restore centralized workspace for sign-in."
                publish()
                return false
            }
            reloadWorkspace(ReplicationMode.CENTRALIZED)
        }
        return actionMutex.withLock {
            !localMutationCorrupted && !accountNetworkBlocked() &&
                accountWorkspaceController.claimSignIn().acquired
        }
    }

    private suspend fun beginSignInAttempt(): AccountAttemptIdentity = actionMutex.withLock {
        authStatus = AuthStatus.SigningIn
        notice = null
        currentAttemptIdentity().also { publish() }
    }

    private suspend fun fetchTimedBootstrap(): TimedBootstrap {
        val sentPhysicalMs = currentTimeMillis()
        val sentElapsedRealtimeMs = elapsedRealtimeMillis()
        val response = auth.authorized(api::bootstrap)
        val receivedPhysicalMs = currentTimeMillis()
        val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
        return TimedBootstrap(
            response,
            trustedClock.sample(
                response,
                sentPhysicalMs,
                sentElapsedRealtimeMs,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            ),
        )
    }

    private suspend fun finishAuthenticatedSession(
        profile: User,
        bootstrap: TimedBootstrap,
        identity: AccountAttemptIdentity?,
    ) {
        if (!completeAuthentication(
                profile,
                bootstrap.response,
                checkNotNull(identity),
                bootstrap.clockSample,
            )
        ) {
            cancelSignInAttempt()
            return
        }
        if (historyResolution == null && accountSwitch == null) {
            requestSync(force = true)
            if (foreground) centralizedSyncRuntime.requestRevisionOpen()
        }
    }

    private suspend fun cancelSignInAttempt() = withContext(NonCancellable) {
        actionMutex.withLock {
            if (authStatus != AuthStatus.SigningIn) return@withLock
            runCatching(auth::clear)
            resetSignedOutAuthentication(notice = null)
        }
    }

    private suspend fun failSignIn(
        identity: AccountAttemptIdentity?,
        message: String?,
        clearCredentials: Boolean = false,
    ) = actionMutex.withLock {
        val attemptIdentity = identity ?: return@withLock
        if (!isCurrent(attemptIdentity)) return@withLock
        if (clearCredentials) auth.clear()
        resetSignedOutAuthentication(message)
    }

    private fun resetSignedOutAuthentication(notice: String?) {
        authStatus = AuthStatus.SignedOut
        user = null
        this.notice = notice
        restorePendingResolutionForSignedOut(
            "Sign in again to retry the exact saved history choice.",
        )
        publish()
    }

    override suspend fun logout() = accountWorkspaceController.serialize { logoutInternal() }

    override suspend fun resetLocalAccount() = accountWorkspaceController.serialize {
        resetLocalAccountInternal()
    }

    private suspend fun resetLocalAccountInternal() {
        initialize()
        if (!localAccountResetRequired()) return
        val resetGeneration = advanceAccountGeneration(
            AccountWorkspaceReason.LocalAccountResetStarted,
        )
        closeRevisionStream()
        if (!quarantineReplicationForReset()) return
        val remoteLogoutFailure = runCatching { auth.logout() }.exceptionOrNull()
        if (!clearCredentialsForReset()) return
        if (!clearReplicationForReset()) return
        commitLocalAccountReset(resetGeneration, remoteLogoutFailure)
    }

    private fun localAccountResetRequired(): Boolean =
        localMutationCorrupted || credentialRecoveryRequired ||
            local.accountDeletionState in setOf(
                AccountDeletionPrepared,
                AccountDeletionRemoteCommitted,
                AccountLocalScrubRequired,
            )

    private suspend fun quarantineReplicationForReset(): Boolean {
        val failure = runCatching { replication?.quarantineAccount() }.exceptionOrNull()
        if (failure == null) return true
        publishNotice(R.string.local_account_reset_failed_corrupt_data_was_kept)
        return false
    }

    private suspend fun clearCredentialsForReset(): Boolean {
        if (runCatching(auth::clear).exceptionOrNull() == null) return true
        publishNotice(R.string.local_account_reset_failed_credentials_were_not_cleared)
        return false
    }

    private suspend fun clearReplicationForReset(): Boolean {
        val failure = runCatching { replication?.clearAccountData() }.exceptionOrNull() ?: return true
        actionMutex.withLock {
            notice = failure.message
                ?: appContext.getString(R.string.local_account_reset_failed_corrupt_data_was_kept)
            publish()
        }
        return false
    }

    private suspend fun commitLocalAccountReset(resetGeneration: Long, remoteLogoutFailure: Throwable?) {
        try {
            actionMutex.withLock {
                if (accountWorkspaceController.generation != resetGeneration) return@withLock
                val message = appContext.getString(
                    if (remoteLogoutFailure == null) {
                        R.string.local_account_reset_complete
                    } else {
                        R.string.local_account_reset_complete_remote_logout_not_confirmed
                    },
                )
                clearPersistedAccount(
                    clearedSettings = TimerSettings(),
                    resetSequence = true,
                    nextNotice = message,
                    clearCorruption = true,
                    reason = AccountWorkspaceReason.LocalAccountResetStarted,
                )
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                notice = error.message
                    ?: appContext.getString(R.string.local_account_reset_failed_corrupt_data_was_kept)
                publish()
            }
        }
    }

    private suspend fun advanceAccountGeneration(reason: AccountWorkspaceReason): Long =
        actionMutex.withLock {
            val transition = accountWorkspaceController.advanceGeneration(reason)
            syncing = false
            retrying = false
            publish()
            transition.generation
        }

    private suspend fun publishNotice(messageId: Int) = actionMutex.withLock {
        notice = appContext.getString(messageId)
        publish()
    }

    private suspend fun persistAccountDeletionMarker(marker: String) {
        val previous = local
        val marked = previous.copy(accountDeletionState = marker)
        accountWorkspaceController.setDeletionAdmissionQuarantined(true)
        accountPublication.transition(
            quarantined = true,
            repairPublication = ::publishSnapshot,
        )
        try {
            AccountDeletionMarkerPersistence.complete(
                persist = { timerStore.saveState(marked) },
                install = { local = marked },
            )
        } catch (error: CancellationException) {
            local = marked
            accountPublication.transition(
                quarantined = true,
                repairPublication = ::publishSnapshot,
            )
            throw error
        } catch (error: Exception) {
            local = previous
            accountWorkspaceController.setDeletionAdmissionQuarantined(
                previous.accountDeletionState != null,
            )
            accountPublication.transition(
                quarantined = previous.accountDeletionState != null,
                repairPublication = ::publishSnapshot,
            )
            throw error
        }
    }

    private suspend fun logoutInternal() {
        initialize()
        if (localMutationCorrupted) return
        if (!prepareRemoteLogout()) return
        val hasOwnedAccount = beginLocalLogout()
        logoutRevocations.startPreparedLogout()
        try {
            persistLogoutScrubMarker(hasOwnedAccount)
            closeRevisionStream()
            replication?.quarantineAccount()
            replication?.clearAccountData()
            actionMutex.withLock {
                if (local.ownerUserId == null) {
                    clearUnownedSession()
                    replication?.releaseAccountQuarantine()
                    accountAdmissionResolved = true
                    publish()
                    return@withLock
                }
                val clearedSettings = settings.withDurations(DurationsMs()).copy(autoStartBreaks = false)
                clearPersistedAccount(
                    clearedSettings = clearedSettings,
                    resetSequence = false,
                    nextNotice = notice,
                    reason = AccountWorkspaceReason.LogoutStarted,
                )
            }
        } catch (error: Exception) {
            actionMutex.withLock {
                notice = error.message ?: appContext.getString(R.string.sign_out_failed_local_timer_data_was_kept)
                publish()
            }
        }
    }

    private suspend fun beginLocalLogout(): Boolean = actionMutex.withLock {
        accountWorkspaceController.advanceGeneration(AccountWorkspaceReason.LogoutStarted)
        syncing = false
        retrying = false
        authStatus = AuthStatus.SignedOut
        user = null
        accountAdmissionResolved = false
        publish()
        local.ownerUserId != null
    }

    private suspend fun persistLogoutScrubMarker(hasOwnedAccount: Boolean) {
        if (!hasOwnedAccount) return
        actionMutex.withLock {
            persistAccountDeletionMarker(AccountLocalScrubRequired)
            alarmCoordinator.cancelForAccountClear()
        }
    }

    private suspend fun prepareRemoteLogout(): Boolean {
        return try {
            auth.prepareLogout()
            true
        } catch (error: Exception) {
            actionMutex.withLock {
                notice = error.message ?: "Could not preserve remote sign-out work"
                publish()
            }
            false
        }
    }

    private fun clearUnownedSession() {
        user = null
        authStatus = AuthStatus.SignedOut
        accountWorkspaceController.clearAccountSwitch(AccountWorkspaceReason.LogoutStarted)
        accountSwitch = null
        accountWorkspaceController.clearBootstrap(AccountWorkspaceReason.LogoutStarted)
        conflict = null
        terminalSyncError = null
        restorePendingResolutionForSignedOut(
            "Sign in again to retry the exact saved history choice.",
        )
        publish()
    }

    private suspend fun clearPersistedAccount(
        clearedSettings: TimerSettings,
        resetSequence: Boolean,
        nextNotice: String?,
        clearCorruption: Boolean = false,
        reason: AccountWorkspaceReason,
    ) {
        val nextLocal = clearedLocal(clearedSettings, resetSequence)
        timerStore.clearAccount(nextLocal)
        installClearedCanonical(nextLocal, clearedSettings)
        installClearedPending()
        installClearedSession(clearCorruption, resetSequence, reason)
        accountWorkspaceController.setDeletionAdmissionQuarantined(false)
        replication?.releaseAccountQuarantine()
        accountPublication.transition(quarantined = false)
        notice = nextNotice
        alarmCoordinator.cancelForAccountClear()
        publish()
    }

    private fun clearedLocal(
        clearedSettings: TimerSettings,
        resetSequence: Boolean,
    ): LocalStateEntity {
        val cleared = local.copy(
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
            accountDeletionState = null,
        )
        return if (resetSequence) cleared.copy(
            deviceSequence = 0,
            hlcWallMs = 0,
            hlcCounter = 0,
            lastUuidV7 = null,
        ) else cleared
    }

    private fun installClearedCanonical(nextLocal: LocalStateEntity, clearedSettings: TimerSettings) {
        trustedClock.clear()
        local = nextLocal
        canonicalTimer = null
        canonicalHistory = emptyList()
        canonicalTasks = emptyList()
        canonicalAutoStartBreaks = false
        knownTasks = emptyMap()
        tasks = emptyList()
        projection = TimerProjection(null, emptyList())
        settings = clearedSettings
    }

    private fun installClearedPending() {
        pending = emptyList()
        commandDependencies = emptyMap()
        pendingDurationOperations = emptyList()
        pendingTaskOperations = emptyList()
        pendingAutoStartOperations = emptyList()
        pendingSelectedTaskOperations = emptyList()
        pendingBootstrapResolution = null
    }

    private fun installClearedSession(
        clearCorruption: Boolean,
        resetSelection: Boolean,
        reason: AccountWorkspaceReason,
    ) {
        if (clearCorruption) {
            localMutationCorrupted = false
            credentialRecoveryRequired = false
            mutationFailure = null
        }
        if (resetSelection) selectedTaskId = null
        user = null
        historyResolution = null
        accountWorkspaceController.clearAccountSwitch(reason)
        accountSwitch = null
        accountWorkspaceController.clearBootstrap(reason)
        conflict = null
        terminalSyncError = null
        authStatus = AuthStatus.SignedOut
        accountAdmissionResolved = true
    }

    override suspend fun deleteAccount(confirmation: String) =
        accountWorkspaceController.serialize { deleteAccountInternal(confirmation) }

    private suspend fun deleteAccountInternal(confirmation: String) {
        initialize()
        require(confirmation == "DELETE") { "Type DELETE exactly" }
        val deletionGeneration = actionMutex.withLock {
            val transition = accountWorkspaceController.beginDeletionAdmission()
            syncing = false
            retrying = false
            persistAccountDeletionMarker(AccountDeletionPrepared)
            alarmCoordinator.cancelForAccountClear()
            authStatus = AuthStatus.SignedOut
            user = null
            accountAdmissionResolved = false
            publish()
            transition.generation
        }
        closeRevisionStream()
        try {
            replication?.quarantineAccount()
            auth.deleteAccount(confirmation)
            actionMutex.withLock {
                if (accountWorkspaceController.generation != deletionGeneration) return@withLock
                persistAccountDeletionMarker(AccountDeletionRemoteCommitted)
            }
            auth.clear()
            scrubDeletedAccount(deletionGeneration)
        } catch (error: Exception) {
            if (local.accountDeletionState == AccountDeletionRemoteCommitted) {
                runCatching { scrubDeletedAccount(deletionGeneration) }
                return
            }
            actionMutex.withLock {
                notice = error.message ?: appContext.getString(R.string.account_deletion_failed_no_local_data_was_removed)
                publish()
            }
        }
    }

    private suspend fun recoverCommittedAccountDeletion() {
        runCatching(auth::clear)
        val deletionGeneration = accountWorkspaceController.beginDeletionAdmission().generation
        runCatching { scrubDeletedAccount(deletionGeneration) }
            .onFailure { error ->
                actionMutex.withLock {
                    notice = error.message ?: AccountDeletionRecoveryFailedMessage
                    publish()
                }
            }
    }

    private suspend fun scrubDeletedAccount(deletionGeneration: Long) {
        replication?.clearAccountData()
        actionMutex.withLock {
            if (accountWorkspaceController.generation != deletionGeneration) return@withLock
            val clearedSettings = runCatching {
                json.decodeFromString<TimerSettings>(local.settingsJson)
            }.getOrDefault(TimerSettings()).withDurations(DurationsMs()).copy(autoStartBreaks = false)
            clearPersistedAccount(
                clearedSettings,
                resetSequence = false,
                nextNotice = null,
                clearCorruption = true,
                reason = AccountWorkspaceReason.DeletionStarted,
            )
        }
    }

    override suspend fun confirmAccountSwitch() =
        accountWorkspaceController.serialize { confirmAccountSwitchInternal() }

    private suspend fun confirmAccountSwitchInternal() {
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
                installAccountSwitch(candidate)
            }
            if (foreground) centralizedSyncRuntime.requestRevisionOpen()
        } catch (error: Exception) {
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate) return@withLock
                accountSwitch = accountSwitch?.copy(
                    submitting = false,
                    error = error.message ?: appContext.getString(R.string.could_not_switch_accounts_without_risking_local_data),
                )
                publish()
            }
        }
    }

    private suspend fun installAccountSwitch(candidate: AccountSwitchCandidate) {
        TimerSyncValidation.validateUser(candidate.profile)
        TimerSyncValidation.validateCanonicalResponse(
            candidate.bootstrap,
            "Bootstrap",
            requireEmptyAcknowledgements = true,
        )
        accountWorkspaceController.advanceGeneration(AccountWorkspaceReason.AccountSwitchConfirmed)
        user = candidate.profile
        accountWorkspaceController.replaceBootstrapResponse(
            candidate.bootstrap,
            AccountWorkspaceReason.AccountSwitchConfirmed,
        )
        installBootstrap(
            candidate.profile,
            candidate.bootstrap,
            clearLocal = true,
            clockSample = candidate.clockSample,
        )
        accountWorkspaceController.clearAccountSwitch(
            AccountWorkspaceReason.AccountSwitchConfirmed,
        )
        accountSwitch = null
        authStatus = AuthStatus.SignedIn
        publish()
        scheduleAlarm()
    }

    override suspend fun cancelAccountSwitch() =
        accountWorkspaceController.serialize { cancelAccountSwitchInternal() }

    private suspend fun cancelAccountSwitchInternal() {
        initialize()
        if (localMutationCorrupted) return
        val candidate = actionMutex.withLock {
            val value = pendingAccountSwitch ?: return@withLock null
            val state = accountSwitch ?: return@withLock null
            if (state.submitting) return@withLock null
            accountWorkspaceController.advanceGeneration(
                AccountWorkspaceReason.AccountSwitchCancelled,
            )
            accountSwitch = state.copy(submitting = true, error = null)
            publish()
            value
        } ?: return

        val logoutError = runCatching { auth.logout() }.exceptionOrNull()
        auth.clear()
        actionMutex.withLock {
            if (pendingAccountSwitch !== candidate) return@withLock
            accountWorkspaceController.clearAccountSwitch(
                AccountWorkspaceReason.AccountSwitchCancelled,
            )
            accountSwitch = null
            accountWorkspaceController.clearBootstrap(
                AccountWorkspaceReason.AccountSwitchCancelled,
            )
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
            val types = mutationCoordinator.cancelAndClearTypes(current)
            if (types.isEmpty()) return@withLock
            val reservation = reserveMutation(count = types.size, withDeviceSequences = true)
                ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.cancel(
                    TimerCancelMutationInput(
                        state = timerMutationState(),
                        current = current,
                        types = types,
                        reservation = reservation,
                        physicalNowMs = currentTimeMillis(),
                    ),
                )
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositoryTimerCommandBatchTransition(mutation))
            installTimerMutation(event.plan)
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun clearTimer() {
        initialize()
        issueCommand(CommandType.Clear)
    }

    suspend fun showCompletionAlert(
        timerId: String,
        notifier: suspend () -> Boolean,
    ): Boolean {
        ensureLocalInitialized()
        return actionMutex.withLock {
            val completedTimer = projection.timer
            if (localWorkspaceAdmissionBlocked() || completedTimer?.id != timerId ||
                completedTimer.status != TimerStatus.Completed
            ) return@withLock false
            alarmCoordinator.markCompletionAlert(timerId)
            try {
                notifier().also { shown ->
                    if (!shown) alarmCoordinator.stopCompletionAlert(timerId)
                }
            } catch (error: Exception) {
                alarmCoordinator.stopCompletionAlert(timerId)
                throw error
            }
        }
    }

    fun stopCompletionAlert(timerId: String?): Boolean =
        alarmCoordinator.stopCompletionAlert(timerId).changed

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
            timerStore.saveState(local)
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
            val state = timerMutationState()
            if (!mutationCoordinator.acceptsDuration(state, phase, delta)) return@withLock
            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.duration(DurationMutationInput(state, phase, delta, reservation))
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositoryDurationTransition(mutation))
            installDurationMutation(event.plan)
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    private fun installDurationMutation(mutation: DurationMutationPlan) {
        local = mutation.local
        settings = mutation.settings
        pendingDurationOperations = mutation.operations
        installCoreProjection(mutation.projection)
        publish()
    }

    override suspend fun setAutoStart(enabled: Boolean) {
        initialize()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || settings.autoStartBreaks == enabled) return@withLock
            val state = timerMutationState()
            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.autoStart(AutoStartMutationInput(state, enabled, reservation))
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositoryAutoStartTransition(mutation))
            local = event.plan.local
            settings = event.plan.settings
            pendingAutoStartOperations = event.plan.operations
            installCoreProjection(event.plan.projection)
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
            if (taskId == selectedTaskId) return@withLock
            val state = timerMutationState()
            val reservation = reserveMutation(count = 1, withDeviceSequences = false)
                ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.selectedTask(
                    SelectedTaskMutationInput(state, taskId, reservation),
                )
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositorySelectedTaskTransition(mutation))
            local = event.plan.local
            pendingSelectedTaskOperations = event.plan.operations
            installCoreProjection(event.plan.projection)
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun addTask(title: String): Boolean {
        initialize()
        val task = taskFromSharedCore(title) ?: return false
        val existing = tasks.firstOrNull { it.id == task.id }
        if (existing != null) {
            selectTask(existing.id)
            return true
        }
        return issueTaskOperation(
            TaskOperationType.Upsert,
            task,
            select = true,
            identityValidated = true,
        )
    }

    override suspend fun deleteTask(taskId: String) {
        initialize()
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        issueTaskOperation(TaskOperationType.Delete, task)
    }

    override suspend fun finishExpiredTimer(): Boolean = finishExpiredTimer(expectedTimerId = null)

    internal suspend fun finishExpiredTimer(expectedTimerId: String?): Boolean {
        ensureLocalInitialized()
        val timer = actionMutex.withLock {
            if (localWorkspaceAdmissionBlocked()) return@withLock null
            expirableTimer()?.takeIf { expectedTimerId == null || it.id == expectedTimerId }
        } ?: return false
        replication?.initialize()
        if (replicationMode() != ReplicationMode.CENTRALIZED) reloadWorkspace(replicationMode())
        if (timer.status != TimerStatus.Running ||
            TimerPresentation.elapsedAt(timer) < timer.plannedDurationMs
        ) return false
        if (replicationMode() == ReplicationMode.IROH) return finishExpiredIrohTimer(timer)
        return finishLocalTimer(
            onlyIfExpired = true,
            allowWhileLoading = true,
            expectedTimerId = expectedTimerId,
        )
    }

    private fun expirableTimer(): CanonicalTimer? = projection.timer
        ?.takeIf { it.status in activeStatuses }
        ?: canonicalTimer?.takeIf {
            it.status == TimerStatus.Running && it.id == local.ownedTimerId && awaitingDurableLocalCompletion()
        }

    private suspend fun finishExpiredIrohTimer(timer: CanonicalTimer): Boolean = try {
        replication?.afterLocalMutation()
        reloadWorkspace(ReplicationMode.IROH)
        var saved = false
        val expired = actionMutex.withLock {
            if (localWorkspaceAdmissionBlocked()) return@withLock false
            val expiry = coreCompletion.expiry(
                CoreExpiryInput(
                    beforeTimer = timer,
                    projectedTimer = projection.timer,
                    history = projection.history,
                    selectedPhase = settings.selectedPhase,
                    autoStartBreaks = settings.autoStartBreaks,
                    localDeviceId = local.deviceId,
                    ownedTimerId = local.ownedTimerId,
                    reference = Instant.ofEpochMilli(currentTimeMillis()),
                    zoneId = java.time.ZoneId.systemDefault(),
                ),
            )
            expiry.generatedBreakPhase?.let { phase ->
                saved = commitTimerCommand(CommandType.Start, phase)
                if (!saved) return@withLock false
            }
            expiry.expired
        }
        if (saved) afterLocalMutation()
        expired
    } catch (error: Exception) {
        conflict = error.message ?: appContext.getString(R.string.iroh_room_projection_could_not_be_refreshed)
        publish()
        false
    }

    suspend fun rescheduleAlarmFromLocal() {
        ensureLocalInitialized()
        scheduleAlarm()
    }

    internal suspend fun shutdownForTest() {
        centralizedSyncRuntime.shutdown()
        repositoryJob.cancelAndJoin()
        replication?.close()
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

    override suspend fun setReplicationMode(mode: ReplicationMode) =
        accountWorkspaceController.serialize { setReplicationModeInternal(mode) }

    private suspend fun setReplicationModeInternal(mode: ReplicationMode) {
        initialize()
        if (accountNetworkBlocked()) return
        val controller = replication ?: return
        accountWorkspaceController.advanceGeneration(AccountWorkspaceReason.ReplicationModeChanged)
        controller.setMode(mode)
        reloadWorkspace(mode)
        if (mode == ReplicationMode.CENTRALIZED && authStatus == AuthStatus.SignedIn) {
            requestSync(force = true)
            if (foreground) centralizedSyncRuntime.requestRevisionOpen()
        } else {
            centralizedSyncRuntime.requestRevisionClose()
        }
    }

    override suspend fun createIrohRoom(name: String) =
        accountWorkspaceController.serialize { createIrohRoomInternal(name) }

    private suspend fun createIrohRoomInternal(name: String) {
        initialize()
        if (accountNetworkBlocked()) return
        val controller = replication ?: return
        controller.createRoom(name)
        reloadWorkspace(ReplicationMode.IROH)
        centralizedSyncRuntime.requestRevisionClose()
    }

    override suspend fun joinIrohRoom(invite: String) =
        accountWorkspaceController.serialize { joinIrohRoomInternal(invite) }

    private suspend fun joinIrohRoomInternal(invite: String) {
        initialize()
        if (accountNetworkBlocked()) return
        val controller = replication ?: return
        controller.joinRoom(invite)
        reloadWorkspace(controller.mode)
        if (controller.mode != ReplicationMode.CENTRALIZED) {
            centralizedSyncRuntime.requestRevisionClose()
        }
    }

    override suspend fun leaveIrohRoom() =
        accountWorkspaceController.serialize { leaveIrohRoomInternal() }

    private suspend fun leaveIrohRoomInternal() {
        initialize()
        if (accountNetworkBlocked()) return
        val controller = replication ?: return
        controller.leaveRoom()
        reloadWorkspace(ReplicationMode.OFFLINE)
    }

    override suspend fun refreshIrohInvite() {
        initialize()
        if (accountNetworkBlocked()) return
        replication?.refreshInvite()
    }

    override suspend fun syncIrohNow() {
        initialize()
        if (accountNetworkBlocked()) return
        replication?.syncNow()
    }

    override suspend fun resolveHistory(strategy: BootstrapStrategy) {
        initialize()
        if (localMutationCorrupted) return
        val refreshBootstrap = actionMutex.withLock {
            pendingBootstrapResolution == null && (
                bootstrapSnapshot == null || bootstrapClockSample?.let(trustedClock::isStale) != false
                )
        }
        if (refreshBootstrap && !refreshResolutionBootstrap()) return
        val attempt = actionMutex.withLock { resolutionAttempt(strategy) } ?: return
        performBootstrapResolution(attempt)
    }

    private suspend fun refreshResolutionBootstrap(): Boolean {
        val identity = actionMutex.withLock { currentAttemptIdentity() }
        val refreshed = try {
            fetchTimedBootstrap()
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(
                identity,
                "Session expired while refreshing remote history.",
            )
            return false
        } catch (error: Exception) {
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = error.message
                        ?: appContext.getString(R.string.could_not_refresh_remote_history),
                )
                publish()
            }
            return false
        }
        return actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock false
            TimerSyncValidation.validateCanonicalResponse(
                refreshed.response,
                "Bootstrap",
                requireEmptyAcknowledgements = true,
            )
            accountWorkspaceController.captureBootstrap(
                refreshed.response,
                refreshed.clockSample,
                AccountWorkspaceReason.BootstrapRefreshed,
            )
            historyResolution = (historyResolution ?: HistoryResolutionState(0, 0)).copy(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(refreshed.response.history),
                error = null,
            )
            publish()
            true
        }
    }

    private suspend fun resolutionAttempt(
        strategy: BootstrapStrategy,
    ): BootstrapResolutionAttempt? {
        if (authStatus != AuthStatus.SignedIn || historyResolution?.submitting == true) return null
        val profile = user ?: return null
        val stored = pendingBootstrapResolution
        if (stored == null) {
            return prepareBootstrapResolution(
                profile,
                strategy,
                bootstrapSnapshot ?: return null,
                bootstrapClockSample ?: return null,
            )
        }
        if (stored.ownerUserId != profile.id) return corruptPendingResolution()
        val request = try {
            stored.toRequestStrict()
        } catch (_: Exception) {
            return corruptPendingResolution()
        }
        if (request.strategy != strategy) {
            notice = appContext.getString(
                R.string.retry_pending_choice_before_another_option,
                request.strategy.displayName(),
            )
            publish()
            return null
        }
        markResolutionSubmitting(request)
        return captureBootstrapResolutionAttempt(request)
    }

    private fun corruptPendingResolution(): BootstrapResolutionAttempt? {
        historyResolution = corruptedResolutionState()
        publish()
        return null
    }

    private fun markResolutionSubmitting(request: BootstrapResolutionRequest) {
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
    }

    override suspend fun recoverCorruptedResolution() {
        initialize()
        if (localMutationCorrupted) return
        val recovery = actionMutex.withLock { prepareResolutionRecovery() } ?: return
        val (profile, identity) = recovery
        try {
            val bootstrap = fetchTimedBootstrap()
            completeAuthentication(
                profile,
                bootstrap.response,
                identity,
                bootstrap.clockSample,
                repreviewResolution = true,
            )
        } catch (_: AuthenticationRequired) {
            expireResolutionRecovery(identity)
        } catch (error: Exception) {
            failResolutionRecovery(identity, error)
        }
    }

    private suspend fun prepareResolutionRecovery(): Pair<User, AccountAttemptIdentity>? {
        val resolution = historyResolution ?: return null
        val profile = user ?: return null
        if (authStatus != AuthStatus.SignedIn ||
            resolution.recovery != ResolutionRecovery.Repreview ||
            resolution.submitting
        ) return null
        timerStore.discardBootstrapResolution()
        pendingBootstrapResolution = null
        accountWorkspaceController.beginRecovery()
        historyResolution = resolution.copy(
            submitting = true,
            error = appContext.getString(
                R.string.refreshing_account_history_without_corrupted_saved_request,
            ),
        )
        publish()
        return profile to currentAttemptIdentity()
    }

    private suspend fun expireResolutionRecovery(identity: AccountAttemptIdentity) {
        val shouldCloseStream = actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock false
            auth.clear()
            accountWorkspaceController.expireRecovery()
            authStatus = AuthStatus.SignedOut
            user = null
            syncing = false
            retrying = false
            historyResolution = HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = 0,
                corrupted = true,
                recovery = ResolutionRecovery.Repreview,
                error = appContext.getString(
                    R.string.session_expired_sign_in_again_to_recheck_history,
                ),
            )
            publish()
            true
        }
        if (shouldCloseStream) closeRevisionStream()
    }

    private suspend fun failResolutionRecovery(
        identity: AccountAttemptIdentity,
        error: Exception,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        historyResolution = historyResolution?.copy(
            submitting = false,
            error = error.message ?: appContext.getString(R.string.could_not_refresh_account_history),
        )
        publish()
    }

    private suspend fun prepareBootstrapResolution(
        profile: User,
        strategy: BootstrapStrategy,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
    ): BootstrapResolutionAttempt? {
        val transition = mutationProjection {
            centralizedSyncCoordinator.prepareBootstrapResolution(
                CentralizedBootstrapPreparationInput(
                    snapshot = centralizedSyncSnapshot(),
                    bootstrap = bootstrap,
                    strategy = strategy,
                    sampledLocal = localWithClockSample(bootstrap, clockSample),
                    projectionNow = Instant.ofEpochMilli(trustedClock.now(local, clockSample)),
                ),
            )
        } ?: return null
        return when (transition) {
            is CentralizedBootstrapPreparationTransition.Invalid -> {
                installInvalidPreparedResolution(strategy, bootstrap, transition.error)
                null
            }
            is CentralizedBootstrapPreparationTransition.Planned -> persistPreparedResolution(
                profile,
                bootstrap,
                clockSample,
                transition,
            )
        }
    }

    private fun installInvalidPreparedResolution(
        strategy: BootstrapStrategy,
        bootstrap: SyncResponse,
        error: Throwable,
    ) {
        historyResolution = HistoryResolutionState(
            localHistoryCount = visibleHistoryCount(projection.history),
            remoteHistoryCount = visibleHistoryCount(bootstrap.history),
            corrupted = true,
            recovery = ResolutionRecovery.KeepRemote.takeIf {
                strategy != BootstrapStrategy.KeepRemote
            },
            error = error.message
                ?: appContext.getString(R.string.queued_bootstrap_resolution_is_invalid),
        )
        publish()
    }

    private suspend fun persistPreparedResolution(
        profile: User,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
        plan: CentralizedBootstrapPreparationTransition.Planned,
    ): BootstrapResolutionAttempt {
        val request = plan.request
        val reconciled = plan.pending
        val resolution = request.toEntity(profile)
        val event = transitionCommitter.commit(
            RepositoryBootstrapPreparationTransition(
                update = BootstrapPreparationStorageUpdate(
                    local = reconciled.local,
                    pending = reconciled.queues,
                    commandDependencies = reconciled.dependencies,
                    resolution = resolution,
                ),
                profile = profile,
                bootstrap = bootstrap,
                clockSample = clockSample,
                plan = plan,
                resolution = resolution,
            ),
        )
        trustedClock.install(event.clockSample)
        installPending(event.plan.pending)
        pendingBootstrapResolution = event.resolution
        installCoreProjection(event.plan.projection)
        historyResolution = HistoryResolutionState(
            localHistoryCount = visibleHistoryCount(projection.history),
            remoteHistoryCount = visibleHistoryCount(event.bootstrap.history),
            pendingStrategy = request.strategy,
            requestId = request.requestId,
            submitting = true,
        )
        return captureBootstrapResolutionAttempt(request)
    }

    private suspend fun performBootstrapResolution(attempt: BootstrapResolutionAttempt) {
        val identity = AccountAttemptIdentity(
            attempt.accountGeneration,
            attempt.request.requestId,
        )
        try {
            val response = auth.authorized { api.resolveBootstrap(it, attempt.request) }
            val receivedPhysicalMs = currentTimeMillis()
            val receivedElapsedRealtimeMs = elapsedRealtimeMillis()
            val shouldSync = applyBootstrapResponse(
                attempt,
                identity,
                response,
                receivedPhysicalMs,
                receivedElapsedRealtimeMs,
            ) ?: return
            if (shouldSync) requestSync(force = true)
            if (foreground) centralizedSyncRuntime.requestRevisionOpen()
        } catch (error: BootstrapConflictException) {
            handleBootstrapConflict(identity, error)
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(
                identity,
                "Session expired. Sign in again to retry the exact saved history choice.",
            )
        } catch (error: ApiException) {
            handleResolutionApiFailure(identity, error)
        } catch (error: IOException) {
            failBootstrapResolution(
                identity,
                error.message ?: appContext.getString(
                    R.string.could_not_finish_history_resolution_retry_same_request,
                ),
            )
        } catch (error: Exception) {
            failBootstrapResolution(
                identity,
                error.message ?: appContext.getString(
                    R.string.history_resolution_failed_without_changing_local_data,
                ),
            )
        }
    }

    private suspend fun applyBootstrapResponse(
        attempt: BootstrapResolutionAttempt,
        identity: AccountAttemptIdentity,
        response: SyncResponse,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ): Boolean? = actionMutex.withLock {
        if (!isCurrent(identity) || authStatus != AuthStatus.SignedIn) return@withLock null
        TimerSyncValidation.validateCanonicalResponse(response, "Bootstrap resolution")
        trustedClock.validate(response)
        val canonicalResponse = centralizedSyncCoordinator.canonicalBootstrapResponse(
            bootstrapSnapshot,
            response,
        )
        TimerSyncValidation.validateCanonicalResponse(
            canonicalResponse,
            "Bootstrap resolution canonical state",
        )
        val clockSample = trustedClock.advance(
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
        ) throw SyncProtocolException("Bootstrap resolution returned a regressed revision")
        applyBootstrapResolution(attempt.request, canonicalResponse, response, clockSample)
        pendingAutoStartOperations.isNotEmpty() ||
            pendingSelectedTaskOperations.isNotEmpty() ||
            eligiblePendingCommands().isNotEmpty()
    }

    private suspend fun handleBootstrapConflict(
        identity: AccountAttemptIdentity,
        error: BootstrapConflictException,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        timerStore.discardBootstrapResolution()
        pendingBootstrapResolution = null
        accountWorkspaceController.clearBootstrap(AccountWorkspaceReason.BootstrapInvalidated)
        val detail = when (error.kind) {
            BootstrapConflictKind.Revision ->
                appContext.getString(R.string.remote_history_changed_choose_again)
            BootstrapConflictKind.RequestId ->
                appContext.getString(R.string.server_rejected_saved_request_identity)
            BootstrapConflictKind.Unknown -> error.message
                ?: appContext.getString(R.string.history_resolution_conflicted_choose_again)
        }
        historyResolution = historyResolution?.copy(
            pendingStrategy = null,
            requestId = null,
            submitting = false,
            error = detail,
        )
        publish()
    }

    private suspend fun handleResolutionApiFailure(
        identity: AccountAttemptIdentity,
        error: ApiException,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        if (error.isRetryable()) {
            historyResolution = historyResolution?.copy(
                submitting = false,
                error = error.message
                    ?: "Could not finish history resolution. Retry uses the same saved request.",
            )
        } else {
            timerStore.discardBootstrapResolution()
            pendingBootstrapResolution = null
            accountWorkspaceController.clearBootstrap(AccountWorkspaceReason.BootstrapInvalidated)
            historyResolution = corruptedResolutionState().copy(
                error = appContext.getString(
                    R.string.server_permanently_rejected_saved_history_request,
                    error.statusCode,
                ),
            )
        }
        publish()
    }

    private suspend fun failBootstrapResolution(
        identity: AccountAttemptIdentity,
        message: String,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        historyResolution = historyResolution?.copy(submitting = false, error = message)
        publish()
    }

    override fun onForeground() {
        centralizedSyncRuntime.markForeground(true)
        val accountQuarantined = accountNetworkBlocked()
        if (!accountQuarantined) replication?.onForeground()
        if (!accountQuarantined && replicationMode() == ReplicationMode.CENTRALIZED) {
            centralizedSyncRuntime.resumeForeground()
        }
    }

    override fun onBackground() {
        centralizedSyncRuntime.markForeground(false)
        replication?.onBackground()
        centralizedSyncRuntime.resumeBackground()
    }

    private suspend fun restoreProfile() {
        val identity = actionMutex.withLock { currentAttemptIdentity() }
        try {
            val profile = fetchValidatedProfile()
            val bootstrap = fetchTimedBootstrap()
            completeAuthentication(profile, bootstrap.response, identity, bootstrap.clockSample)
        } catch (error: IOException) {
            failProfileRestore(
                identity,
                error.message ?: appContext.getString(R.string.could_not_verify_signed_in_account),
            )
        } catch (_: AuthenticationRequired) {
            handleAuthenticationRequired(identity, "Session expired while refreshing account bootstrap.")
        } catch (error: ProfileProtocolException) {
            failProfileRestore(identity, error.message, clearCredentials = true)
        } catch (error: Exception) {
            failProfileRestore(
                identity,
                error.message ?: appContext.getString(R.string.could_not_validate_account_bootstrap),
            )
        }
    }

    private suspend fun failProfileRestore(
        identity: AccountAttemptIdentity,
        message: String?,
        clearCredentials: Boolean = false,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        if (clearCredentials) auth.clear()
        resetSignedOutAuthentication(message)
    }

    private suspend fun completeAuthentication(
        profile: User,
        bootstrap: SyncResponse,
        identity: AccountAttemptIdentity,
        clockSample: ServerClockSample,
        repreviewResolution: Boolean = false,
    ): Boolean {
        val completion = actionMutex.withLock {
            prepareAuthenticationCompletion(
                profile,
                bootstrap,
                identity,
                clockSample,
                repreviewResolution,
            )
        }
        return when (completion) {
            AuthenticationCompletion.Stale -> false
            AuthenticationCompletion.Complete -> true
            is AuthenticationCompletion.Resolve -> {
                performBootstrapResolution(completion.attempt)
                true
            }
        }
    }

    private suspend fun prepareAuthenticationCompletion(
        profile: User,
        bootstrap: SyncResponse,
        identity: AccountAttemptIdentity,
        clockSample: ServerClockSample,
        repreviewResolution: Boolean,
    ): AuthenticationCompletion {
        if (!isCurrent(identity) || localMutationCorrupted || accountNetworkBlocked()) {
            return AuthenticationCompletion.Stale
        }
        TimerSyncValidation.validateUser(profile)
        TimerSyncValidation.validateCanonicalResponse(
            bootstrap,
            "Bootstrap",
            requireEmptyAcknowledgements = true,
        )
        val storedResolution = pendingBootstrapResolution
        val boundOwnerId = local.ownerUserId ?: storedResolution?.ownerUserId
        if (boundOwnerId != null && boundOwnerId != profile.id) {
            installPendingAccountSwitch(profile, bootstrap, clockSample, boundOwnerId)
            return AuthenticationCompletion.Complete
        }
        val plan = authenticationBootstrapPlan(
            profile,
            bootstrap,
            storedResolution,
            boundOwnerId,
            repreviewResolution,
        )
        installAuthenticatedProfile(profile, bootstrap, clockSample)
        val attempt = applyAuthenticationPlan(
            profile,
            bootstrap,
            clockSample,
            storedResolution,
            plan,
        )
        authStatus = AuthStatus.SignedIn
        publish()
        scheduleAlarm()
        return attempt?.let(AuthenticationCompletion::Resolve)
            ?: AuthenticationCompletion.Complete
    }

    private fun authenticationBootstrapPlan(
        profile: User,
        bootstrap: SyncResponse,
        storedResolution: PendingBootstrapResolutionEntity?,
        boundOwnerId: String?,
        repreviewResolution: Boolean,
    ): CoreBootstrapPlan? = if (storedResolution == null) {
        centralizedSyncCoordinator.bootstrapPlan(
            CentralizedBootstrapPlanningInput(
                localOwnerId = boundOwnerId?.takeUnless {
                    repreviewResolution && it == profile.id
                },
                currentUserId = profile.id,
                localHistory = projection.history,
                remoteHistory = bootstrap.history,
                hasLocalState = hasLocalSyncState(),
                hasRemoteState = hasRemoteSyncState(bootstrap),
            ),
        )
    } else {
        null
    }

    private fun installPendingAccountSwitch(
        profile: User,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
        boundOwnerId: String,
    ) {
        accountWorkspaceController.captureAccountSwitch(profile, bootstrap, clockSample)
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
    }

    private fun installAuthenticatedProfile(
        profile: User,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
    ) {
        accountWorkspaceController.completeAuthentication(bootstrap, clockSample)
        user = profile
        accountWorkspaceController.clearAccountSwitch(
            AccountWorkspaceReason.AuthenticationCompleted,
        )
        accountSwitch = null
        conflict = null
        terminalSyncError = null
    }

    private suspend fun applyAuthenticationPlan(
        profile: User,
        bootstrap: SyncResponse,
        clockSample: ServerClockSample,
        storedResolution: PendingBootstrapResolutionEntity?,
        plan: CoreBootstrapPlan?,
    ): BootstrapResolutionAttempt? {
        when {
            storedResolution != null -> restoreStoredResolution(storedResolution, bootstrap)
            plan == CoreBootstrapPlan.NormalSync ->
                installBootstrap(profile, bootstrap, clearLocal = false, clockSample)
            plan is CoreBootstrapPlan.Choose -> {
                historyResolution = HistoryResolutionState(
                    localHistoryCount = plan.localHistoryCount,
                    remoteHistoryCount = plan.remoteHistoryCount,
                )
            }
            plan is CoreBootstrapPlan.Automatic -> return prepareBootstrapResolution(
                profile,
                plan.strategy,
                bootstrap,
                clockSample,
            )
            else -> throw SyncProtocolException("Shared Core bootstrap plan is unavailable")
        }
        return null
    }

    private fun restoreStoredResolution(
        stored: PendingBootstrapResolutionEntity,
        bootstrap: SyncResponse,
    ) {
        historyResolution = try {
            val request = stored.toRequestStrict()
            HistoryResolutionState(
                localHistoryCount = visibleHistoryCount(projection.history),
                remoteHistoryCount = visibleHistoryCount(bootstrap.history),
                pendingStrategy = request.strategy,
                requestId = request.requestId,
                error = appContext.getString(
                    R.string.previous_history_choice_still_needs_server_response,
                ),
            )
        } catch (_: Exception) {
            corruptedResolutionState()
        }
    }

    private suspend fun installBootstrap(
        profile: User,
        response: SyncResponse,
        clearLocal: Boolean,
        clockSample: ServerClockSample,
    ) {
        val snapshot = centralizedSyncSnapshot()
        val commands = snapshot.queues.commands.takeUnless { clearLocal }.orEmpty()
        val delta = trustedClock.responsePhysicalDelta(clockSample)
        val application = centralizedSyncCoordinator.applyBootstrapInstallation(
            CentralizedBootstrapInstallationInput(
                snapshot = snapshot,
                profile = profile,
                response = response,
                clearLocal = clearLocal,
                sampledLocal = localWithClockSample(response, clockSample),
                localizedTimer = localizedCanonicalTimer(response.canonicalTimer, commands, delta),
                localizedHistory = localizedHistory(response.history, commands, delta),
                projectionNow = Instant.ofEpochMilli(trustedClock.now(local, clockSample)),
            ),
        )
        val event = transitionCommitter.commit(
            RepositoryBootstrapInstallationTransition(
                application = application,
                response = response,
                clearLocal = clearLocal,
                clockSample = clockSample,
            ),
        )
        installBootstrapState(
            event.application,
            event.response,
            event.clearLocal,
            event.clockSample,
        )
    }

    private fun installBootstrapState(
        application: CentralizedSyncApplication,
        response: SyncResponse,
        clearLocal: Boolean,
        clockSample: ServerClockSample,
    ) {
        trustedClock.install(clockSample)
        local = application.local
        canonicalTimer = application.canonical.timer
        canonicalHistory = application.canonical.history
        canonicalTasks = application.canonical.tasks
        canonicalAutoStartBreaks = response.autoStartBreaks
        knownTasks = application.canonical.knownTasks
        if (clearLocal) {
            installEmptyPending()
            pendingBootstrapResolution = null
        } else {
            installPending(application.pending)
            local = application.local
        }
        historyResolution = null
        settings = application.projected.settings
        installCoreProjection(application.projected.projection)
    }

    private suspend fun finishLocalTimer(
        onlyIfExpired: Boolean,
        allowWhileLoading: Boolean,
        expectedTimerId: String? = null,
    ): Boolean {
        var saved = false
        actionMutex.withLock {
            if (localWorkspaceAdmissionBlocked(allowWhileLoading)) return@withLock
            val current = (if (onlyIfExpired) expirableTimer() else projection.timer) ?: return@withLock
            if (expectedTimerId != null && current.id != expectedTimerId) return@withLock
            if (!canFinishTimer(current, onlyIfExpired)) return@withLock
            val completionRequest = coreCompletion.commandRequest(
                CoreCommandRequestInput(
                    commandType = CommandType.Finish,
                    requestedTimer = current,
                    projectedTimer = projection.timer,
                    automatic = onlyIfExpired,
                    generateAutoBreak = true,
                    autoStartBreaks = settings.autoStartBreaks,
                    localDeviceId = local.deviceId,
                    ownedTimerId = local.ownedTimerId,
                ),
            )
            if (!completionRequest.eligible) return@withLock
            val reservation = reserveMutation(
                count = if (completionRequest.reserveGeneratedBreak) 2 else 1,
                withDeviceSequences = true,
            ) ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.finish(
                    TimerFinishMutationInput(
                        state = timerMutationState(),
                        current = current,
                        completionRequest = completionRequest,
                        reservation = reservation,
                        physicalNowMs = currentTimeMillis(),
                    ),
                )
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositoryTimerCommandBatchTransition(mutation))
            installTimerMutation(event.plan)
            saved = true
        }
        if (saved) afterLocalMutation()
        return saved
    }

    private fun canFinishTimer(current: CanonicalTimer, onlyIfExpired: Boolean): Boolean {
        if (current.status !in activeStatuses) return false
        if (!onlyIfExpired) return true
        return current.id == local.ownedTimerId &&
            current.status == TimerStatus.Running &&
            TimerPresentation.elapsedAt(current) >= current.plannedDurationMs
    }

    private suspend fun issueCommand(type: String, startingPhase: String? = null): Boolean {
        val saved = actionMutex.withLock { commitTimerCommand(type, startingPhase) }
        if (saved) afterLocalMutation()
        return saved
    }

    private suspend fun commitTimerCommand(type: String, startingPhase: String?): Boolean {
        if (mutationsBlocked()) return false
        val state = timerMutationState()
        if (!mutationCoordinator.acceptsCommand(state, type)) return false
        val reservation = reserveMutation(count = 1, withDeviceSequences = true) ?: return false
        val mutation = plannedMutation {
            mutationCoordinator.command(
                TimerCommandMutationInput(
                    state = state,
                    type = type,
                    startingPhase = startingPhase,
                    reservation = reservation,
                    physicalNowMs = currentTimeMillis(),
                ),
            )
        } ?: return false
        val event = transitionCommitter.commit(RepositoryTimerCommandTransition(mutation))
        installTimerMutation(event.plan)
        return true
    }

    private fun installTimerMutation(mutation: TimerCommandMutationPlan) {
        local = mutation.local
        if (mutation.settings.selectedPhase != settings.selectedPhase) selectedPhaseGeneration += 1L
        settings = mutation.settings
        pending = pending + mutation.commands
        commandDependencies = commandDependencies + mutation.dependencies
        installCoreProjection(mutation.projection)
        publish()
        scheduleAlarm()
    }

    private suspend fun issueTaskOperation(
        type: String,
        task: FocusTask,
        select: Boolean = false,
        identityValidated: Boolean = false,
    ): Boolean {
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked() || select && projection.timer?.status in activeStatuses) return@withLock
            val authoritativeTask = authoritativeTask(task, identityValidated) ?: return@withLock
            val state = timerMutationState()
            val changesSelection = select || type == TaskOperationType.Delete &&
                selectedTaskId == authoritativeTask.id
            val reservation = reserveMutation(
                count = if (changesSelection) 2 else 1,
                withDeviceSequences = false,
            ) ?: return@withLock
            val mutation = plannedMutation {
                mutationCoordinator.task(
                    TaskMutationInput(state, type, authoritativeTask, select, reservation),
                )
            } ?: return@withLock
            val event = transitionCommitter.commit(RepositoryTaskTransition(mutation))
            installTaskMutation(event.plan)
            saved = true
        }
        if (saved) afterLocalMutation()
        return saved
    }

    private fun authoritativeTask(task: FocusTask, identityValidated: Boolean): FocusTask? {
        val authoritative = if (identityValidated) task else taskFromSharedCore(task.title) ?: return null
        if (authoritative.id == task.id) return authoritative
        notice = appContext.getString(R.string.shared_core_invalid_output)
        publish()
        return null
    }

    private fun installTaskMutation(mutation: TaskMutationPlan) {
        local = mutation.local
        knownTasks = mutation.knownTasks
        pendingTaskOperations = mutation.taskOperations
        pendingSelectedTaskOperations = mutation.selectedTaskOperations
        installCoreProjection(mutation.projection)
        publish()
    }

    private fun taskFromSharedCore(title: String): FocusTask? {
        val value = try {
            val input = json.encodeToString(mapOf("title" to title))
            sharedCoreDispatch?.invoke("task.identity.v1", input)
                ?: sharedCore.dispatch("task.identity.v1", input)
        } catch (error: SharedCoreException.Operation) {
            notice = appContext.getString(
                R.string.task_must_contain_printable_text_and_fit_within_512_bytes,
            )
            publish()
            return null
        } catch (error: SharedCoreException) {
            notice = appContext.getString(R.string.shared_core_unavailable)
            publish()
            return null
        }
        val identity = runCatching {
            val output = value.jsonObject
            Triple(
                output["id"]?.jsonPrimitive?.contentOrNull,
                output["title"]?.jsonPrimitive?.contentOrNull,
                output["utf8Bytes"]?.jsonPrimitive?.intOrNull,
            )
        }.getOrNull()
        val id = identity?.first
        val normalizedTitle = identity?.second
        val utf8Bytes = identity?.third
        if (
            id.isNullOrEmpty() || normalizedTitle.isNullOrEmpty() ||
            utf8Bytes == null ||
            utf8Bytes != normalizedTitle.toByteArray(StandardCharsets.UTF_8).size ||
            utf8Bytes > 512
        ) {
            notice = appContext.getString(R.string.shared_core_invalid_output)
            publish()
            return null
        }
        return FocusTask(id, normalizedTitle)
    }

    private fun centralizedSyncRuntimeHost() = object : CentralizedSyncRuntimeHost {
        override fun snapshot(): CentralizedSyncRuntimeSnapshot = centralizedAccountSync.runtimeSnapshot(
            centralized = replicationMode() == ReplicationMode.CENTRALIZED,
            pendingQueuesEmpty = pendingQueuesEmpty(),
            localRevision = if (::local.isInitialized) local.revision else 0L,
        )

        override fun accountGeneration(): Long = accountWorkspaceController.generation

        override fun revisionStreamAdmission(): RevisionStreamAdmission {
            val admission = accountWorkspaceController.admissionSnapshot
            val state = snapshot()
            return RevisionStreamAdmission(
                accountGeneration = admission.generation,
                eligible = !admission.deletionQuarantined && state.signedIn && state.centralized &&
                    !state.resolutionPending && !state.accountSwitchPending,
            )
        }

        override suspend fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt? =
            actionMutex.withLock { this@TimerRepository.prepareSyncAttempt(identity) }

        override suspend fun accept(event: CentralizedSyncRuntimeEvent) {
            acceptCentralizedSyncRuntimeEvent(event)
        }
    }

    private suspend fun acceptCentralizedSyncRuntimeEvent(event: CentralizedSyncRuntimeEvent) {
        when (event) {
            is CentralizedSyncRuntimeEvent.Paused -> actionMutex.withLock {
                if (event.accountGeneration == accountWorkspaceController.generation) {
                    acceptSyncPause(event.reason)
                }
            }
            is CentralizedSyncRuntimeEvent.SyncResponseReady -> actionMutex.withLock {
                if (currentSyncAttempt(event.attempt.identity)) {
                    applySyncResponse(
                        event.attempt,
                        event.response,
                        event.receivedPhysicalMs,
                        event.receivedElapsedRealtimeMs,
                    )
                }
            }
            is CentralizedSyncRuntimeEvent.AuthenticationExpired -> expireSyncAuthentication(event.identity)
            is CentralizedSyncRuntimeEvent.RevisionAuthenticationExpired ->
                expireRevisionAuthentication(event.accountGeneration)
            is CentralizedSyncRuntimeEvent.TerminalFailure -> actionMutex.withLock {
                if (currentSyncAttempt(event.identity)) {
                    activeSyncAttempt = null
                    markTerminalSyncError(terminalSyncFailureMessage(event.error))
                }
            }
            is CentralizedSyncRuntimeEvent.Retrying -> actionMutex.withLock {
                if (currentSyncAttempt(event.identity)) {
                    syncing = false
                    retrying = true
                    publish()
                }
            }
            is CentralizedSyncRuntimeEvent.LocalFailure -> actionMutex.withLock {
                if (currentSyncAttempt(event.identity)) {
                    activeSyncAttempt = null
                    syncing = false
                    retrying = false
                    notice = event.error.message
                        ?: appContext.getString(R.string.sync_stopped_after_a_local_failure)
                    publish()
                }
            }
        }
    }

    private fun acceptSyncPause(reason: SyncPauseReason) {
        when (reason) {
            SyncPauseReason.WorkspaceTransition -> {
                syncing = false
                retrying = false
            }
            SyncPauseReason.Unavailable -> Unit
            SyncPauseReason.NoPending -> retrying = false
        }
        publish()
    }

    private suspend fun expireSyncAuthentication(identity: SyncAttemptIdentity) = actionMutex.withLock {
        if (!currentSyncAttempt(identity)) return@withLock
        activeSyncAttempt = null
        accountWorkspaceController.expireAuthentication(
            AccountWorkspaceReason.SyncAuthenticationExpired,
            discardBootstrap = false,
        )
        authStatus = AuthStatus.SignedOut
        user = null
        syncing = false
        retrying = false
        publish()
    }

    private suspend fun expireRevisionAuthentication(accountGeneration: Long) = actionMutex.withLock {
        if (accountGeneration != accountWorkspaceController.generation) return@withLock
        accountWorkspaceController.expireAuthentication(
            AccountWorkspaceReason.AuthenticationExpired,
            discardBootstrap = false,
        )
        authStatus = AuthStatus.SignedOut
        user = null
        publish()
    }

    private fun terminalSyncFailureMessage(error: Throwable): String = when (error) {
        is SyncProtocolException -> error.message
            ?: appContext.getString(R.string.sync_protocol_validation_failed)
        is SerializationException -> error.message
            ?: appContext.getString(R.string.sync_returned_a_malformed_response)
        is CoreProjectionException, is SharedCoreException -> projectionFailureMessage(error)
        is ApiException -> error.message
            ?: appContext.getString(R.string.sync_rejected_status, error.statusCode)
        else -> error.message ?: appContext.getString(R.string.sync_stopped_after_a_local_failure)
    }

    private fun requestSync(force: Boolean = false) {
        centralizedSyncRuntime.requestSync(force)
    }

    private suspend fun afterLocalMutation() {
        if (replicationMode() == ReplicationMode.IROH) {
            try {
                replication?.afterLocalMutation()
                reloadWorkspace(ReplicationMode.IROH)
            } catch (error: Exception) {
                conflict = error.message ?: appContext.getString(R.string.iroh_room_operation_could_not_be_recorded)
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
            if (localWorkspaceAdmissionBlocked()) return@withLock
            if (mode == ReplicationMode.CENTRALIZED && accountNetworkBlocked()) return@withLock
            val stored = timerStore.loadWorkspace()
            local = stored.local
            pending = stored.pending.commands
            commandDependencies = stored.commandDependencies
            pendingTaskOperations = stored.pending.taskOperations
            pendingDurationOperations = stored.pending.durationOperations
            pendingAutoStartOperations = stored.pending.autoStartOperations
            pendingSelectedTaskOperations = stored.pending.selectedTaskOperations
            pendingBootstrapResolution = stored.bootstrapResolution
            settings = stored.settings
            canonicalTimer = stored.canonicalTimer
            canonicalHistory = stored.canonicalHistory
            canonicalTasks = stored.canonicalTasks
            canonicalAutoStartBreaks = stored.canonicalAutoStartBreaks
            knownTasks = stored.knownTasks
            user = stored.user
            authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
            conflict = networkState.conflict?.let { "Iroh room has an immutable operation conflict." }
            rebuildProjections()
            publish()
            scheduleAlarm()
        }
        if (mode == ReplicationMode.CENTRALIZED && foreground) {
            centralizedSyncRuntime.requestRevisionOpen()
        }
    }

    fun scheduleWorkspaceReload() {
        scope.launch { reloadWorkspace(ReplicationMode.IROH) }
    }

    override fun refresh() {
        if (accountNetworkBlocked()) return
        if (replicationMode() == ReplicationMode.IROH) {
            scope.launch { syncIrohNow() }
        } else if (replicationMode() == ReplicationMode.CENTRALIZED && authStatus == AuthStatus.SignedIn) {
            requestSync(force = true)
        }
    }

    private fun pendingQueuesEmpty(): Boolean =
        pending.isEmpty() &&
            pendingTaskOperations.isEmpty() &&
            pendingDurationOperations.isEmpty() &&
            pendingAutoStartOperations.isEmpty() &&
            pendingSelectedTaskOperations.isEmpty()

    private fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt? {
        if (historyResolution != null || accountSwitch != null) return null
        if (identity.accountGeneration != accountWorkspaceController.generation ||
            authStatus != AuthStatus.SignedIn || replicationMode() != ReplicationMode.CENTRALIZED
        ) return null
        activeSyncAttempt = identity
        val attempt = centralizedSyncCoordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = identity,
                snapshot = centralizedSyncSnapshot(),
                sentPhysicalMs = currentTimeMillis(),
                sentElapsedRealtimeMs = elapsedRealtimeMillis(),
            ),
        )
        syncing = true
        retrying = false
        publish()
        return attempt
    }

    private suspend fun applySyncResponse(
        attempt: SyncAttempt,
        response: SyncResponse,
        receivedPhysicalMs: Long,
        receivedElapsedRealtimeMs: Long,
    ) {
        if (!currentSyncAttempt(attempt.identity)) return
        TimerSyncValidation.validateCanonicalResponse(response, "Sync")
        val clockSample = trustedClock.sample(
            response,
            attempt.sentPhysicalMs,
            attempt.sentElapsedRealtimeMs,
            receivedPhysicalMs,
            receivedElapsedRealtimeMs,
        )
        val delta = trustedClock.responsePhysicalDelta(clockSample)
        val application = centralizedSyncCoordinator.applySync(
            CentralizedSyncApplicationInput(
                snapshot = centralizedSyncSnapshot(),
                attempt = attempt,
                response = response,
                sampledLocal = localWithClockSample(response, clockSample),
                localizedTimer = localizedCanonicalTimer(
                    response.canonicalTimer,
                    attempt.request.commands,
                    delta,
                ),
                localizedHistory = localizedHistory(
                    response.history,
                    attempt.request.commands,
                    delta,
                ),
                projectionNow = Instant.ofEpochMilli(trustedClock.now(local, clockSample)),
            ),
        )
        val event = transitionCommitter.commit(
            repositorySyncTransition(attempt, response, application, clockSample),
        )
        installSyncApplication(event.application, event.response, event.clockSample)
        if (activeSyncAttempt == attempt.identity) activeSyncAttempt = null
    }

    private fun repositorySyncTransition(
        attempt: SyncAttempt,
        response: SyncResponse,
        application: CentralizedSyncApplication,
        clockSample: ServerClockSample,
    ) = RepositorySyncTransition(
        update = FullSyncStorageUpdate(
            local = application.local,
            acknowledged = attempt.request,
            acknowledgedDurationOperationIds = attempt.request.durationOperations
                .map(DurationOperation::id),
            retained = application.pending.queues,
            retainedCommandDependencies = application.pending.dependencies,
            discardedCommands = application.generatedCommands.discarded,
            discardedCommandDependencies = commandDependencies,
        ),
        application = application,
        response = response,
        clockSample = clockSample,
    )

    private fun currentSyncAttempt(identity: SyncAttemptIdentity): Boolean =
        identity == activeSyncAttempt &&
            identity.accountGeneration == accountWorkspaceController.generation &&
            authStatus == AuthStatus.SignedIn &&
            replicationMode() == ReplicationMode.CENTRALIZED

    private fun installSyncApplication(
        application: CentralizedSyncApplication,
        response: SyncResponse,
        clockSample: ServerClockSample,
    ) {
        trustedClock.install(clockSample)
        installPending(application.pending)
        local = application.local
        canonicalTimer = application.canonical.timer
        canonicalHistory = application.canonical.history
        canonicalTasks = application.canonical.tasks
        canonicalAutoStartBreaks = response.autoStartBreaks
        knownTasks = application.canonical.knownTasks
        settings = application.projected.settings
        installConflictTransition(application.conflict)
        installCoreProjection(application.projected.projection)
        syncing = false
        retrying = false
        publish()
        scheduleAlarm()
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

    private fun installConflictTransition(transition: CentralizedConflictTransition) {
        if (transition is CentralizedConflictTransition.Replace) {
            conflict = transition.conflict
        }
    }

    private suspend fun closeRevisionStream() {
        centralizedSyncRuntime.closeRevisionStream()
    }

    private fun updateNetworkState() {
        val transition = centralizedSyncRuntime.updateOnlineState(currentOnlineState())
        publish()
        centralizedSyncRuntime.resumeNetworkTransition(transition)
    }

    private fun currentOnlineState(): Boolean {
        networkAvailable?.let { return it() }
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun scheduleAlarm() {
        alarmCoordinator.schedule(projection.timer, local.ownedTimerId)
    }

    private fun eligiblePendingCommands(): List<TimerCommand> =
        pending.filter { it.id !in commandDependencies }

    private fun pendingSyncQueues() = PendingSyncQueues(
        commands = pending,
        taskOperations = pendingTaskOperations,
        durationOperations = pendingDurationOperations,
        autoStartOperations = pendingAutoStartOperations,
        selectedTaskOperations = pendingSelectedTaskOperations,
    )

    private fun centralizedSyncSnapshot() = CentralizedSyncSnapshot(
        local = local,
        queues = pendingSyncQueues(),
        dependencies = commandDependencies,
        canonicalTimer = canonicalTimer,
        canonicalHistory = canonicalHistory,
        canonicalTasks = canonicalTasks,
        canonicalAutoStartBreaks = canonicalAutoStartBreaks,
        knownTasks = knownTasks,
        settings = settings,
        selectedPhaseGeneration = selectedPhaseGeneration,
    )

    private fun timerMutationState() = TimerMutationState(
        local = local,
        settings = settings,
        projection = projection,
        projectionBase = currentProjectionBase(),
        queues = pendingSyncQueues(),
        dependencies = commandDependencies,
        knownTasks = knownTasks,
        visibleTasks = tasks,
        selectedTaskId = selectedTaskId,
    )

    private fun installEmptyPending() {
        pending = emptyList()
        pendingDurationOperations = emptyList()
        pendingTaskOperations = emptyList()
        pendingAutoStartOperations = emptyList()
        pendingSelectedTaskOperations = emptyList()
        commandDependencies = emptyMap()
    }

    private fun localWithClockSample(
        response: SyncResponse,
        clockSample: ServerClockSample,
    ): LocalStateEntity {
        val (mergedWall, mergedCounter) = mergedClock(response, clockSample)
        return local.copy(
            hlcWallMs = mergedWall,
            hlcCounter = mergedCounter,
            serverClockOffsetMs = clockSample.offsetMs,
            serverClockUncertaintyMs = clockSample.uncertaintyMs,
            serverClockSamplePhysicalMs = clockSample.midpointPhysicalMs,
            serverClockSampleElapsedRealtimeMs = clockSample.midpointElapsedRealtimeMs,
            serverClockBootId = trustedClock.bootId(),
        )
    }

    private fun installPending(reconciled: CentralizedReconciledPending) {
        local = reconciled.local
        pending = reconciled.queues.commands
        pendingTaskOperations = reconciled.queues.taskOperations
        pendingDurationOperations = reconciled.queues.durationOperations
        pendingAutoStartOperations = reconciled.queues.autoStartOperations
        pendingSelectedTaskOperations = reconciled.queues.selectedTaskOperations
        commandDependencies = reconciled.dependencies
    }

    private fun projectSynchronizedState(
        base: CoreProjectionBase = currentProjectionBase(),
        queues: PendingSyncQueues = pendingSyncQueues(),
    ): CoreProjectionResult {
        val request = SynchronizedProjectionRequestFactory.create(base, queues, local.deviceId)
        return coreProjection.apply(
            base = request.base,
            pending = request.pending,
            now = request.horizon,
        )
    }

    private fun currentProjectionBase(): CoreProjectionBase {
        return CoreProjectionBase(
            canonicalTimer = canonicalTimer,
            history = canonicalHistory,
            tasks = canonicalTasks,
            durationsMs = settings.effectiveDurationsMs(),
            autoStartBreaks = canonicalAutoStartBreaks,
            selectedTaskId = local.selectedTaskId,
        )
    }

    private fun localizedProjectedTimer(
        timer: CanonicalTimer?,
        commands: List<TimerCommand>,
    ): CanonicalTimer? {
        val canonical = timer ?: return null
        val command = canonical.lastIntent?.commandId
            ?.let { commandId -> commands.firstOrNull { it.id == commandId } }
            ?.let(::withPersistedPhysicalTime)
        if (command != null && canonical.status == TimerStatus.Running &&
            command.type in setOf(CommandType.Start, CommandType.Resume)
        ) {
            return canonical.copy(anchorAt = command.physicalOccurredAt ?: command.occurredAt)
        }
        return localizedCanonicalTimer(canonical, commands, defaultDeltaMs = 0L)
    }

    private fun installCoreProjection(
        result: CoreProjectionResult,
        commands: List<TimerCommand> = pending,
    ) {
        projection = TimerProjection(
            localizedProjectedTimer(result.canonicalTimer, commands),
            localizedHistory(result.history, commands, defaultDeltaMs = 0L),
        )
        alarmCoordinator.reconcileCompletionAlert(projection.timer)
        tasks = result.tasks
        selectedTaskId = result.selectedTaskId
        settings = settings.withDurations(result.durationsMs).copy(
            autoStartBreaks = result.autoStartBreaks,
        )
        knownTasks = (knownTasks.values + canonicalTasks + result.tasks).associateBy(FocusTask::id)
    }

    private fun awaitingDurableLocalCompletion(): Boolean {
        val persisted = canonicalTimer ?: return false
        val ownedTimerId = local.ownedTimerId ?: return false
        return persisted.id == ownedTimerId &&
            persisted.status == TimerStatus.Running &&
            projection.timer?.let { it.id == persisted.id && it.status == TimerStatus.Completed } == true &&
            pending.none { it.timerId == persisted.id && it.type == CommandType.Finish }
    }

    private fun rebuildProjections() {
        installCoreProjection(projectSynchronizedState())
    }

    private fun <T> plannedMutation(block: () -> TimerMutationTransition<T>): T? =
        when (val transition = mutationProjection(block) ?: return null) {
            TimerMutationTransition.Ignored -> null
            is TimerMutationTransition.Planned -> transition.plan
        }

    private fun <T> mutationProjection(block: () -> T): T? = try {
        block().also {
            if (notice == mutationFailure) notice = null
            mutationFailure = null
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        val message = projectionFailureMessage(error)
        mutationFailure = message
        notice = message
        publish()
        null
    }

    private fun projectionFailureMessage(error: Throwable): String = when (error) {
        is SharedCoreException.Load, is SharedCoreException.Abi ->
            appContext.getString(R.string.shared_core_unavailable)
        else -> appContext.getString(R.string.shared_core_invalid_output)
    }

    private suspend fun fetchValidatedProfile(): User {
        val profile = try {
            auth.authorized(api::me).user
        } catch (error: SerializationException) {
            throw ProfileProtocolException("Account profile response is malformed: ${error.message.orEmpty()}")
        }
        TimerSyncValidation.validateUser(profile)
        return profile
    }

    private suspend fun applyBootstrapResolution(
        request: BootstrapResolutionRequest,
        response: SyncResponse,
        acknowledgementResponse: SyncResponse,
        clockSample: ServerClockSample,
    ) {
        val profile = user ?: throw AuthenticationRequired()
        val delta = trustedClock.responsePhysicalDelta(clockSample)
        val application = centralizedSyncCoordinator.applyBootstrapResolution(
            CentralizedBootstrapResolutionInput(
                snapshot = centralizedSyncSnapshot(),
                profile = profile,
                request = request,
                response = response,
                acknowledgementResponse = acknowledgementResponse,
                sampledLocal = localWithClockSample(response, clockSample),
                localizedTimer = localizedCanonicalTimer(
                    response.canonicalTimer,
                    request.commands,
                    delta,
                ),
                localizedHistory = localizedHistory(response.history, request.commands, delta),
                projectionNow = Instant.ofEpochMilli(trustedClock.now(local, clockSample)),
            ),
        )
        val event = transitionCommitter.commit(
            RepositoryBootstrapResolutionTransition(
                update = BootstrapResolutionStorageUpdate(
                    local = application.local,
                    clearAutoStartOperations = request.autoStartOperations != null,
                    retainedCommands = application.pending.queues.commands,
                    retainedCommandDependencies = application.pending.dependencies,
                    retainedAutoStartOperations = application.pending.queues.autoStartOperations,
                    clearSelectedTaskOperations = request.selectedTaskOperations != null,
                    retainedSelectedTaskOperations = application.pending.queues.selectedTaskOperations,
                ),
                application = application,
                response = response,
                clockSample = clockSample,
            ),
        )
        installResolvedBootstrap(event.application, event.response, event.clockSample)
    }

    private fun installResolvedBootstrap(
        application: CentralizedSyncApplication,
        response: SyncResponse,
        clockSample: ServerClockSample,
    ) {
        trustedClock.install(clockSample)
        local = application.local
        pending = application.pending.queues.commands
        commandDependencies = application.pending.dependencies
        pendingTaskOperations = emptyList()
        pendingDurationOperations = emptyList()
        pendingAutoStartOperations = application.pending.queues.autoStartOperations
        pendingSelectedTaskOperations = application.pending.queues.selectedTaskOperations
        pendingBootstrapResolution = null
        canonicalTimer = application.canonical.timer
        canonicalHistory = application.canonical.history
        canonicalTasks = application.canonical.tasks
        canonicalAutoStartBreaks = response.autoStartBreaks
        knownTasks = application.canonical.knownTasks
        settings = application.projected.settings
        historyResolution = null
        accountWorkspaceController.captureBootstrap(
            response,
            clockSample,
            AccountWorkspaceReason.BootstrapResolved,
        )
        syncing = false
        retrying = false
        installConflictTransition(application.conflict)
        installCoreProjection(application.projected.projection)
        publish()
        scheduleAlarm()
    }
    private fun mergedClock(
        response: SyncResponse,
        clockSample: ServerClockSample,
    ): Pair<Long, Long> {
        val retainedClock = retainedHlc()
        return try {
            val boundedLocal = retainedClock.takeIf {
                it.first <= trustedClock.now(local, clockSample) + SyncWireBounds.MaxClockSkewMs
            } ?: (0L to 0L)
            val merged = coreHlc.tick(
                physicalNowMs = trustedClock.now(local, clockSample),
                local = CoreHlc(boundedLocal.first, boundedLocal.second),
                remote = CoreHlc(response.serverHlcWallMs, response.serverHlcCounter),
            )
            merged.wallMs to merged.counter
        } catch (_: IllegalArgumentException) {
            throw SyncProtocolException("Sync returned a hybrid clock outside trusted-time bounds")
        }
    }

    private fun reserveMutation(
        count: Int,
        withDeviceSequences: Boolean,
    ): TimerMutationReservation? {
        val reservation = try {
            val retainedClock = retainedHlc()
            val nowMs = trustedClock.now(local, retainedWallMs = retainedClock.first)
            val maximumRetainedWallMs = minOf(
                SyncWireBounds.MaxSafeInteger,
                nowMs + SyncWireBounds.MaxClockSkewMs,
            )
            require(retainedClock.first <= maximumRetainedWallMs) {
                "Retained hybrid clock is outside trusted-time bounds"
            }
            val clocks = coreHlc.reserve(
                physicalNowMs = nowMs,
                retained = CoreHlc(retainedClock.first, retainedClock.second),
                count = count,
            )
            val stamps = SyncWireBounds.mutationStamps(
                nowMs = nowMs,
                clocks = clocks,
                retainedDeviceSequence = local.deviceSequence,
                withDeviceSequences = withDeviceSequences,
            )
            val previous = previousUuidV7()
            val uuids = UuidV7.reserve(
                timestampMs = stamps.first().wallMs,
                count = count,
                previous = previous,
                entropy = uuidEntropy,
            )
            TimerMutationReservation(stamps, uuids, uuids.last().toString())
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
                pendingAutoStartOperations.map(AutoStartOperation::id) +
                pendingSelectedTaskOperations.map(SelectedTaskOperation::id)
            ).mapNotNull(UuidV7::payload)
            .maxWithOrNull(UuidV7::compare)
        require(stored == null || queued == null || UuidV7.compare(stored, queued) >= 0) {
            "Persisted UUIDv7 cursor is behind queued mutations"
        }
        return stored ?: queued
    }

    private suspend fun repairLegacyMutationQueues(persist: Boolean = true): Boolean {
        val repair = prepareLegacyMutationQueueRepair()
        if (!legacyMutationQueueChanged(repair)) return false
        if (persist) persistLegacyMutationQueueRepair(repair)
        installLegacyMutationQueueRepair(repair)
        return true
    }

    private fun prepareLegacyMutationQueueRepair(): LegacyMutationQueueRepair {
        val canRepairWirePayload = pendingBootstrapResolution == null
        val repairedCommands = pending.map { command ->
            command.copy(
                hlcWallMs = repairedLegacyWallMs(
                    command.occurredAt,
                    command.hlcWallMs,
                    canRepairWirePayload,
                ),
                physicalOccurredAt = command.physicalOccurredAt
                    ?.takeIf(::supportedPhysicalOccurrence)
                    ?: command.occurredAt,
            )
        }
        val repairedTasks = pendingTaskOperations.map { operation ->
            operation.copy(hlcWallMs = repairedLegacyWallMs(
                operation.occurredAt,
                operation.hlcWallMs,
                canRepairWirePayload,
            ))
        }
        val repairedDurations = pendingDurationOperations.map { operation ->
            operation.copy(hlcWallMs = repairedLegacyWallMs(
                operation.occurredAt,
                operation.hlcWallMs,
                canRepairWirePayload && operation.hlcWallMs != 0L,
            ))
        }
        val repairedAutoStart = pendingAutoStartOperations.map { operation ->
            operation.copy(hlcWallMs = repairedLegacyWallMs(
                operation.occurredAt,
                operation.hlcWallMs,
                canRepairWirePayload && operation.hlcWallMs != 0L,
            ))
        }
        val repairedLocal = repairedLegacyLocal(
            repairedCommands,
            repairedTasks,
            repairedDurations,
            repairedAutoStart,
        )
        return LegacyMutationQueueRepair(
            repairedLocal,
            repairedCommands,
            repairedTasks,
            repairedDurations,
            repairedAutoStart,
        )
    }

    private fun repairedLegacyWallMs(
        occurredAt: String,
        wallMs: Long,
        canRepair: Boolean,
    ): Long {
        if (!canRepair) return wallMs
        val occurrenceMs = runCatching { Instant.parse(occurredAt).toEpochMilli() }
            .getOrNull() ?: return wallMs
        return occurrenceMs.takeIf {
            wallMs !in (it - SyncWireBounds.MaxClockSkewMs)..
                (it + SyncWireBounds.MaxClockSkewMs)
        } ?: wallMs
    }

    private fun repairedLegacyLocal(
        commands: List<TimerCommand>,
        tasks: List<TaskOperation>,
        durations: List<DurationOperation>,
        autoStart: List<AutoStartOperation>,
    ): LocalStateEntity {
        if (SyncWireBounds.isClockTuple(
                local.hlcWallMs,
                local.hlcCounter,
                allowLegacySentinel = true,
            )
        ) return local
        val repairedClocks = commands.map { it.hlcWallMs to it.hlcCounter } +
            tasks.map { it.hlcWallMs to it.hlcCounter } +
            durations.filter { it.hlcWallMs > 0 }.map { it.hlcWallMs to it.hlcCounter } +
            autoStart.filter { it.hlcWallMs > 0 }.map { it.hlcWallMs to it.hlcCounter }
        val retained = repairedClocks.maxWithOrNull(
            compareBy<Pair<Long, Long>>({ it.first }, { it.second }),
        ) ?: (0L to 0L)
        return local.copy(hlcWallMs = retained.first, hlcCounter = retained.second)
    }

    private fun legacyMutationQueueChanged(repair: LegacyMutationQueueRepair): Boolean {
        return repair.local != local ||
            repair.commands != pending ||
            repair.taskOperations != pendingTaskOperations ||
            repair.durationOperations != pendingDurationOperations ||
            repair.autoStartOperations != pendingAutoStartOperations
    }

    private suspend fun persistLegacyMutationQueueRepair(repair: LegacyMutationQueueRepair) {
        timerStore.saveMutationState(
            repair.local,
            PendingSyncQueues(
                commands = repair.commands,
                taskOperations = repair.taskOperations,
                durationOperations = repair.durationOperations,
                autoStartOperations = repair.autoStartOperations,
                selectedTaskOperations = emptyList(),
            ),
            commandDependencies,
        )
    }

    private fun installLegacyMutationQueueRepair(repair: LegacyMutationQueueRepair) {
        local = repair.local
        pending = repair.commands
        pendingTaskOperations = repair.taskOperations
        pendingDurationOperations = repair.durationOperations
        pendingAutoStartOperations = repair.autoStartOperations
    }

    private fun retainedHlc(): Pair<Long, Long> {
        val latestPersistedWallMs = latestPersistedMutationWallMs()
        val latestTrustedWallMs = trustedClock.sampledNowOrNull(local, latestPersistedWallMs)
            ?.plus(SyncWireBounds.MaxClockSkewMs)
        return (
        listOf(local.hlcWallMs to local.hlcCounter) +
            pending.map { it.hlcWallMs to it.hlcCounter } +
            pendingTaskOperations.map { it.hlcWallMs to it.hlcCounter } +
            pendingDurationOperations.filter { it.hlcWallMs > 0 }
                .map { it.hlcWallMs to it.hlcCounter } +
            pendingAutoStartOperations.filter { it.hlcWallMs > 0 }
                .map { it.hlcWallMs to it.hlcCounter } +
            pendingSelectedTaskOperations.map { it.hlcWallMs to it.hlcCounter }
        ).filter { latestTrustedWallMs == null || it.first <= latestTrustedWallMs }
            .maxWithOrNull(compareBy<Pair<Long, Long>>({ it.first }, { it.second }))
            ?: (0L to 0L)
    }

    private fun latestPersistedMutationWallMs(): Long = (
        listOf(local.hlcWallMs) +
            pending.map(TimerCommand::hlcWallMs) +
            pendingTaskOperations.map(TaskOperation::hlcWallMs) +
            pendingDurationOperations.map(DurationOperation::hlcWallMs) +
            pendingAutoStartOperations.map(AutoStartOperation::hlcWallMs) +
            pendingSelectedTaskOperations.map(SelectedTaskOperation::hlcWallMs)
        ).maxOrNull() ?: local.hlcWallMs

    private fun forTrustedWire(command: TimerCommand): TimerCommand =
        command.copy(physicalOccurredAt = null)

    private fun forTrustedWire(operation: TaskOperation): TaskOperation = operation

    private fun forTrustedWire(operation: DurationOperation): DurationOperation = operation

    private fun forTrustedWire(operation: AutoStartOperation): AutoStartOperation = operation

    private fun forTrustedWire(operation: SelectedTaskOperation): SelectedTaskOperation = operation

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

    private fun captureBootstrapResolutionAttempt(
        request: BootstrapResolutionRequest,
    ): BootstrapResolutionAttempt {
        val sentPhysicalMs = currentTimeMillis()
        val sentElapsedRealtimeMs = elapsedRealtimeMillis()
        return BootstrapResolutionAttempt(
            accountGeneration = accountWorkspaceController.generation,
            request = request,
            sentPhysicalMs = sentPhysicalMs,
            sentElapsedRealtimeMs = sentElapsedRealtimeMs,
        )
    }

    private fun visibleHistoryCount(history: List<HistoryItem>): Int {
        return history.count { it.status == TimerStatus.Completed }
    }

    private fun hasLocalSyncState(): Boolean {
        return projection.timer != null ||
            projection.history.isNotEmpty() ||
            tasks.isNotEmpty() ||
            pending.isNotEmpty() ||
            pendingTaskOperations.isNotEmpty() ||
            pendingDurationOperations.isNotEmpty() ||
            pendingAutoStartOperations.isNotEmpty() ||
            pendingSelectedTaskOperations.isNotEmpty() ||
            local.selectedTaskId != null ||
            settings.effectiveDurationsMs() != DurationsMs() ||
            settings.autoStartBreaks
    }

    private fun hasRemoteSyncState(response: SyncResponse): Boolean {
        return response.canonicalTimer != null ||
            response.history.isNotEmpty() ||
            response.tasks.isNotEmpty() ||
            response.selectedTaskId != null ||
            response.durationsMs != DurationsMs() ||
            response.autoStartBreaks
    }

    private fun accountNetworkBlocked(): Boolean =
        !accountAdmissionResolved || accountPublication.quarantined || !::local.isInitialized ||
            local.accountDeletionState != null || credentialRecoveryRequired

    private fun localWorkspaceAdmissionBlocked(allowWhileLoading: Boolean = true): Boolean =
        !::local.isInitialized || mutationFailure != null || mutationsBlocked(allowWhileLoading) ||
            auth.credentialState() !in setOf(AuthCredentialState.Empty, AuthCredentialState.Active)

    private fun mutationsBlocked(allowWhileLoading: Boolean = false): Boolean {
        return accountPublication.quarantined ||
            localMutationCorrupted ||
            credentialRecoveryRequired ||
            local.accountDeletionState != null ||
            replication?.state?.value?.transitioning == true ||
            (replicationMode() == ReplicationMode.IROH && networkState.conflict != null) ||
            historyResolution != null ||
            accountSwitch != null ||
            (!allowWhileLoading && authStatus == AuthStatus.Loading) ||
            authStatus == AuthStatus.SigningIn
    }

    private fun PendingBootstrapResolutionEntity.toRequestStrict(): BootstrapResolutionRequest {
        val storedUser = strictJson.decodeFromString<User>(userJson)
            .also(TimerSyncValidation::validateUser)
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
            selectedTaskOperations = selectedTaskOperationsJson?.let {
                strictJson.decodeFromString<List<SelectedTaskOperation>>(it)
            },
        )
        TimerSyncValidation.validateResolutionEnvelope(request, local.deviceId)
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
                    request.autoStartOperations.orEmpty().isEmpty() &&
                    request.selectedTaskOperations.orEmpty().isEmpty(),
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
        if (request.selectedTaskOperations != null) {
            require(request.selectedTaskOperations.sortedWith(selectedTaskOperationComparator) ==
                pendingSelectedTaskOperations.map(::forTrustedWire).sortedWith(selectedTaskOperationComparator)
            ) { "Saved bootstrap selected-task operations do not match local queues" }
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
        selectedTaskOperationsJson = selectedTaskOperations?.let(json::encodeToString),
        ownerUserId = profile.id,
        userJson = json.encodeToString(profile),
    )

    private fun BootstrapStrategy.displayName(): String = when (this) {
        BootstrapStrategy.KeepRemote -> "Keep Remote"
        BootstrapStrategy.ReplaceRemote -> "Keep Local"
        BootstrapStrategy.Merge -> "Keep Both"
    }

    private fun currentAttemptIdentity() = accountWorkspaceController.attemptIdentity(
        pendingBootstrapResolution?.requestId,
    )

    private fun isCurrent(identity: AccountAttemptIdentity): Boolean =
        accountWorkspaceController.owns(identity, pendingBootstrapResolution?.requestId)

    private suspend fun handleAuthenticationRequired(
        identity: AccountAttemptIdentity,
        message: String,
    ) {
        var shouldCloseStream = false
        actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock
            auth.clear()
            accountWorkspaceController.expireAuthentication(
                AccountWorkspaceReason.AuthenticationExpired,
                discardBootstrap = true,
            )
            authStatus = AuthStatus.SignedOut
            user = null
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
        error = appContext.getString(R.string.saved_history_resolution_corrupted_local_data_preserved),
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

    private fun acceptAlarmCoordinatorEvent(@Suppress("UNUSED_PARAMETER") event: AlarmCoordinatorEvent) {
        publish()
    }

    private fun publish() {
        accountPublication.publish(::publishSnapshot)
    }

    private fun publishSnapshot(accountQuarantined: Boolean) {
        statePublisher.publish(RepositoryPublication(
            ready = initialized.isCompleted,
            authStatus = if (accountQuarantined) AuthStatus.SignedOut else authStatus,
            localAccountResetRequired = localMutationCorrupted || credentialRecoveryRequired ||
                accountQuarantined,
            user = user.takeUnless { accountQuarantined },
            projection = if (accountQuarantined) TimerProjection(null, emptyList()) else projection,
            completionAlertTimerId = alarmCoordinator.completionAlertTimerId.takeUnless { accountQuarantined },
            tasks = if (accountQuarantined) emptyList() else tasks,
            knownTasks = if (accountQuarantined) emptyList() else knownTasks.values,
            selectedTaskId = selectedTaskId.takeUnless { accountQuarantined },
            settings = if (accountQuarantined) TimerSettings() else settings,
            pendingCounts = if (accountQuarantined) {
                listOf(0, 0, 0, 0, 0)
            } else {
                listOf(
                    pending.size,
                    pendingTaskOperations.size,
                    pendingDurationOperations.size,
                    pendingAutoStartOperations.size,
                    pendingSelectedTaskOperations.size,
                )
            },
            online = online && !accountQuarantined,
            syncing = syncing && !accountQuarantined,
            retrying = retrying && !accountQuarantined,
            historyResolution = historyResolution.takeUnless { accountQuarantined },
            accountSwitch = accountSwitch.takeUnless { accountQuarantined },
            conflict = conflict.takeUnless { accountQuarantined },
            notice = notice,
            deviceId = if (::local.isInitialized && !accountQuarantined) local.deviceId else "",
            network = if (accountQuarantined) IrohNetworkState() else networkState,
        ))
    }

    private companion object {
        const val MaxCommandsPerSync = 256
        const val MaxTaskOperationsPerSync = 256
        const val MaxDurationOperationsPerSync = 256
        const val MaxAutoStartOperationsPerSync = 256
        const val MaxSelectedTaskOperationsPerSync = 256
        const val CompletionAlertPreferences = "completion-alert"
        const val CompletionAlertTimerId = "timer-id"
        const val MaxBootstrapOperations = 4096
        const val MaxTimerDurationMs = 14_400_000L
        const val LocalClockRangeError = "Local clock or sequence is outside the synchronization range."
        const val LocalStateCorruptedError =
            "Persisted timer state is corrupted. Sync and local mutations are blocked."
        const val AccountDeletionPrepared = "prepared"
        const val AccountDeletionRemoteCommitted = "remote_committed"
        const val AccountLocalScrubRequired = "local_scrub_required"
        const val AccountDeletionOutcomeUnknownMessage =
            "Account deletion outcome is unresolved. Retry deletion or reset local account data."
        const val AccountDeletionRecoveryFailedMessage =
            "Account was deleted remotely, but local cleanup must be retried."
        const val UnreadableCredentialMessage =
            "Stored sign-in credentials are unreadable. Reset local account data to sign in again."
        const val PendingLogoutMessage =
            "Sign-out revocation is pending. Sign in to retry it before creating a new session."
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
        val selectedTaskOperationComparator = compareBy<SelectedTaskOperation>(
            SelectedTaskOperation::hlcWallMs,
            SelectedTaskOperation::hlcCounter,
            SelectedTaskOperation::id,
        )
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
