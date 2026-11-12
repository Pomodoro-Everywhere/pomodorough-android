package me.egigoka.pomodorough.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import me.egigoka.pomodorough.crash.CrashReporter
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
import me.egigoka.pomodorough.data.storage.StoredTimerWorkspace
import me.egigoka.pomodorough.data.storage.TimerStore
import me.egigoka.pomodorough.data.time.TrustedClock
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.IrohRouteReadException
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
    // New fields go last: positional construction and componentN() are a source contract.
    val localStorageReadFailed: Boolean = false,
    val skipDestinationPhase: String? = null,
    val noticeAction: NoticeAction? = null,
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
    private val pendingTaskDeletionWindow = PendingTaskDeletionWindow(
        PendingTaskDeletionMarkerStore(
            appContext.getSharedPreferences(PendingTaskDeletionMarkerStore.PreferencesName, Context.MODE_PRIVATE),
        ),
    )
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
    private val coreSkipDestination by lazy { CoreSkipDestinationDispatcher(coreDispatch) }
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
            batchPlanner = coreBatchPlanner,
        )
    }
    private val coreBatchPlanner by lazy { CoreBatchPlanDispatcher(coreDispatch) }
    private var syncNextDomain = CoreBatchPlanDispatcher.InitialCursor
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + Dispatchers.IO)
    private val logoutRevocations = LogoutRevocationRetryController(auth, scope)
    private val initializeMutex = Mutex()
    private val actionMutex = workspaceCoordinator
    private val initialized = CompletableDeferred<Unit>()
    private val networkInitializationStarted = AtomicBoolean(false)
    private val accountRestoreRetryInFlight = AtomicBoolean(false)
    // Set by restored connectivity and cleared when a restore attempt starts, so a failed
    // attempt can tell that the network came back after it began.
    private val connectivityRestoredDuringRestore = AtomicBoolean(false)
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
    private var neverSentProof = CoreNeverSentProof()
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
    // UX round 2, L5: the conflict card shows an authored sentence, so the raw
    // reason stays a diagnostic. It goes to the local log once, when it is set;
    // there is no Sentry capture here.
    private var conflict: String? = null
        set(value) {
            if (value != null && value != field) Log.w(ConflictLogTag, "Conflict reason: $value")
            field = value
        }
    // A notice set anywhere else is a plain sentence: it drops the retry that
    // an earlier account failure offered. Only resetSignedOutAuthentication pairs them.
    private var notice: String? = null
        set(value) {
            field = value
            noticeAction = null
        }
    private var noticeAction: NoticeAction? = null
    private var historyResolution: HistoryResolutionState?
        get() = centralizedAccountSync.historyResolution
        set(value) { centralizedAccountSync.historyResolution = value }
    private var accountSwitch: AccountSwitchState?
        get() = centralizedAccountSync.accountSwitch
        set(value) { centralizedAccountSync.accountSwitch = value }
    private var localMutationCorrupted = false
    private var localStorageReadFailed = false
    // R54-A07: a startup read of the replication route or workspace failed. Startup shows the
    // retry screen, and mutations and network work stay blocked until a later startup succeeds.
    private var replicationStartupReadFailed = false
    private var credentialRecoveryRequired = false
    private var mutationFailure: String? = null
    private var selectedPhaseGeneration = 0L
    private var networkState = IrohNetworkState()
    private var skipDestinationMemo: SkipDestinationMemo? = null

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
        initializeWorkspace()
        replayLeftoverTaskDeletion()
    }

    private suspend fun initializeWorkspace() {
        ensureLocalInitialized()
        // A failed storage read leaves startup open for the next initialize().
        if (!initialized.isCompleted) return
        if (!admitAccountInitialization()) return
        if (!initializeReplication()) return
        // R54-A07: this startup read the route, so it releases an earlier read hold before the
        // workspace gates run. The reload or the publish below then replaces the retry screen.
        replicationStartupReadFailed = false
        if (foreground) replication?.onForeground()
        if (initializeNonCentralizedWorkspace()) return
        publish()
        initializeCentralizedAccount()
    }

    // R54-A07: a failed route read holds startup on the retry screen instead of escaping the
    // caller. The orchestration published nothing and reads the route again on the next call.
    private suspend fun initializeReplication(): Boolean {
        try {
            replication?.initialize()
        } catch (_: IrohRouteReadException) {
            // expected-silent: the orchestration already sent the bounded route-read report.
            replicationStartupReadFailed = true
            publish()
            return false
        }
        return true
    }

    private suspend fun admitAccountInitialization(): Boolean {
        if (localMutationCorrupted || mutationFailure != null) return false
        if (resolveDeletionStateForInitialization()) return false
        if (resolveCredentialStateForInitialization()) return false
        logoutRevocations.startIfNeeded()
        accountAdmissionResolved = true
        return true
    }

    // R54-A01: refresh, foreground, and restored connectivity re-admit a restore that failed
    // with credentials and owner retained. Each request admits at most one attempt through
    // initialization's gates and single-flight restore; a failure waits for the next request.
    private fun requestAccountRestoreRetry() {
        if (!accountRestoreRetryAllowed()) return
        if (!accountRestoreRetryInFlight.compareAndSet(false, true)) return
        scope.launch {
            try {
                accountWorkspaceController.serialize { retryAccountRestore() }
            } finally {
                accountRestoreRetryInFlight.set(false)
            }
            requestRetryForConnectivityRestoredDuringRestore()
        }
    }

    // A restore request dropped because an attempt was in flight would otherwise wait for the
    // next foreground if that attempt fails. Only restored connectivity carries news the attempt
    // could not see; each restored network earns one follow-up request. A running retry holds
    // admission, so it makes the request itself after releasing it. While the gate refuses (for
    // example, an explicit restore holds initialization), the restoration stays recorded so the
    // failure of that restore can spend it.
    private fun requestRetryForConnectivityRestoredDuringRestore() {
        if (accountRestoreRetryInFlight.get()) return
        if (!accountRestoreRetryAllowed()) return
        if (connectivityRestoredDuringRestore.getAndSet(false)) requestAccountRestoreRetry()
    }

    private suspend fun retryAccountRestore() {
        if (!accountRestoreRetryAllowed() || !admitAccountInitialization()) return
        initializeCentralizedAccount()
    }

    // A SignedOut account with the restore re-armed means a restore or sign-in failed; startup
    // before its first restore stays Loading. The owner must be retained so a retry never
    // reaches the stale-credential clear.
    private fun accountRestoreRetryAllowed(): Boolean =
        initialized.isCompleted && !networkInitializationStarted.get() && !accountNetworkBlocked() &&
            replicationMode() == ReplicationMode.CENTRALIZED && authStatus == AuthStatus.SignedOut &&
            credentialsAdmitRestoreRetry() && retainsAccountOwner()

    // Unreadable credentials are admitted so initialization's gate publishes the same recovery
    // notice as initialize() and fences later retries. Pending logout never retries, so a retry
    // cannot republish its notice; empty credentials have nothing to restore.
    private fun credentialsAdmitRestoreRetry(): Boolean = when (auth.credentialState()) {
        AuthCredentialState.Active, AuthCredentialState.Unreadable -> true
        AuthCredentialState.Empty, AuthCredentialState.LogoutPending -> false
    }

    private fun retainsAccountOwner(): Boolean =
        local.ownerUserId != null || local.userJson != null || hasRecoverablePendingOwner()

    private fun hasRecoverablePendingOwner(): Boolean =
        pendingBootstrapResolution?.let { pendingResolution ->
            historyResolution?.corrupted != true &&
                pendingResolution.ownerUserId.isNotBlank() &&
                pendingResolution.userJson.isNotBlank()
        } == true

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
                notice = appContext.getString(R.string.account_deletion_outcome_unknown)
                publish()
                initialized.complete(Unit)
                true
            }
            null -> false
            else -> {
                localMutationCorrupted = true
                conflict = localStateCorruptedSentence()
                terminalSyncError = localStateCorruptedSentence()
                publish()
                true
            }
        }

    private fun resolveCredentialStateForInitialization(): Boolean = when (auth.credentialState()) {
        AuthCredentialState.Unreadable -> {
            credentialRecoveryRequired = true
            authStatus = AuthStatus.SignedOut
            user = null
            notice = appContext.getString(R.string.unreadable_credential)
            publish()
            true
        }
        AuthCredentialState.LogoutPending -> {
            authStatus = AuthStatus.SignedOut
            notice = appContext.getString(R.string.pending_logout)
            publish()
            logoutRevocations.startIfNeeded()
            true
        }
        else -> false
    }

    private suspend fun initializeNonCentralizedWorkspace(): Boolean {
        if (replicationMode() == ReplicationMode.CENTRALIZED) return false
        if (!reloadStartupWorkspace(replicationMode())) return true
        if (authStatus == AuthStatus.Loading) {
            authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
        }
        // Also replaces a retry screen when the reload gates held the stored workspace back.
        publish()
        return true
    }

    // R54-A07: a failed startup reload holds startup on the retry screen. Returns false after
    // that failure; the stored rows stay untouched for the next startup to read again.
    private suspend fun reloadStartupWorkspace(mode: ReplicationMode): Boolean = actionMutex.withLock {
        if (!workspaceReloadAdmitted(mode)) return@withLock true
        val stored = try {
            timerStore.loadWorkspace()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // One bounded read report via CrashReporter (no persisted content or SQL).
            CorruptStateReporter.report(CorruptStateCategory.StartupWorkspaceRead)
            replicationStartupReadFailed = true
            publish()
            return@withLock false
        }
        installStoredWorkspace(stored)
        true
    }

    private suspend fun initializeCentralizedAccount() {
        if (!networkInitializationStarted.compareAndSet(false, true)) return
        if (local.ownerUserId == null && local.userJson == null && auth.hasTokens() && !hasRecoverablePendingOwner()) {
            val clearError = runCatching(auth::clear).exceptionOrNull()
            if (clearError is CancellationException) throw clearError
            val outcome = StaleCredentialClearRecovery.resolve(clearError)
            if (outcome.recoveryRequired) {
                credentialRecoveryRequired = true
                authStatus = AuthStatus.SignedOut
                user = null
                notice = appContext.getString(R.string.unreadable_credential)
                publish()
                return
            }
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
            if (!adoptLegacyDependencies()) return@withLock
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
        // A73 guard-first (A60 pattern): the guarded block swallows cancel; rethrow
        // before mapping to a projection failure so coroutine cancel propagates.
        if (error is CancellationException) throw error
        // R54-A08/A09: startup ends without a workspace, so the boot reschedule and every
        // mutation return early from here on. One bounded report, without error text, records it.
        CorruptStateReporter.report(CorruptStateCategory.ProjectionRuntimeStartup)
        mutationFailure = projectionFailureMessage(error)
        notice = mutationFailure
        finishFailedInitialization()
        return false
    }

    private suspend fun adoptLegacyDependencies(): Boolean {
        if (replicationMode() == ReplicationMode.IROH) return true
        val gate = recordLegacyDependencyDecision()
        if (gate != LegacyDependencyGate.Open) {
            holdLegacyDependencies(gate)
            finishFailedInitialization()
            return false
        }
        // Adoption never rewrites retained rows, so the loaded sibling map still gates release.
        return true
    }

    /**
     * Leaving an Iroh room, or switching an Iroh workspace to another mode, installs a workspace
     * that startup skipped. Adopt it before any sync can send its retained queue as modern rows.
     */
    private suspend fun adoptReloadedLegacyDependencies() {
        if (replicationMode() == ReplicationMode.IROH) return
        val gate = recordLegacyDependencyDecision()
        if (gate == LegacyDependencyGate.Open) return
        holdLegacyDependencies(gate)
        publish()
    }

    private suspend fun recordLegacyDependencyDecision(): LegacyDependencyGate {
        // Only Migration 16 to 17 marks a pre-R54 queue for Core. Null markers mean a modern
        // workspace, and a recorded decision is validated below without calling Core again.
        if (!LegacyDependencyStartup.pending(local)) return legacyDependencyGate()
        local = try {
            LegacyDependencyStartup(dao, coreDispatch).adopt(Instant.ofEpochMilli(currentTimeMillis()), ZoneId.systemDefault())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CrashReporter.report(error)
            return LegacyDependencyGate.RetryAdoption
        }
        clearLegacyRetryNotice()
        return legacyDependencyGate()
    }

    // The record write now committed, so the retry notice from an earlier attempt is stale.
    private fun clearLegacyRetryNotice() {
        if (mutationFailure != appContext.getString(R.string.legacy_dependency_recovery_not_saved)) return
        if (notice == mutationFailure) notice = null
        mutationFailure = null
    }

    // A successful mutation clears its earlier failure. A held legacy queue keeps its notice
    // until a Core decision opens the gate, because a cleared failure reopens restore admission
    // and workspace reload.
    private fun clearMutationFailure() {
        if (heldLegacyDependencyNotice() != null) return
        if (notice == mutationFailure) notice = null
        mutationFailure = null
    }

    private fun legacyDependencyGate(): LegacyDependencyGate = when {
        LegacyDependencyStartup.pending(local) -> LegacyDependencyGate.RetryAdoption
        LegacyDependencyStartup.blocked(local) -> LegacyDependencyGate.Blocked
        else -> LegacyDependencyGate.Open
    }

    /**
     * Raises the notice for a held legacy queue. A blocked decision is final, because startup
     * never asks Core again, so it also takes the corrupt-state route: sync stays blocked with a
     * persistent error, and Reset Local Account clears the queue and both markers (clearedLocal).
     */
    private fun holdLegacyDependencies(gate: LegacyDependencyGate) {
        when (gate) {
            LegacyDependencyGate.Open -> return
            LegacyDependencyGate.RetryAdoption -> {
                mutationFailure = appContext.getString(R.string.legacy_dependency_recovery_not_saved)
            }
            LegacyDependencyGate.Blocked -> {
                mutationFailure = appContext.getString(R.string.legacy_dependencies_need_recovery)
                localMutationCorrupted = true
                terminalSyncError = mutationFailure
                conflict = mutationFailure
            }
        }
        notice = mutationFailure
        // A reload during a retry delay can hold the next attempt; the notice replaces "retrying".
        retrying = false
    }

    /**
     * Sign-in, restore, and a history choice can send the retained queue through
     * resolveBootstrap, and a new Iroh room sends it to peers, so each new attempt waits for the
     * Core decision. Each call retries a pending adoption once. Nothing is persisted for a held
     * attempt: a history choice is dropped and the dialog asks again, and an Automatic plan is
     * not prepared.
     */
    private suspend fun legacyDependenciesAllowEgress(): Boolean {
        val gate = recordLegacyDependencyDecision()
        if (gate == LegacyDependencyGate.Open) return true
        holdLegacyDependencies(gate)
        publish()
        return false
    }

    // Entering a room from another mode stashes this workspace and builds the room from its
    // queue. A live IROH workspace skips adoption on purpose (adoptLegacyDependencies).
    private suspend fun legacyDependenciesAllowRoomEntry(): Boolean =
        replicationMode() == ReplicationMode.IROH || actionMutex.withLock { legacyDependenciesAllowEgress() }

    // A held sign-in keeps the hold notice on the signed-out screen.
    private fun heldLegacyDependencyNotice(): String? =
        mutationFailure.takeIf { ::local.isInitialized && legacyDependencyGate() != LegacyDependencyGate.Open }

    private suspend fun loadLocalInitialization(): LocalInitializationData? = try {
        timerStore.initialize()
    } catch (error: LocalDecodingException) {
        // Corrupt-state diagnostics: blocked UI plus one bounded decoding report
        // via CrashReporter (deduplicated, consent-aware, no persisted payload).
        CorruptStateReporter.report(CorruptStateCategory.Decoding)
        accountWorkspaceController.setDeletionAdmissionQuarantined(
            error.local.accountDeletionState != null,
        )
        accountPublication.transition(error.local.accountDeletionState != null)
        local = error.local
        localMutationCorrupted = true
        terminalSyncError = localStateCorruptedSentence()
        conflict = localStateCorruptedSentence()
        historyResolution = HistoryResolutionState(0, 0, corrupted = true, error = localStateCorruptedSentence())
        alarmCoordinator.cancelForAccountClear()
        finishFailedInitialization()
        null
    } catch (error: LocalStorageReadException) {
        // One bounded read report per stage via CrashReporter (no persisted content or SQL).
        failLocalStorageRead(error.stage)
        null
    }

    // R54-A02: a read failure is not corruption. Every row stays as stored and initialization
    // stays incomplete, so startup shows a retry and the next initialize() reads again. Each stage
    // reports once, without persisted content or SQL.
    private fun failLocalStorageRead(stage: LocalStorageReadStage) {
        CorruptStateReporter.report(stage.category)
        localStorageReadFailed = true
        publish()
    }

    private fun installLocalInitialization(data: LocalInitializationData) {
        accountWorkspaceController.setDeletionAdmissionQuarantined(
            data.local.accountDeletionState != null,
        )
        accountPublication.transition(data.local.accountDeletionState != null)
        local = data.local
        syncNextDomain = local.syncNextDomain
        pending = data.commands
        commandDependencies = data.commandDependencies
        pendingDurationOperations = data.durationOperations
        pendingTaskOperations = data.taskOperations
        pendingAutoStartOperations = data.autoStartOperations
        pendingSelectedTaskOperations = data.selectedTaskOperations
        pendingBootstrapResolution = data.bootstrapResolution
        neverSentProof = data.neverSent
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
        legacyRepair.exceptionOrNull()?.let { error ->
            if (error is CancellationException) throw error
        }
        if (legacyRepair.isFailure) {
            return failCorruptMutationState(localClockRangeSentence(), CorruptStateCategory.ClockRange)
        }
        val queueError = runCatching {
            TimerSyncValidation.validatePendingQueues(pendingSyncQueues(), local.deviceId)
        }.exceptionOrNull()
        if (queueError is CancellationException) throw queueError
        if (queueError != null) {
            return failCorruptMutationState(
                queueError.message ?: localStateCorruptedSentence(),
                CorruptStateCategory.QueueValidation,
            )
        }
        val rangeError = runCatching {
            TimerSyncValidation.validatePersistedMutationRanges(local, pendingSyncQueues())
        }.exceptionOrNull()
        if (rangeError is CancellationException) throw rangeError
        if (rangeError != null) {
            return failCorruptMutationState(localClockRangeSentence(), CorruptStateCategory.ClockRange)
        }
        return legacyRepair.getOrDefault(false)
    }

    private fun localStateCorruptedSentence() = appContext.getString(R.string.local_state_corrupted_sync_blocked)

    private fun localClockRangeSentence() = appContext.getString(R.string.local_clock_outside_sync_range)

    private fun failCorruptMutationState(message: String, category: CorruptStateCategory): Boolean? {
        CorruptStateReporter.report(category)
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
            val sourceId = commandDependencies[entity.id] ?: return@filter false
            val source = pendingById[sourceId]
            source == null || entity.deviceSequence <= source.deviceSequence
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
            timerStore.saveMutationState(
                local,
                pendingSyncQueues(),
                commandDependencies,
                neverSentProof.filteredTo(pendingSyncQueues()),
            )
        }
        if (repair.invalidDependentEntities.isNotEmpty()) {
            timerStore.deleteCommands(repair.invalidDependentEntities)
        }
    }

    private suspend fun finishLocalInitialization() {
        val pendingMessage = R.string.sign_in_to_finish_saved_history_choice
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
        neverSentProof = CoreNeverSentProof()
        syncNextDomain = CoreBatchPlanDispatcher.InitialCursor
        user = null
        historyResolution = null
        accountSwitch = null
        syncing = false
        retrying = false
        authStatus = AuthStatus.SignedOut
        accountAdmissionResolved = false
    }

    // R54-A19: a restart between sending the own Start and its acknowledgement
    // projects without that Start, so a queued own Start keeps the ownership.
    private suspend fun clearStaleOwnedTimer() {
        if (local.ownedTimerId == null || awaitingDurableLocalCompletion() ||
            ownStartQueued(local.ownedTimerId, pending) ||
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

    // The owner that signs in again replays the deletes it left behind.
    override suspend fun signIn(credentialProvider: GoogleCredentialProvider) {
        accountWorkspaceController.serialize { signInInternal(credentialProvider) }
        replayLeftoverTaskDeletion(signedInReplay())
    }

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
        } catch (error: AuthenticationRequired) {
            // expected-silent: session expiry surfaces as a sign-in notice, not a crash.
            identity?.let {
                handleAuthenticationRequired(
                    it,
                    R.string.session_expired_sign_in_again_history_choice,
                    signInFailureNotice(error),
                )
            }
        } catch (error: ProfileProtocolException) {
            // expected-silent: malformed profile surfaces as a sign-in notice, not a crash.
            failSignIn(identity, signInFailureNotice(error), clearCredentials = true)
        } catch (error: Exception) {
            // R50-L02: transport stays quiet with notice; credential/Core/commit
            // gets one bounded stage+cause report.
            // expected-silent: network failure surfaces as a sign-in notice, not a crash.
            reportAccountBootstrap(AccountBootstrapStage.SignIn, identity, error)
            failSignIn(identity, signInFailureNotice(error))
        } finally {
            accountWorkspaceController.releaseSignIn()
        }
    }

    private suspend fun prepareSignIn(): Boolean {
        initialize()
        if (actionMutex.withLock { localMutationCorrupted || accountNetworkBlocked() }) return false
        if (replicationMode() != ReplicationMode.CENTRALIZED) {
            completePendingTaskDeletion()
            replication?.setMode(ReplicationMode.CENTRALIZED)
            if (replicationMode() != ReplicationMode.CENTRALIZED) {
                notice = appContext.getString(R.string.sign_in_workspace_not_restored)
                publish()
                return false
            }
            reloadWorkspace(ReplicationMode.CENTRALIZED)
        }
        return actionMutex.withLock {
            !localMutationCorrupted && !accountNetworkBlocked() &&
                legacyDependenciesAllowEgress() &&
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
            resetSignedOutAuthentication(notice = heldLegacyDependencyNotice())
        }
    }

    private suspend fun failSignIn(
        identity: AccountAttemptIdentity?,
        failure: AccountFailureNotice?,
        clearCredentials: Boolean = false,
    ) = actionMutex.withLock {
        val attemptIdentity = identity ?: return@withLock
        if (!isCurrent(attemptIdentity)) return@withLock
        if (clearCredentials && !retireRejectedCredentials()) {
            resetSignedOutAuthentication(appContext.getString(R.string.unreadable_credential))
            return@withLock
        }
        // R51-A06: bootstrap failure re-arms restore so an explicit
        // initialize() retries instead of stranding the account.
        networkInitializationStarted.set(false)
        resetSignedOutAfterAccountFailure(failure)
    }

    // R54-A02 (R43-A11 pattern): a vault that refuses to clear still holds the rejected tokens.
    // Retrying would replay them, so the account stays un-armed and asks for a local reset,
    // which retries the clear. The failure reports once as CredentialClear.
    private fun retireRejectedCredentials(): Boolean {
        val clearError = runCatching(auth::clear).exceptionOrNull()
        if (clearError is CancellationException) throw clearError
        if (!StaleCredentialClearRecovery.resolve(clearError).recoveryRequired) return true
        credentialRecoveryRequired = true
        return false
    }

    // The sentence and its retry publish together; the raw exception text never does.
    private fun resetSignedOutAfterAccountFailure(failure: AccountFailureNotice?) =
        resetSignedOutAuthentication(
            notice = failure?.let { appContext.getString(it.sentence) },
            action = failure?.action,
        )

    private fun resetSignedOutAuthentication(notice: String?, action: NoticeAction? = null) {
        authStatus = AuthStatus.SignedOut
        user = null
        this.notice = notice
        noticeAction = action
        restorePendingResolutionForSignedOut(R.string.sign_in_again_to_retry_history_choice)
        publish()
    }

    // UX round 2: a task delete that waits for Undo reaches its owner's queue
    // before the account paths below change or reset the workspace. The owner
    // keeps its marker, because those paths can discard that queue.
    override suspend fun logout() {
        releasePendingTaskDeletionToOwner()
        accountWorkspaceController.serialize { logoutInternal() }
        replayLeftoverTaskDeletion()
    }

    override suspend fun resetLocalAccount() {
        releasePendingTaskDeletionToOwner()
        accountWorkspaceController.serialize { resetLocalAccountInternal() }
        replayLeftoverTaskDeletion()
    }

    private suspend fun resetLocalAccountInternal() {
        initialize()
        if (!localAccountResetRequired()) return
        val resetGeneration = advanceAccountGeneration(
            AccountWorkspaceReason.LocalAccountResetStarted,
        )
        closeRevisionStream()
        if (!quarantineReplicationForReset()) return
        // A61: auth.logout() is suspend (network); cancel must propagate
        // instead of reading as a logout failure with a spurious notice.
        val remoteLogoutFailure = runCatching { auth.logout() }.exceptionOrNull()
        if (remoteLogoutFailure is CancellationException) throw remoteLogoutFailure
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
        // A62: quarantineAccount() suspends via mutex.withLock; cancel must
        // propagate instead of reading as a reset failure with a notice.
        val failure = runCatching { replication?.quarantineAccount() }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        if (failure == null) return true
        publishNotice(R.string.local_account_reset_failed_corrupt_data_was_kept)
        return false
    }

    private suspend fun clearCredentialsForReset(): Boolean {
        // A62-excluded: auth.clear() is non-suspend, so no cancel can arise here.
        if (runCatching(auth::clear).exceptionOrNull() == null) return true
        publishNotice(R.string.local_account_reset_failed_credentials_were_not_cleared)
        return false
    }

    private suspend fun clearReplicationForReset(): Boolean {
        // A62: clearAccountData() suspends via mutex.withLock; cancel must
        // propagate instead of publishing a spurious failure notice.
        val failure = runCatching { replication?.clearAccountData() }.exceptionOrNull()
        if (failure is CancellationException) throw failure
        val nonCancel = failure ?: return true
        actionMutex.withLock {
            notice = failureNoticeText(nonCancel, R.string.local_account_reset_failed_corrupt_data_was_kept)
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // expected-silent: local reset failure surfaces as a notice with corrupt data kept, not a crash.
            actionMutex.withLock {
                notice = failureNoticeText(error, R.string.local_account_reset_failed_corrupt_data_was_kept)
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
        if (local.accountDeletionState != null && local.accountDeletionState != AccountLocalScrubRequired) return
        try {
            beginLocalLogout()
            currentCoroutineContext().ensureActive()
            if (!prepareRemoteLogout()) return
            logoutRevocations.startPreparedLogout()
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // expected-silent: sign-out scrub failure surfaces as a notice with local data kept, not a crash.
            actionMutex.withLock {
                notice = failureNoticeText(error, R.string.sign_out_failed_local_timer_data_was_kept)
                publish()
            }
        }
    }

    private suspend fun beginLocalLogout() = actionMutex.withLock {
        if (local.ownerUserId != null) {
            // Retiring credentials is irreversible here; Room must retain the cleanup obligation first.
            if (local.accountDeletionState == null) persistAccountDeletionMarker(AccountLocalScrubRequired)
            alarmCoordinator.cancelForAccountClear()
        }
        accountWorkspaceController.advanceGeneration(AccountWorkspaceReason.LogoutStarted)
        syncing = false
        retrying = false
        authStatus = AuthStatus.SignedOut
        user = null
        accountAdmissionResolved = false
        publish()
    }

    private suspend fun prepareRemoteLogout(): Boolean {
        return try {
            auth.prepareLogout()
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // expected-silent: remote sign-out preservation failure surfaces as a notice, not a crash.
            actionMutex.withLock {
                notice = failureNoticeText(error, R.string.could_not_preserve_remote_sign_out)
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
        restorePendingResolutionForSignedOut(R.string.sign_in_again_to_retry_history_choice)
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
            safeBaseDurationsJson = null,
            safeBaseSelectedTaskId = null,
            safeCanonicalHeadWallMs = null,
            safeCanonicalHeadCounter = null,
            syncNextDomain = CoreBatchPlanDispatcher.InitialCursor,
            legacyDependencyInputJson = null,
            legacyDependencyResultJson = null,
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
        neverSentProof = CoreNeverSentProof()
        syncNextDomain = CoreBatchPlanDispatcher.InitialCursor
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

    override suspend fun deleteAccount(confirmation: String) {
        completePendingTaskDeletion()
        accountWorkspaceController.serialize { deleteAccountInternal(confirmation) }
    }

    private suspend fun prepareAccountDeletion(): Long? {
        try {
            return actionMutex.withLock {
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            handleAccountDeletionFailure(error)
            actionMutex.withLock {
                notice = appContext.getString(R.string.account_deletion_failed_no_local_data_was_removed)
                publish()
            }
            return null
        }
    }

    private suspend fun deleteAccountInternal(confirmation: String) {
        initialize()
        require(confirmation == "DELETE") { "Type DELETE exactly" }
        val deletionGeneration = prepareAccountDeletion() ?: return
        closeRevisionStream()
        var executionStage = AccountDeletionExecutionStage.RemoteDelete
        try {
            replication?.quarantineAccount()
            auth.deleteAccount(confirmation)
            executionStage = AccountDeletionExecutionStage.Marker
            actionMutex.withLock {
                if (accountWorkspaceController.generation != deletionGeneration) return@withLock
                persistAccountDeletionMarker(AccountDeletionRemoteCommitted)
            }
            executionStage = AccountDeletionExecutionStage.CredentialClear
            auth.clear()
            executionStage = AccountDeletionExecutionStage.RoomScrub
            scrubDeletedAccount(deletionGeneration)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val category = handleAccountDeletionExecutionFailure(executionStage, error)
            if (local.accountDeletionState == AccountDeletionRemoteCommitted) {
                retryCommittedAccountScrub({ scrubDeletedAccount(deletionGeneration) })
                return
            }
            actionMutex.withLock {
                notice = if (category == null) {
                    appContext.getString(R.string.account_deletion_outcome_unknown)
                } else {
                    appContext.getString(R.string.account_deletion_failed_no_local_data_was_removed)
                }
                publish()
            }
        }
    }

    private suspend fun recoverCommittedAccountDeletion() {
        runCatching(auth::clear).onFailure { error ->
            if (error is CancellationException) throw error
            handleAccountDeletionExecutionFailure(AccountDeletionExecutionStage.CredentialClear, error)
        }
        val deletionGeneration = accountWorkspaceController.beginDeletionAdmission().generation
        runCatching { scrubDeletedAccount(deletionGeneration) }
            .onFailure { error ->
                if (error is CancellationException) throw error
                handleAccountDeletionExecutionFailure(AccountDeletionExecutionStage.RoomScrub, error)
                actionMutex.withLock {
                    notice = appContext.getString(R.string.account_deletion_recovery_failed)
                    publish()
                }
            }
    }

    private suspend fun scrubDeletedAccount(deletionGeneration: Long) {
        // A78 product decision: deletion wipes Iroh rooms, peers,
        // operations, and the vault (join-capable secrets must not
        // survive); logout keeps device-local P2P rows and only scrubs
        // account fields (see IrohRoomMetadataPersistence).
        replication?.scrubDeletedAccount()
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

    override suspend fun confirmAccountSwitch() {
        releasePendingTaskDeletionToOwner()
        accountWorkspaceController.serialize { confirmAccountSwitchInternal() }
        replayLeftoverTaskDeletion(signedInReplay())
    }

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
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CrashReporter.report(error)
            actionMutex.withLock {
                if (pendingAccountSwitch !== candidate) return@withLock
                accountSwitch = accountSwitch?.copy(
                    submitting = false,
                    error = failureNoticeText(error, R.string.could_not_switch_accounts_without_risking_local_data),
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
        // A61: same suspend-logout guard as resetLocalAccountInternal above;
        // cancel must propagate instead of surfacing a spurious notice.
        if (logoutError is CancellationException) throw logoutError
        // R54-A04: a refused clear still completes the cancellation signed out, then asks for a
        // local reset instead of escaping with the switch left submitting.
        val credentialsRetired = retireRejectedCredentials()
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
            restorePendingResolutionForSignedOut(R.string.sign_in_with_local_data_owner_to_continue_syncing)
            notice = logoutError?.let {
                failureNoticeText(it, R.string.account_switch_cancelled_server_logout_failed)
            }
            if (!credentialsRetired) notice = appContext.getString(R.string.unreadable_credential)
            publish()
        }
        closeRevisionStream()
    }

    override suspend fun toggleTimer() {
        initialize()
        completePendingTaskDeletion()
        when (projection.timer?.status) {
            TimerStatus.Running -> issueCommand(CommandType.Pause)
            TimerStatus.Paused -> issueCommand(CommandType.Resume)
            else -> issueCommand(CommandType.Start)
        }
    }

    override suspend fun finishTimer() {
        initialize()
        completePendingTaskDeletion()
        finishLocalTimer(onlyIfExpired = false, allowWhileLoading = false)
    }

    suspend fun cancelTimer() {
        initialize()
        issueCommand(CommandType.Cancel)
    }

    override suspend fun cancelAndClearTimer() {
        initialize()
        completePendingTaskDeletion()
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
            try {
                val event = transitionCommitter.commit(RepositoryTimerCommandBatchTransition(mutation))
                clearMutationStorageNotice()
                installTimerMutation(event.plan)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
                return@withLock
            }
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun clearTimer() {
        initialize()
        completePendingTaskDeletion()
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
            if (!alarmCoordinator.markCompletionAlert(timerId).changed) return@withLock false
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

    // R48-A01: foreground ticker and alarm receiver share this once-only
    // alert path. Only the finish winner reaches here with an eligible
    // timer; showCompletionAlert still enforces owner, identity,
    // quarantine, cancellation, and auto-break suppression.
    override suspend fun consumeCompletionAlert(
        timerId: String,
        notify: suspend (timerId: String) -> Boolean,
    ): Boolean = showCompletionAlert(timerId) { notify(timerId) }

    override suspend fun selectPhase(phase: String) {
        initialize()
        completePendingTaskDeletion()
        var saved = false
        actionMutex.withLock {
            // R49-A01: TimerPhase.all mirrors Shared Core valid phases.
            // Core owns phase policy; this adapter only persists the choice.
            if (mutationsBlocked() || phase !in TimerPhase.all ||
                projection.timer?.status in activeStatuses
            ) return@withLock
            if (phase == settings.selectedPhase) return@withLock
            val proposedSettings = settings.copy(selectedPhase = phase)
            val proposedLocal = local.copy(settingsJson = json.encodeToString(proposedSettings))
            try {
                timerStore.saveState(proposedLocal)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // CrashReporter via PhaseSelectionReporter (bounded, no payload).
                handlePhaseSelectionFailure(error)
                notice = appContext.getString(R.string.sync_stopped_after_a_local_failure)
                publish()
                return@withLock
            }
            settings = proposedSettings
            local = proposedLocal
            selectedPhaseGeneration += 1L
            if (notice == appContext.getString(R.string.sync_stopped_after_a_local_failure)) {
                notice = null
            }
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
            try {
                val event = transitionCommitter.commit(RepositoryDurationTransition(mutation))
                clearMutationStorageNotice()
                installDurationMutation(event.plan)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
                return@withLock
            }
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    private fun installDurationMutation(mutation: DurationMutationPlan) {
        local = mutation.local
        settings = mutation.settings
        pendingDurationOperations = mutation.operations
        neverSentProof = neverSentProof.copy(durationOperations = neverSentProof.durationOperations + mutation.operation.id)
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
            try {
                val event = transitionCommitter.commit(RepositoryAutoStartTransition(mutation))
                clearMutationStorageNotice()
                local = event.plan.local
                settings = event.plan.settings
                pendingAutoStartOperations = event.plan.operations
                neverSentProof = neverSentProof.copy(autoStartOperations = neverSentProof.autoStartOperations + event.plan.operation.id)
                installCoreProjection(event.plan.projection)
                publish()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
                return@withLock
            }
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    override suspend fun selectTask(taskId: String?) {
        initialize()
        completePendingTaskDeletion()
        var saved = false
        actionMutex.withLock {
            if (mutationsBlocked()) return@withLock
            if (taskId != null && tasks.none { it.id == taskId }) return@withLock
            val runningFocusTimer = projection.timer
                ?.takeIf { it.status in activeStatuses && it.phase == TimerPhase.Focus }
            val retargetNeeded = runningFocusTimer != null && runningFocusTimer.taskId != taskId
            if (taskId == selectedTaskId && !retargetNeeded) return@withLock
            val selectionNeeded = taskId != selectedTaskId
            if (selectionNeeded && runningFocusTimer != null && retargetNeeded) {
                if (!commitSelectedTaskWithRetarget(runningFocusTimer, taskId)) return@withLock
            } else if (selectionNeeded) {
                if (!commitSelectedTask(taskId)) return@withLock
            } else if (runningFocusTimer != null && retargetNeeded) {
                if (!commitRetarget(runningFocusTimer, taskId)) return@withLock
            }
            publish()
            saved = true
        }
        if (saved) afterLocalMutation()
    }

    private suspend fun commitSelectedTask(taskId: String?): Boolean {
        val state = timerMutationState()
        val reservation = reserveMutation(count = 1, withDeviceSequences = false) ?: return false
        val mutation = plannedMutation {
            mutationCoordinator.selectedTask(SelectedTaskMutationInput(state, taskId, reservation))
        } ?: return false
        try {
            val event = transitionCommitter.commit(RepositorySelectedTaskTransition(mutation))
            clearMutationStorageNotice()
            local = event.plan.local
            pendingSelectedTaskOperations = event.plan.operations
            neverSentProof = neverSentProof.copy(
                selectedTaskOperations = neverSentProof.selectedTaskOperations + event.plan.operation.id,
            )
            installCoreProjection(event.plan.projection)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
            return false
        }
        return true
    }

    private suspend fun commitRetarget(current: CanonicalTimer, taskId: String?): Boolean {
        val state = timerMutationState()
        val reservation = reserveMutation(count = 1, withDeviceSequences = true) ?: return false
        val mutation = plannedMutation {
            mutationCoordinator.retarget(TimerRetargetMutationInput(state, current.id, taskId, reservation, currentTimeMillis()))
        } ?: return false
        try {
            val event = transitionCommitter.commit(RepositoryTimerCommandTransition(mutation))
            installRetargetMutation(event.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is SharedCoreException) {
                CrashReporter.report(error)
                val message = projectionFailureMessage(error)
                mutationFailure = message
                notice = message
            } else {
                handleSelectedTaskRetargetFailure(error)
                val message = appContext.getString(R.string.sync_stopped_after_a_local_failure)
                mutationFailure = message
                notice = message
            }
            publish()
            return false
        }
        return true
    }

    // R49-A02: one reservation, one Room transaction, one projection.
    // Selection consumes no device sequence; the retarget advances it by
    // one, preserving contiguous command ordering with no gaps.
    private suspend fun commitSelectedTaskWithRetarget(current: CanonicalTimer, taskId: String?): Boolean {
        val state = timerMutationState()
        val reservation = reserveSelectionRetarget() ?: return false
        val mutation = plannedMutation {
            mutationCoordinator.selectedTaskWithRetarget(state, taskId, current.id, reservation, currentTimeMillis())
        } ?: return false
        try {
            val event = transitionCommitter.commit(RepositorySelectedTaskRetargetTransition(mutation))
            installSelectedTaskRetargetMutation(event.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (error is SharedCoreException) {
                CrashReporter.report(error)
                val message = projectionFailureMessage(error)
                mutationFailure = message
                notice = message
            } else {
                handleSelectedTaskRetargetFailure(error)
                val message = appContext.getString(R.string.sync_stopped_after_a_local_failure)
                mutationFailure = message
                notice = message
            }
            publish()
            return false
        }
        return true
    }

    private fun installSelectedTaskRetargetMutation(mutation: SelectedTaskRetargetMutationPlan) {
        local = mutation.local
        pendingSelectedTaskOperations = mutation.selectedTaskOperations
        pending = pending + mutation.commands
        commandDependencies = commandDependencies + mutation.dependencies
        mutation.commands.forEach { command ->
            if (command.type == CommandType.Retarget) {
                neverSentProof = neverSentProof.copy(commands = neverSentProof.commands + command.id)
            }
        }
        neverSentProof = neverSentProof.copy(
            selectedTaskOperations = neverSentProof.selectedTaskOperations + mutation.selectionOperation.id,
        )
        installCoreProjection(mutation.projection)
        scheduleAlarm()
    }

    private fun installRetargetMutation(mutation: TimerCommandMutationPlan) {
        local = mutation.local
        pending = pending + mutation.commands
        commandDependencies = commandDependencies + mutation.dependencies
        mutation.commands.forEach { command ->
            if (command.type == CommandType.Retarget) {
                neverSentProof = neverSentProof.copy(commands = neverSentProof.commands + command.id)
            }
        }
        installCoreProjection(mutation.projection)
        publish()
        scheduleAlarm()
    }

    override suspend fun addTask(title: String): Boolean {
        initialize()
        // R54-A09: without a local workspace the add is refused before the shared core derives
        // the task identity, because a projection runtime that failed at startup throws there.
        if (!::local.isInitialized) return false
        completePendingTaskDeletion()
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
        completePendingTaskDeletion()
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        issueTaskOperation(TaskOperationType.Delete, task)
    }

    override val pendingTaskDeletion: StateFlow<PendingTaskDeletion?> get() = pendingTaskDeletionWindow.pending

    // UX round 2: the row hides and the durable marker is written; nothing
    // leaves the workspace until the undo window ends.
    override suspend fun requestTaskDeletion(taskId: String) {
        initialize()
        completePendingTaskDeletion()
        if (!::local.isInitialized) return
        val task = tasks.firstOrNull { it.id == taskId } ?: return
        pendingTaskDeletionWindow.open(task, workspaceOwner())
    }

    override suspend fun undoTaskDeletion() = pendingTaskDeletionWindow.undo()

    // Every entry point that can start a timer or change a task ends the undo
    // window first, through the normal delete path. One rule holds for every
    // path: a deleted task never comes back silently, and it is never deleted
    // silently later. A refused delete shows the task again with a notice.
    // UX round 2, S1: every workspace swap (room create, join, leave, identity
    // recovery, a mode change and the forced mode of a sign-in) ends it too,
    // before the swap. Home and a room can hold the same task identity, so the
    // delete must land in the workspace it was opened in and travel with it.
    override suspend fun completePendingTaskDeletion() {
        val committed = actionMutex.withLock {
            val deletion = pendingTaskDeletionWindow.take() ?: return@withLock false
            // An owner that left keeps its marker until it signs in again.
            if (deletion.workspaceOwner != currentWorkspaceOwner()) return@withLock false
            finishMarkedDeletionLocked(deletion.marker, deletion.title, admitted = !mutationsBlocked())
        }
        if (committed) afterLocalMutation()
    }

    // Logout, reset and account switch can discard the owner's queue next, so
    // the owner keeps its marker even when the delete was queued. The owner's
    // next sign-in replays it against the server copy; an absent task needs nothing.
    private suspend fun releasePendingTaskDeletionToOwner() {
        val committed = actionMutex.withLock {
            val deletion = pendingTaskDeletionWindow.take() ?: return@withLock false
            deletion.workspaceOwner == currentWorkspaceOwner() &&
                commitMarkedDeletionLocked(deletion.taskId, admitted = !mutationsBlocked()) ==
                MarkedDeletion.Committed
        }
        if (committed) afterLocalMutation()
    }

    // Markers without an open window come from a process that died during the
    // window, from an owner that left, or from a session that ended. They wait
    // until the workspace accepts mutations, so a loading workspace drops nothing.
    private suspend fun replayLeftoverTaskDeletion(replay: LeftoverReplay = LeftoverReplay.LocalWorkspace) {
        val committed = actionMutex.withLock {
            if (mutationsBlocked()) return@withLock false
            val owner = currentWorkspaceOwner() ?: return@withLock false
            finishLeftoverDeletionsLocked(owner, replay, admitted = true)
        }
        if (committed) afterLocalMutation()
    }

    private fun signedInReplay(): LeftoverReplay =
        if (authStatus == AuthStatus.SignedIn) LeftoverReplay.ServerCopy else LeftoverReplay.LocalWorkspace

    private suspend fun finishLeftoverDeletionsLocked(
        owner: String,
        replay: LeftoverReplay,
        admitted: Boolean,
    ): Boolean {
        val refusedTitles = mutableListOf<String>()
        val finishedTitles = mutableListOf<String>()
        pendingTaskDeletionWindow.resolveSuperseding(owner) { taskId -> tasks.any { it.id == taskId } }
        for (taskId in pendingTaskDeletionWindow.leftoverTaskIds(owner)) {
            val task = tasks.firstOrNull { it.id == taskId }
            // An account's task can be absent here only because its delete is queued, or
            // because a discarded queue still has it on the server; the sign-in decides.
            val waitsForServerCopy = owner != PendingTaskDeletionMarker.SignedOutWorkspaceOwner
            if (task == null && waitsForServerCopy && replay == LeftoverReplay.LocalWorkspace) continue
            val title = task?.title.orEmpty()
            when (endMarkedDeletionLocked(PendingTaskDeletionMarker(taskId, owner), admitted)) {
                MarkedDeletion.Refused -> refusedTitles += title
                MarkedDeletion.Committed -> finishedTitles += title
                MarkedDeletion.AlreadyGone -> Unit
            }
        }
        val announcedTitles = if (replay == LeftoverReplay.ServerCopy) finishedTitles else emptyList()
        announceLeftoverOutcomesLocked(refusedTitles, announcedTitles)
        return finishedTitles.isNotEmpty()
    }

    // UX round 2: one replay can refuse some markers and finish others, and the notice holds one
    // message. Every refused task gets its sentence, and the finished deletes follow on the next
    // line, so no outcome replaces another. A task that is already gone changes nothing on screen.
    private fun announceLeftoverOutcomesLocked(refusedTitles: List<String>, finishedTitles: List<String>) {
        val sentences = refusedTitles.map { appContext.getString(R.string.task_deletion_refused, it) } +
            listOfNotNull(laterDeletionSentence(finishedTitles))
        if (sentences.isEmpty()) return
        notice = sentences.joinToString(NoticeLineSeparator)
        noticeAction = null
        publish()
    }

    // A server-copy replay deletes a task the owner deleted on this device before it left the
    // account (logout, account switch or local reset). That can be long after the delete, so the
    // sentence names the task. Two or more finished deletes are counted; none ends silently.
    private fun laterDeletionSentence(titles: List<String>): String? = when (titles.size) {
        0 -> null
        1 -> appContext.getString(R.string.task_deletion_finished_later, titles.single())
        else -> appContext.resources.getQuantityString(
            R.plurals.task_deletion_finished_later_many,
            titles.size,
            titles.size,
        )
    }

    // A queued or absent task ends the marker. A refused delete ends it too: the
    // task shows again with a notice, and no later replay deletes it.
    private suspend fun finishMarkedDeletionLocked(
        marker: PendingTaskDeletionMarker,
        title: String,
        admitted: Boolean,
    ): Boolean {
        val outcome = endMarkedDeletionLocked(marker, admitted)
        if (outcome == MarkedDeletion.Refused) announceLeftoverOutcomesLocked(listOf(title), emptyList())
        return outcome == MarkedDeletion.Committed
    }

    private suspend fun endMarkedDeletionLocked(
        marker: PendingTaskDeletionMarker,
        admitted: Boolean,
    ): MarkedDeletion {
        val outcome = commitMarkedDeletionLocked(marker.taskId, admitted)
        pendingTaskDeletionWindow.forget(marker)
        return outcome
    }

    private suspend fun commitMarkedDeletionLocked(taskId: String, admitted: Boolean): MarkedDeletion {
        if (!::local.isInitialized) return MarkedDeletion.Refused
        val task = tasks.firstOrNull { it.id == taskId } ?: return MarkedDeletion.AlreadyGone
        if (!admitted) return MarkedDeletion.Refused
        val committed = commitTaskOperationLocked(TaskOperationType.Delete, task)
        return if (committed) MarkedDeletion.Committed else MarkedDeletion.Refused
    }

    // Session expiry and sign-in change the account state inside the action
    // lock, so the open window settles there, before user and owner change.
    // Only hard workspace blocks refuse it; Loading and SigningIn are the very
    // transitions being settled. No sync is requested: the session is ending,
    // the owner is leaving, or the sign-in syncs after its bootstrap.
    private suspend fun settleOpenTaskDeletionLocked(change: TaskDeletionSessionChange) {
        val deletion = pendingTaskDeletionWindow.take() ?: return
        if (deletion.workspaceOwner != currentWorkspaceOwner()) return
        when (commitMarkedDeletionLocked(deletion.taskId, admitted = !workspaceMutationsBlocked())) {
            MarkedDeletion.Refused -> if (change == TaskDeletionSessionChange.AccountJoins) {
                finishMarkedDeletionLocked(deletion.marker, deletion.title, admitted = false)
            } else {
                pendingTaskDeletionWindow.restore(deletion)
            }
            // The owner that leaves keeps its marker: confirming the switch discards its queue.
            else -> if (change != TaskDeletionSessionChange.OwnerLeaves) {
                pendingTaskDeletionWindow.forget(deletion.marker)
            }
        }
    }

    // UX round 2, L7: an account takes over the signed-out workspace, so its
    // leftover deletes complete for the local owner first and go with the takeover.
    // Rework2: logout clears the account's tasks from the device, so a task the
    // signed-out workspace still holds was created after the account's kept delete.
    // That create supersedes the delete, and the sign-in replay leaves the task alone.
    private suspend fun settleTaskDeletionsForAccountLocked(boundOwnerId: String?, accountId: String) {
        if (boundOwnerId != null && boundOwnerId != accountId) {
            settleOpenTaskDeletionLocked(TaskDeletionSessionChange.OwnerLeaves)
            return
        }
        settleOpenTaskDeletionLocked(TaskDeletionSessionChange.AccountJoins)
        if (boundOwnerId != null) return
        val owner = PendingTaskDeletionMarker.SignedOutWorkspaceOwner
        finishLeftoverDeletionsLocked(owner, LeftoverReplay.LocalWorkspace, admitted = !workspaceMutationsBlocked())
        pendingTaskDeletionWindow.supersedeLeftovers(accountId, tasks.map(FocusTask::id))
    }

    private fun currentWorkspaceOwner(): String? =
        if (::local.isInitialized) workspaceOwner() else null

    private fun workspaceOwner(): String = local.ownerUserId ?: PendingTaskDeletionMarker.SignedOutWorkspaceOwner

    // Mirrors the Core renameTask plan: identity hashes the title, so the
    // renamed record lands before the old one leaves, and a title that
    // already exists merges into that task. Selection moves through
    // selectTask so a running focus timer retargets like a manual switch.
    // Core 0.56.0: the old record leaves through a rename operation that
    // names the new title, so Core moves its finished history along.
    override suspend fun renameTask(taskId: String, title: String) {
        initialize()
        completePendingTaskDeletion()
        val current = tasks.firstOrNull { it.id == taskId } ?: return
        val renamed = taskFromSharedCore(title) ?: return
        if (renamed.id == taskId) return
        val wasSelected = selectedTaskId == taskId
        if (tasks.none { it.id == renamed.id }) {
            val upserted = issueTaskOperation(TaskOperationType.Upsert, renamed, identityValidated = true)
            if (!upserted) return
        }
        if (wasSelected) {
            selectTask(renamed.id)
            // A refused selection keeps both records rather than dropping
            // the selected task out from under a running timer.
            if (selectedTaskId != renamed.id) return
        }
        issueTaskOperation(TaskOperationType.Rename, current, renamedTitle = renamed.title)
    }

    override suspend fun finishExpiredTimer(): Boolean = finishExpiredTimer(expectedTimerId = null)

    internal suspend fun finishExpiredTimer(expectedTimerId: String?): Boolean {
        ensureLocalInitialized()
        val timer = actionMutex.withLock {
            if (localWorkspaceAdmissionBlocked()) return@withLock null
            expirableTimer()?.takeIf { expectedTimerId == null || it.id == expectedTimerId }
        } ?: return false
        if (!initializeReplication()) return false
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
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        CrashReporter.report(error)
        conflict = error.message ?: appContext.getString(R.string.iroh_room_projection_could_not_be_refreshed)
        publish()
        false
    }

    suspend fun rescheduleAlarmFromLocal() {
        ensureLocalInitialized()
        // R54-A08: a contained startup read failure, or a projection runtime that failed before
        // the read, leaves no local workspace to schedule from. The next successful local
        // initialization schedules the alarm.
        if (!::local.isInitialized) return
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
        if (mode == ReplicationMode.IROH && !legacyDependenciesAllowRoomEntry()) return
        val controller = replication ?: return
        completePendingTaskDeletion()
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
        if (accountNetworkBlocked() || !legacyDependenciesAllowRoomEntry()) return
        val controller = replication ?: return
        completePendingTaskDeletion()
        controller.createRoom(name)
        reloadWorkspace(ReplicationMode.IROH)
        centralizedSyncRuntime.requestRevisionClose()
    }

    override suspend fun joinIrohRoom(invite: String) =
        accountWorkspaceController.serialize { joinIrohRoomInternal(invite) }

    private suspend fun joinIrohRoomInternal(invite: String) {
        initialize()
        if (accountNetworkBlocked() || !legacyDependenciesAllowRoomEntry()) return
        val controller = replication ?: return
        completePendingTaskDeletion()
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
        completePendingTaskDeletion()
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

    override suspend fun confirmIrohIdentityRecovery() =
        accountWorkspaceController.serialize { confirmIrohIdentityRecoveryInternal() }

    private suspend fun confirmIrohIdentityRecoveryInternal() {
        initialize()
        if (accountNetworkBlocked()) return
        val controller = replication ?: return
        completePendingTaskDeletion()
        controller.confirmIdentityRecovery()
        reloadWorkspace(controller.mode)
        if (controller.mode != ReplicationMode.CENTRALIZED) {
            centralizedSyncRuntime.requestRevisionClose()
        }
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
        } catch (error: CancellationException) {
            throw error
        } catch (_: AuthenticationRequired) {
            // expected-silent: session expiry surfaces as resolution error, not a crash.
            handleAuthenticationRequired(identity, R.string.session_expired_sign_in_again_history_choice)
            return false
        } catch (error: Exception) {
            // R50-L02: transport stays quiet with retry; credential/Core/commit
            // gets one bounded stage+cause report.
            // expected-silent: refresh failure surfaces as resolution error with retry, not a crash.
            actionMutex.withLock {
                if (!isCurrent(identity)) return@withLock
                handleAccountBootstrapFailure(AccountBootstrapStage.ResolutionRecovery, error)
                historyResolution = historyResolution?.copy(
                    submitting = false,
                    error = failureNoticeText(error, R.string.could_not_refresh_remote_history),
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
        if (!legacyDependenciesAllowEgress()) return null
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
        } catch (error: CancellationException) {
            // A60 guard-first: toRequestStrict is pure today, so cancel must propagate.
            throw error
        } catch (_: Exception) {
            // expected-silent: corrupted saved resolution surfaces as corrupted UI, not a crash.
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
        } catch (error: CancellationException) {
            throw error
        } catch (_: AuthenticationRequired) {
            // expected-silent: session expiry surfaces as corrupted recovery, not a crash.
            expireResolutionRecovery(identity)
        } catch (error: Exception) {
            // R50-L02: transport stays quiet; credential/Core/commit gets one
            // bounded stage+cause report.
            // expected-silent: recovery refresh failure surfaces as recovery error, not a crash.
            reportAccountBootstrap(AccountBootstrapStage.ResolutionRecovery, identity, error)
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
        if (!legacyDependenciesAllowEgress()) return null
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
            // R54-A04: a refused clear expires the recovery like a clean one and asks for a reset.
            val retired = retireRejectedCredentials()
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
            if (!retired) notice = appContext.getString(R.string.unreadable_credential)
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
            error = failureNoticeText(error, R.string.could_not_refresh_account_history),
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
            error = failureNoticeText(error, R.string.queued_bootstrap_resolution_is_invalid),
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
        // The prepared resolution publishes the reconciled queues: only the
        // unpublished remainder keeps never-sent proof, in memory and in storage.
        val retainedProof = neverSentProof.retiredForResolution(request).filteredTo(reconciled.queues)
        val event = transitionCommitter.commit(
            RepositoryBootstrapPreparationTransition(
                update = BootstrapPreparationStorageUpdate(
                    local = reconciled.local,
                    pending = reconciled.queues,
                    commandDependencies = reconciled.dependencies,
                    resolution = resolution,
                    retainedNeverSent = retainedProof,
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
        // The prepared resolution publishes the reconciled queues: retire the
        // published never-sent proof now, so a resolution retry reconciles
        // the same ids as possibly delivered instead of claimed never-sent.
        neverSentProof = retainedProof
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
        } catch (error: CancellationException) {
            throw error
        } catch (error: BootstrapConflictException) {
            // expected-silent: server conflict surfaces as resolution UI, not a crash.
            handleBootstrapConflict(identity, error)
        } catch (_: AuthenticationRequired) {
            // expected-silent: session expiry surfaces as sign-in notice, not a crash.
            handleAuthenticationRequired(identity, R.string.session_expired_sign_in_again_history_choice)
        } catch (error: ApiException) {
            // expected-silent: server rejection surfaces as resolution UI, not a crash.
            handleResolutionApiFailure(identity, error)
        } catch (error: IOException) {
            // expected-silent: offline failure surfaces as resolution retry, not a crash.
            failBootstrapResolution(
                identity,
                failureNoticeText(error, R.string.could_not_finish_history_resolution_retry_same_request),
            )
        } catch (error: Exception) {
            // R50-A06: bounded stage+cause taxonomy; transport stays quiet.
            // expected-silent: bootstrap failure surfaces as resolution retry, not a crash.
            failBootstrapResolutionBounded(identity, error)
        }
    }

    // R50-A06: generic bootstrap-resolution failures share the bounded
    // ResolutionRecovery taxonomy instead of a raw CrashReporter report.
    private suspend fun failBootstrapResolutionBounded(
        identity: AccountAttemptIdentity,
        error: Exception,
    ) {
        reportAccountBootstrap(AccountBootstrapStage.ResolutionRecovery, identity, error)
        failBootstrapResolution(
            identity,
            failureNoticeText(error, R.string.history_resolution_failed_without_changing_local_data),
        )
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
            BootstrapConflictKind.Unknown ->
                appContext.getString(R.string.history_resolution_conflicted_choose_again)
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
                error = appContext.getString(R.string.could_not_finish_history_resolution_retry_same_request),
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
            requestAccountRestoreRetry()
        }
    }

    override fun onBackground() {
        centralizedSyncRuntime.markForeground(false)
        replication?.onBackground()
        centralizedSyncRuntime.resumeBackground()
    }

    private suspend fun restoreProfile() {
        connectivityRestoredDuringRestore.set(false)
        val identity = actionMutex.withLock { currentAttemptIdentity() }
        try {
            val profile = fetchValidatedProfile()
            val bootstrap = fetchTimedBootstrap()
            if (!completeAuthentication(profile, bootstrap.response, identity, bootstrap.clockSample)) {
                rearmHeldRestore(identity)
            }
        } catch (error: CancellationException) {
            networkInitializationStarted.set(false) // R51-A06: re-arm restore retry.
            throw error
        } catch (error: IOException) {
            // expected-silent: offline failure surfaces as a bootstrap notice, not a crash.
            failProfileRestore(identity, accountRestoreFailureNotice(error))
        } catch (_: AuthenticationRequired) {
            // expected-silent: session expiry surfaces as a bootstrap notice, not a crash.
            handleAuthenticationRequired(identity, R.string.session_expired_sign_in_again_history_choice)
        } catch (error: ProfileProtocolException) {
            // expected-silent: malformed profile surfaces as a bootstrap notice, not a crash.
            failProfileRestore(identity, accountRestoreFailureNotice(error), clearCredentials = true)
        } catch (error: Exception) {
            // R50-L02: transport stays quiet with notice; credential/Core/commit
            // gets one bounded stage+cause report.
            // expected-silent: bootstrap failure surfaces as a notice with retry, not a crash.
            reportAccountBootstrap(AccountBootstrapStage.Restore, identity, error)
            failProfileRestore(identity, accountRestoreFailureNotice(error))
        }
    }

    // A legacy hold is the only Stale reason that can clear later, so the restore re-arms and
    // the next foreground or connectivity retry runs it again once the gate opens. The other
    // Stale reasons are terminal or belong to a newer attempt, which owns the state.
    private suspend fun rearmHeldRestore(identity: AccountAttemptIdentity) = actionMutex.withLock {
        val heldNotice = heldLegacyDependencyNotice()
        if (!isCurrent(identity) || heldNotice == null) return@withLock
        networkInitializationStarted.set(false)
        resetSignedOutAuthentication(notice = heldNotice)
    }

    private suspend fun failProfileRestore(
        identity: AccountAttemptIdentity,
        failure: AccountFailureNotice,
        clearCredentials: Boolean = false,
    ) = actionMutex.withLock {
        if (!isCurrent(identity)) return@withLock
        if (clearCredentials && !retireRejectedCredentials()) {
            resetSignedOutAuthentication(appContext.getString(R.string.unreadable_credential))
            return@withLock
        }
        // R51-A06: bootstrap failure re-arms restore so an explicit
        // initialize() retries instead of stranding the account.
        networkInitializationStarted.set(false)
        resetSignedOutAfterAccountFailure(failure)
        requestRetryForConnectivityRestoredDuringRestore()
    }

    // R50-L02: stale attempts stay quiet; infra gets one bounded report.
    private fun reportAccountBootstrap(
        stage: AccountBootstrapStage,
        identity: AccountAttemptIdentity?,
        error: Exception,
    ) {
        if (identity != null && !isCurrent(identity)) return
        handleAccountBootstrapFailure(stage, error)
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
        if (!isCurrent(identity) || localMutationCorrupted || accountNetworkBlocked() ||
            authenticationHeldForLegacyDependencies()
        ) {
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
        settleTaskDeletionsForAccountLocked(boundOwnerId, profile.id)
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
        // R51-A06: durable bootstrap commit precedes generation fencing,
        // so a commit failure keeps the attempt identity current and its
        // handlers can publish, report, and re-arm retry.
        val attempt = applyAuthenticationPlan(
            profile,
            bootstrap,
            clockSample,
            storedResolution,
            plan,
        )
        installAuthenticatedProfile(profile, bootstrap, clockSample)
        authStatus = AuthStatus.SignedIn
        publish()
        scheduleAlarm()
        return resolveAuthenticationAttempt(attempt)
    }

    // A reload while the attempt was on the network can install a held queue. The recovery
    // refresh marked the dialog submitting, so a held completion releases it.
    private suspend fun authenticationHeldForLegacyDependencies(): Boolean {
        if (legacyDependenciesAllowEgress()) return false
        // The hold notice replaces the "refreshing account history" text that recovery set.
        historyResolution = historyResolution?.copy(submitting = false, error = heldLegacyDependencyNotice())
        publish()
        return true
    }

    // R51-A06: preparation captured the attempt before fencing, so
    // re-stamp it with the fenced generation for resolution ownership.
    private fun resolveAuthenticationAttempt(
        attempt: BootstrapResolutionAttempt?,
    ): AuthenticationCompletion {
        val resolved = attempt?.copy(
            accountGeneration = accountWorkspaceController.generation,
        )
        return resolved?.let(AuthenticationCompletion::Resolve)
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
        clearAccountRetryNotice()
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
        clearAccountRetryNotice()
    }

    // UX round 2, L1: Retry leaves its notice on screen until the retry has an
    // outcome. A completed sign-in or restore is that outcome for every notice
    // that offers an account retry.
    private fun clearAccountRetryNotice() {
        if (noticeAction != null) notice = null
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
        } catch (error: CancellationException) {
            // A63 guard-first (A60 pattern): pure callee, non-suspend caller.
            throw error
        } catch (_: Exception) {
            // expected-silent: corrupted saved resolution surfaces as corrupted UI, not a crash.
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
                retainedNeverSent = neverSentProof.filteredTo(application.pending.queues),
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
            val completionRequest = finishCompletionRequest(current, automatic = onlyIfExpired) ?: return@withLock
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
            try {
                val event = transitionCommitter.commit(RepositoryTimerCommandBatchTransition(mutation))
                clearMutationStorageNotice()
                installTimerMutation(event.plan)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
                return@withLock
            }
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

    // R54-A17: rebuilds project at the deterministic horizon, the newest
    // pending command time, so a timer whose start is still pending never
    // completes there. Core judges an automatic finish against a projection
    // at trusted now, as desktop does. A manual finish keeps the installed
    // projection, which Core does not read for manual requests.
    private fun finishCompletionRequest(
        current: CanonicalTimer,
        automatic: Boolean,
    ): CoreCommandRequestDecision? {
        val projectedTimer = if (automatic) {
            val nowMs = completionTrustedNowMs() ?: return null
            val projected = projectSynchronizedState(now = Instant.ofEpochMilli(nowMs))
            localizedProjectedTimer(projected.canonicalTimer, pending)
        } else {
            projection.timer
        }
        return coreCompletion.commandRequest(
            CoreCommandRequestInput(
                commandType = CommandType.Finish,
                requestedTimer = current,
                projectedTimer = projectedTimer,
                automatic = automatic,
                generateAutoBreak = true,
                autoStartBreaks = settings.autoStartBreaks,
                localDeviceId = local.deviceId,
                ownedTimerId = local.ownedTimerId,
            ),
        )
    }

    // R54-A17: without trusted now Core cannot judge expiry, so the automatic
    // finish waits. It sets no mutation fence: admitAccountInitialization
    // refuses while one is set, which would also block the account restore
    // that brings a fresh server sample. A manual finish still reports the
    // clock through reserveMutation.
    private fun completionTrustedNowMs(): Long? = try {
        trustedClock.now(local, retainedWallMs = retainedHlc().first)
    } catch (_: IllegalArgumentException) {
        // expected-silent: the foreground tick retries every 500 ms; a background alarm leaves the timer to it.
        null
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
        try {
            val event = transitionCommitter.commit(RepositoryTimerCommandTransition(mutation))
            clearMutationStorageNotice()
            installTimerMutation(event.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
            return false
        }
        return true
    }

    private fun installTimerMutation(mutation: TimerCommandMutationPlan) {
        local = mutation.local
        if (mutation.settings.selectedPhase != settings.selectedPhase) selectedPhaseGeneration += 1L
        settings = mutation.settings
        pending = pending + mutation.commands
        commandDependencies = commandDependencies + mutation.dependencies
        neverSentProof = neverSentProof.copy(commands = neverSentProof.commands + mutation.commands.map { it.id })
        installCoreProjection(mutation.projection)
        publish()
        scheduleAlarm()
    }

    private suspend fun issueTaskOperation(
        type: String,
        task: FocusTask,
        select: Boolean = false,
        identityValidated: Boolean = false,
        renamedTitle: String? = null,
    ): Boolean {
        val saved = actionMutex.withLock {
            !mutationsBlocked() && commitTaskOperationLocked(type, task, select, identityValidated, renamedTitle)
        }
        if (saved) afterLocalMutation()
        return saved
    }

    // The caller holds actionMutex and has admitted the mutation.
    private suspend fun commitTaskOperationLocked(
        type: String,
        task: FocusTask,
        select: Boolean = false,
        identityValidated: Boolean = false,
        renamedTitle: String? = null,
    ): Boolean {
        if (select && projection.timer?.status in activeStatuses) return false
        val authoritativeTask = authoritativeTask(task, identityValidated) ?: return false
        val state = timerMutationState()
        val changesSelection = select || TaskOperationType.removesTask(type) &&
            selectedTaskId == authoritativeTask.id
        val reservation = reserveMutation(
            count = if (changesSelection) 2 else 1,
            withDeviceSequences = false,
        ) ?: return false
        val mutation = plannedMutation {
            mutationCoordinator.task(
                TaskMutationInput(state, type, authoritativeTask, select, reservation, renamedTitle),
            )
        } ?: return false
        // UX round 2, rework2: saving a marked task supersedes its delete. A failed save keeps
        // the intent, and the next replay decides by whether the task is present.
        val superseded = PendingTaskDeletionMarker(authoritativeTask.id, workspaceOwner())
            .takeIf { type == TaskOperationType.Upsert && pendingTaskDeletionWindow.beginSupersede(it) }
        try {
            val event = transitionCommitter.commit(RepositoryTaskTransition(mutation))
            superseded?.let(pendingTaskDeletionWindow::supersede)
            clearMutationStorageNotice()
            installTaskMutation(event.plan)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
            return false
        }
        return true
    }

    private fun authoritativeTask(task: FocusTask, identityValidated: Boolean): FocusTask? {
        val authoritative = if (identityValidated) task else taskFromSharedCore(task.title) ?: return null
        if (authoritative.id == task.id) return authoritative
        CrashReporter.report(
            CoreProjectionException.InvalidOutput("Shared Core task identity mismatch"),
        )
        notice = appContext.getString(R.string.shared_core_invalid_output)
        publish()
        return null
    }

    private fun installTaskMutation(mutation: TaskMutationPlan) {
        local = mutation.local
        knownTasks = mutation.knownTasks
        pendingTaskOperations = mutation.taskOperations
        pendingSelectedTaskOperations = mutation.selectedTaskOperations
        neverSentProof = neverSentProof.copy(
            taskOperations = neverSentProof.taskOperations + mutation.operation.id,
            selectedTaskOperations = mutation.selectedOperation?.let {
                neverSentProof.selectedTaskOperations + it.id
            } ?: neverSentProof.selectedTaskOperations,
        )
        installCoreProjection(mutation.projection)
        publish()
    }

    private fun taskFromSharedCore(title: String): FocusTask? {
        val value = try {
            val input = json.encodeToString(mapOf("title" to title))
            sharedCoreDispatch?.invoke("task.identity.v1", input)
                ?: sharedCore.dispatch("task.identity.v1", input)
        } catch (error: SharedCoreException.Operation) {
            // expected-silent: invalid task input surfaces as validation notice, not a crash.
            notice = appContext.getString(
                R.string.task_must_contain_printable_text_and_fit_within_512_bytes,
            )
            publish()
            return null
        } catch (error: SharedCoreException) {
            CrashReporter.report(error)
            notice = appContext.getString(R.string.shared_core_unavailable)
            publish()
            return null
        }
        // A68 (A60 pattern): pure JSON field extract, so cancel cannot arise
        // today; the onFailure rethrow keeps the site consistent if either fact changes.
        val identity = runCatching {
            val output = value.jsonObject
            Triple(
                output["id"]?.jsonPrimitive?.contentOrNull,
                output["title"]?.jsonPrimitive?.contentOrNull,
                output["utf8Bytes"]?.jsonPrimitive?.intOrNull,
            )
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()
        val id = identity?.first
        val normalizedTitle = identity?.second
        val utf8Bytes = identity?.third
        if (
            id.isNullOrEmpty() || normalizedTitle.isNullOrEmpty() ||
            utf8Bytes == null ||
            utf8Bytes != normalizedTitle.toByteArray(StandardCharsets.UTF_8).size ||
            utf8Bytes > 512
        ) {
            CrashReporter.report(
                CoreProjectionException.InvalidOutput("Shared Core task identity output invalid"),
            )
            notice = appContext.getString(R.string.shared_core_invalid_output)
            publish()
            return null
        }
        return FocusTask(id, normalizedTitle)
    }

    // R52-A03: internal so the repo-boundary claim-persistence leg drives the real host.
    internal fun centralizedSyncRuntimeHost() = object : CentralizedSyncRuntimeHost {
        override fun snapshot(): CentralizedSyncRuntimeSnapshot = centralizedAccountSync.runtimeSnapshot(
            centralized = replicationMode() == ReplicationMode.CENTRALIZED,
            pendingQueuesEmpty = pendingQueuesEmpty(),
            localRevision = if (::local.isInitialized) local.revision else 0L,
            legacyAdoptionPending = ::local.isInitialized && LegacyDependencyStartup.pending(local),
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
                    notice = failureNoticeText(event.error, R.string.sync_stopped_after_a_local_failure)
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
        settleOpenTaskDeletionLocked(TaskDeletionSessionChange.SessionEnds)
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
        settleOpenTaskDeletionLocked(TaskDeletionSessionChange.SessionEnds)
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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CrashReporter.report(error)
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
            if (!workspaceReloadAdmitted(mode)) return@withLock
            installStoredWorkspace(timerStore.loadWorkspace())
            adoptReloadedLegacyDependencies()
        }
        if (mode == ReplicationMode.CENTRALIZED && foreground) {
            centralizedSyncRuntime.requestRevisionOpen()
        }
    }

    private fun workspaceReloadAdmitted(mode: ReplicationMode): Boolean =
        initialized.isCompleted && !localWorkspaceAdmissionBlocked() &&
            !(mode == ReplicationMode.CENTRALIZED && accountNetworkBlocked())

    private fun installStoredWorkspace(stored: StoredTimerWorkspace) {
        local = stored.local
        pending = stored.pending.commands
        commandDependencies = stored.commandDependencies
        pendingTaskOperations = stored.pending.taskOperations
        pendingDurationOperations = stored.pending.durationOperations
        pendingAutoStartOperations = stored.pending.autoStartOperations
        pendingSelectedTaskOperations = stored.pending.selectedTaskOperations
        pendingBootstrapResolution = stored.bootstrapResolution
        neverSentProof = stored.neverSent
        settings = stored.settings
        canonicalTimer = stored.canonicalTimer
        canonicalHistory = stored.canonicalHistory
        canonicalTasks = stored.canonicalTasks
        canonicalAutoStartBreaks = stored.canonicalAutoStartBreaks
        knownTasks = stored.knownTasks
        user = stored.user
        authStatus = if (user != null && auth.hasTokens()) AuthStatus.SignedIn else AuthStatus.SignedOut
        conflict = networkState.conflict?.let { appContext.getString(R.string.iroh_room_immutable_operation_conflict) }
        rebuildProjections()
        publish()
        scheduleAlarm()
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
        } else {
            requestAccountRestoreRetry()
        }
    }

    private fun pendingQueuesEmpty(): Boolean =
        pending.isEmpty() &&
            pendingTaskOperations.isEmpty() &&
            pendingDurationOperations.isEmpty() &&
            pendingAutoStartOperations.isEmpty() &&
            pendingSelectedTaskOperations.isEmpty()

    private suspend fun prepareSyncAttempt(identity: SyncAttemptIdentity): SyncAttempt? {
        if (historyResolution != null || accountSwitch != null) return null
        if (identity.accountGeneration != accountWorkspaceController.generation ||
            authStatus != AuthStatus.SignedIn || replicationMode() != ReplicationMode.CENTRALIZED
        ) return null
        // A queue without a recorded Core decision, or with a blocked one, never leaves the device.
        // Every held attempt retries a pending adoption and raises the notice again, so a held
        // sync is never silent and a failed record write does not hold the queue for good.
        val legacyGate = recordLegacyDependencyDecision()
        if (legacyGate != LegacyDependencyGate.Open) {
            holdLegacyDependencies(legacyGate)
            publish()
            return null
        }
        activeSyncAttempt = identity
        val attempt = centralizedSyncCoordinator.prepareSyncAttempt(
            CentralizedSyncAttemptInput(
                identity = identity,
                snapshot = centralizedSyncSnapshot(),
                sentPhysicalMs = currentTimeMillis(),
                sentElapsedRealtimeMs = elapsedRealtimeMillis(),
                nextDomain = syncNextDomain,
            ),
        )
        val nextLocal = local.copy(syncNextDomain = attempt.nextDomain)
        try {
            timerStore.retireNeverSent(attempt.request, nextLocal)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Claim proof must commit before send. A refused commit is a
            // sync failure with backoff, never successful empty work.
            CrashReporter.report(error)
            throw error
        }
        local = nextLocal
        syncNextDomain = attempt.nextDomain
        neverSentProof = neverSentProof.retiredFor(attempt.request)
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
            retainedNeverSent = neverSentProof.filteredTo(application.pending.queues),
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
        if (transition.restored) {
            connectivityRestoredDuringRestore.set(true)
            requestAccountRestoreRetry()
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
        neverSent = neverSentProof,
        dependencies = commandDependencies,
        canonicalTimer = canonicalTimer,
        canonicalHistory = canonicalHistory,
        canonicalTasks = canonicalTasks,
        canonicalAutoStartBreaks = canonicalAutoStartBreaks,
        knownTasks = knownTasks,
        settings = settings,
        selectedPhaseGeneration = selectedPhaseGeneration,
        projectionBase = currentProjectionBase(),
    )

    private fun timerMutationState() = TimerMutationState(
        local = local,
        settings = settings,
        projection = projection,
        projectionBase = currentProjectionBase(),
        queues = pendingSyncQueues(),
        neverSentProof = neverSentProof,
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
        neverSentProof = neverSentProof.filteredTo(reconciled.queues)
    }

    private fun projectSynchronizedState(
        base: CoreProjectionBase = currentProjectionBase(),
        queues: PendingSyncQueues = pendingSyncQueues(),
        now: Instant? = null,
    ): CoreProjectionResult {
        // R43-A03: rebuilds rerun the authoritative Core projection over safe
        // queues only. Without a trustworthy head there is no filtering yet.
        val safe = SafeProjectionPolicy.safeQueues(queues, neverSentProof, safeHeadOrNull())
        val request = SynchronizedProjectionRequestFactory.create(base, safe, local.deviceId)
        // R54-A17: null keeps the deterministic horizon. Only the automatic
        // completion request passes trusted now.
        return coreProjection.apply(
            base = request.base,
            pending = request.pending,
            now = now ?: request.horizon,
        )
    }

    private fun currentProjectionBase(): CoreProjectionBase {
        return CoreProjectionBase(
            canonicalTimer = canonicalTimer,
            history = canonicalHistory,
            tasks = canonicalTasks,
            durationsMs = safeBaseDurationsOrNull() ?: settings.effectiveDurationsMs(),
            autoStartBreaks = canonicalAutoStartBreaks,
            selectedTaskId = sanitizedBaseSelectedTaskId(),
        )
    }

    private fun sanitizedBaseSelectedTaskId(): String? {
        // R50-A02: covering head makes null authoritative (canonical No
        // task). Only legacy rows without a head fall back to optimistic
        // local, preserving full-Core equality for legacy provenance.
        // R52-A02: room selection comes from room state, never the
        // centralized safe base.
        if (!useCentralizedSafeBase()) {
            return local.selectedTaskId?.takeIf { id -> canonicalTasks.any { it.id == id } }
        }
        if (safeHeadOrNull() == null) {
            return local.selectedTaskId?.takeIf { id -> canonicalTasks.any { it.id == id } }
        }
        return local.safeBaseSelectedTaskId?.takeIf { id -> canonicalTasks.any { it.id == id } }
    }

    private fun safeHeadOrNull(): Pair<Long, Long>? = SafeProjectionPolicy.headOrNull(local)

    // R52-A02: centralized safe-base metadata belongs to the centralized
    // route. Room workspaces project room settings instead.
    private fun useCentralizedSafeBase(): Boolean = replicationMode() == ReplicationMode.CENTRALIZED

    // R50-A06: corrupt safe-base JSON keeps the effective-durations
    // fallback but gets one bounded decoding report, no payload.
    private fun safeBaseDurationsOrNull(): DurationsMs? {
        if (!useCentralizedSafeBase()) return null
        return local.safeBaseDurationsJson?.let {
            runCatching { strictJson.decodeFromString<DurationsMs>(it) }
                .onFailure { CorruptStateReporter.report(CorruptStateCategory.Decoding) }
                .getOrNull()
        }
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

    // R50-A06: planning shares the A04 commit split. SharedCore keeps
    // its invalid-output path; storage goes through the bounded
    // persistence reporter instead of misclassifying as Core output.
    private fun <T> mutationProjection(block: () -> T): T? = try {
        block().also { clearMutationFailure() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        publishMutationCommitFailure(error) // CrashReporter via MutationPersistenceReporter
        null
    }

    // UX round 2, B3: a notice shows a typed, localized sentence. The exception
    // message stays out of it.
    private fun failureNoticeText(error: Throwable, @StringRes fallback: Int): String =
        appContext.getString(failureSentence(error, fallback))

    private fun projectionFailureMessage(error: Throwable): String = when (error) {
        is SharedCoreException.Load, is SharedCoreException.Abi ->
            appContext.getString(R.string.shared_core_unavailable)
        else -> appContext.getString(R.string.shared_core_invalid_output)
    }

    // R50-A04: coherent recoverable persistence boundary. Commit stays
    // before install; storage retains committed state, publishes a
    // recoverable notice, and reports one bounded infrastructure event.
    // Cancellation propagates. SharedCore keeps its invalid-output path.
    private fun publishMutationCommitFailure(error: Exception) {
        if (error is SharedCoreException) {
            CrashReporter.report(error)
            val message = projectionFailureMessage(error)
            mutationFailure = message
            notice = message
        } else {
            handleMutationPersistenceFailure(error)
            notice = appContext.getString(R.string.sync_stopped_after_a_local_failure)
        }
        publish()
    }

    private fun clearMutationStorageNotice() {
        if (notice == appContext.getString(R.string.sync_stopped_after_a_local_failure)) {
            notice = null
        }
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
                    retainedNeverSent = neverSentProof.filteredTo(application.pending.queues),
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
        neverSentProof = neverSentProof.filteredTo(pendingSyncQueues())
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
            // expected-silent: clock out of bounds surfaces as blocked mutation, not a crash.
            mutationFailure = failureNoticeText(error, R.string.local_clock_out_of_range)
            notice = mutationFailure
            publish()
            return null
        }
        clearMutationFailure()
        return reservation
    }

    private fun reserveSelectionRetarget(): TimerMutationReservation? {
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
                count = 2,
            )
            val unsequenced = SyncWireBounds.mutationStamps(
                nowMs = nowMs,
                clocks = clocks,
                retainedDeviceSequence = local.deviceSequence,
                withDeviceSequences = false,
            )
            require(local.deviceSequence in 0 until SyncWireBounds.MaxSafeInteger) {
                "Device sequence overflow"
            }
            val stamps = listOf(unsequenced[0], unsequenced[1].copy(deviceSequence = local.deviceSequence + 1))
            val previous = previousUuidV7()
            val uuids = UuidV7.reserve(
                timestampMs = stamps.first().wallMs,
                count = 2,
                previous = previous,
                entropy = uuidEntropy,
            )
            TimerMutationReservation(stamps, uuids, uuids.last().toString())
        } catch (error: IllegalArgumentException) {
            // expected-silent: clock out of bounds surfaces as blocked mutation, not a crash.
            mutationFailure = failureNoticeText(error, R.string.local_clock_out_of_range)
            notice = mutationFailure
            publish()
            return null
        }
        clearMutationFailure()
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
        val queues = PendingSyncQueues(
            commands = repair.commands,
            taskOperations = repair.taskOperations,
            durationOperations = repair.durationOperations,
            autoStartOperations = repair.autoStartOperations,
            selectedTaskOperations = emptyList(),
        )
        timerStore.saveMutationState(
            repair.local,
            queues,
            commandDependencies,
            neverSentProof.filteredTo(queues),
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
    } catch (error: CancellationException) {
        // A72 guard-first (A60 pattern, siblings :1725/:2304): pure time math,
        // non-suspend caller, so cancel cannot arise today; rethrow keeps convention.
        throw error
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
            local.accountDeletionState != null || credentialRecoveryRequired || replicationStartupReadFailed ||
            LegacyDependencyStartup.blocked(local)

    private fun localWorkspaceAdmissionBlocked(allowWhileLoading: Boolean = true): Boolean =
        !::local.isInitialized || mutationFailure != null || mutationsBlocked(allowWhileLoading) ||
            auth.credentialState() !in setOf(AuthCredentialState.Empty, AuthCredentialState.Active)

    private fun mutationsBlocked(allowWhileLoading: Boolean = false): Boolean =
        workspaceMutationsBlocked() ||
            (!allowWhileLoading && authStatus == AuthStatus.Loading) ||
            authStatus == AuthStatus.SigningIn

    // The blocks of the workspace itself, apart from the Loading and SigningIn
    // account transitions.
    private fun workspaceMutationsBlocked(): Boolean {
        // R54-A09: a contained startup read failure, or a projection runtime that failed before
        // the read, leaves local unset; that blocks mutations.
        return !::local.isInitialized ||
            accountPublication.quarantined ||
            localMutationCorrupted ||
            LegacyDependencyStartup.blocked(local) ||
            credentialRecoveryRequired ||
            replicationStartupReadFailed ||
            local.accountDeletionState != null ||
            replication?.state?.value?.transitioning == true ||
            (replicationMode() == ReplicationMode.IROH && networkState.conflict != null) ||
            historyResolution != null ||
            accountSwitch != null
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
        requireSavedBootstrapReplayable(request)
        return request
    }

    private fun requireSavedBootstrapReplayable(request: BootstrapResolutionRequest) {
        // Fail-closed: an oversized saved claim stays durable with no send or
        // replacement. Callers map this to corrupted UI with Repreview recovery.
        val plan = coreBatchPlanner.checkSavedBootstrap(request)
        require(plan.status == "replay_saved") {
            "Saved bootstrap operations exceed the 8192 item aggregate limit"
        }
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

    // failure: the sentence and retry to show when the session ends inside an
    // attempt the user started. Background expiry passes none and stays quiet,
    // unless a notice still offers an account retry: that retry can no longer
    // work, so the notice offers a new sign-in instead (UX round 2, L1).
    private suspend fun handleAuthenticationRequired(
        identity: AccountAttemptIdentity,
        @StringRes message: Int,
        failure: AccountFailureNotice? = null,
    ) {
        var shouldCloseStream = false
        actionMutex.withLock {
            if (!isCurrent(identity)) return@withLock
            settleOpenTaskDeletionLocked(TaskDeletionSessionChange.SessionEnds)
            val retired = retireRejectedCredentials()
            accountWorkspaceController.expireAuthentication(
                AccountWorkspaceReason.AuthenticationExpired,
                discardBootstrap = true,
            )
            authStatus = AuthStatus.SignedOut
            user = null
            syncing = false
            retrying = false
            restorePendingResolutionForSignedOut(message)
            val shown = failure ?: SessionExpiredNotice.takeIf { noticeAction != null }
            if (!retired) {
                notice = appContext.getString(R.string.unreadable_credential)
            } else if (shown != null) {
                notice = appContext.getString(shown.sentence)
                noticeAction = shown.action
            }
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

    private fun restorePendingResolutionForSignedOut(@StringRes message: Int) {
        // A63 (A60 pattern): toRequestStrict is pure and this caller is
        // non-suspend, so cancel cannot arise today; the onFailure rethrow
        // keeps the site consistent if either fact changes.
        historyResolution = pendingBootstrapResolution?.let { stored ->
            runCatching { stored.toRequestStrict() }.fold(
                onSuccess = { request ->
                    HistoryResolutionState(
                        localHistoryCount = visibleHistoryCount(projection.history),
                        remoteHistoryCount = 0,
                        pendingStrategy = request.strategy,
                        requestId = request.requestId,
                        error = appContext.getString(message),
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    corruptedResolutionState()
                },
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
            ready = initialized.isCompleted && !replicationStartupReadFailed,
            localStorageReadFailed = (localStorageReadFailed && !initialized.isCompleted) ||
                replicationStartupReadFailed,
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
            noticeAction = noticeAction,
            deviceId = if (::local.isInitialized && !accountQuarantined) local.deviceId else "",
            network = if (accountQuarantined) IrohNetworkState() else networkState,
            skipDestinationPhase = if (accountQuarantined) null else skipDestinationPhase(),
        ))
    }

    // The idle hero offers "Skip to <phase>". Shared Core owns the destination
    // through timer.completionState.v1, so the long-break cadence is never
    // recomputed here. Publish runs often; the memo keeps one core call per
    // distinct (selected phase, history, local day). A core failure hides the
    // control instead of guessing.
    private fun skipDestinationPhase(): String? {
        if (!initialized.isCompleted || projection.timer?.status in activeStatuses) return null
        // R54-A16: without a workspace or after a recorded projection failure, the control hides
        // without a Core call, so a failed startup keeps its one bounded report.
        if (!::local.isInitialized || mutationFailure != null) return null
        val reference = Instant.ofEpochMilli(currentTimeMillis())
        val zoneId = ZoneId.systemDefault()
        val day = reference.atZone(zoneId).toLocalDate()
        val memo = skipDestinationMemo
        if (memo != null && memo.matches(settings.selectedPhase, projection.history, day)) return memo.phase
        val phase = try {
            coreSkipDestination.destination(
                CoreSkipDestinationInput(settings.selectedPhase, projection.history, reference, zoneId),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            CrashReporter.report(error)
            null
        }
        skipDestinationMemo = SkipDestinationMemo(settings.selectedPhase, projection.history, day, phase)
        return phase
    }

    private companion object {
        const val ConflictLogTag = "PomodoroughSync"
        const val MaxCommandsPerSync = 256
        const val MaxTaskOperationsPerSync = 256
        const val MaxDurationOperationsPerSync = 256
        const val MaxAutoStartOperationsPerSync = 256
        const val MaxSelectedTaskOperationsPerSync = 256
        const val CompletionAlertPreferences = "completion-alert"
        const val CompletionAlertTimerId = "timer-id"
        const val MaxBootstrapOperations = 4096
        const val MaxTimerDurationMs = 14_400_000L
        const val AccountDeletionPrepared = "prepared"
        const val AccountDeletionRemoteCommitted = "remote_committed"
        const val AccountLocalScrubRequired = "local_scrub_required"
        // Joins localized sentences into one notice, one per line; it carries no text of its own.
        const val NoticeLineSeparator = "\n"
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

private data class SkipDestinationMemo(
    val sourcePhase: String,
    val history: List<HistoryItem>,
    val day: LocalDate,
    val phase: String?,
) {
    fun matches(sourcePhase: String, history: List<HistoryItem>, day: LocalDate): Boolean =
        this.sourcePhase == sourcePhase && this.history == history && this.day == day
}
