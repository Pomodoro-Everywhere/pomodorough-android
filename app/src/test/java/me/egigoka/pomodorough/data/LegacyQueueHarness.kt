package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.protocol.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.protocol.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.LocalWorkspaceSnapshot
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.Request
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

/** The profile stored by the legacy witness; Core reads only its `id`. */
internal val LegacyProofProfile = User("account-proof", "proof@example.test", "Proof", "")

/** Ordered evidence from Core, storage and transport for one repository scenario. */
internal class LegacyQueueEvents {
    private val entries = CopyOnWriteArrayList<String>()

    fun record(entry: String) {
        entries += entry
    }

    fun all(): List<String> = entries.toList()

    fun since(mark: Int): List<String> = all().drop(mark)

    fun count(prefix: String): Int = entries.count { it.startsWith(prefix) }

    /** Syncs that carried queued rows; the empty startup sync carries none. */
    fun rowSyncs(): Int = entries.count { it.startsWith("sync:") && it != EmptySync }

    companion object {
        const val EmptySync = "sync:"
    }
}

/** Real Core, with every legacy adoption call recorded. A corrupt result gives invalid evidence. */
internal class LegacyCoreDispatch(
    private val core: (String, String) -> JsonElement,
    private val events: LegacyQueueEvents,
    private val corruptAdoption: Boolean = false,
) {
    fun dispatch(operation: String, body: String): JsonElement {
        if (operation != LegacyDependencyAdoption.Operation) return core(operation, body)
        events.record("adopt")
        return if (corruptAdoption) JsonObject(emptyMap()) else core(operation, body)
    }
}

/** Refuses the decision-record write while [refusals] stays positive. */
internal class FlakyLegacyDao(
    val delegate: TimerDao,
    private val events: LegacyQueueEvents,
) : TimerDao by delegate {
    val refusals = AtomicInteger(0)
    val installs = AtomicInteger(0)

    override suspend fun installLegacyDependencies(expected: LocalWorkspaceSnapshot, next: LocalStateEntity) {
        installs.incrementAndGet()
        events.record("install")
        if (refusals.getAndDecrement() > 0) throw IOException("legacy decision write refused")
        delegate.installLegacyDependencies(expected, next)
    }
}

/**
 * Holds tokens when [signedIn]. A sign-in fails as a transport error unless [signInSucceeds],
 * which stores tokens the way a completed Google exchange does.
 */
internal class LegacyQueueAuth(
    signedIn: Boolean,
    private val signInSucceeds: Boolean = false,
) : AuthSession {
    val signInCalls = AtomicInteger()
    @Volatile private var tokens = signedIn

    override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair {
        signInCalls.incrementAndGet()
        if (!signInSucceeds) throw IOException("sign-in transport is not part of this scenario")
        tokens = true
        return TokenPair("access", "2027-01-01T00:00:00Z", "refresh", "2027-01-01T00:00:00Z")
    }

    override fun hasTokens() = tokens
    override fun credentialState() = if (tokens) AuthCredentialState.Active else AuthCredentialState.Empty
    override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
    override suspend fun logout() = Unit
    override fun clear() = Unit
}

/**
 * Answers the empty startup sync. A sync that carries rows is recorded and then refused as a
 * terminal 422, so one send ends the runtime loop and each send is counted exactly once.
 */
internal class LegacyQueueServer(private val events: LegacyQueueEvents) : PomodoroughService {
    @Volatile var nextRowSyncFailure: Exception? = null

    /** While set, a bootstrap fetch waits here, so a scenario can act while it is in flight. */
    @Volatile var bootstrapGate: CompletableDeferred<Unit>? = null
    @Volatile var bootstrapEntered = CompletableDeferred<Unit>()

    override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
        val ids = request.commands.map { it.id } + request.taskOperations.map { it.id } +
            request.durationOperations.map { it.id } + request.autoStartOperations.map { it.id } +
            request.selectedTaskOperations.map { it.id }
        events.record("sync:" + ids.joinToString(","))
        if (ids.isEmpty()) return EmptyResponse
        nextRowSyncFailure?.let { failure ->
            nextRowSyncFailure = null
            throw failure
        }
        throw ApiException(422, "legacy scenario stops after one send")
    }

    override suspend fun me(accessToken: String): MeResponse {
        events.record("me")
        return MeResponse(LegacyProofProfile, "")
    }

    override suspend fun bootstrap(accessToken: String): SyncResponse {
        events.record("bootstrap")
        bootstrapEntered.complete(Unit)
        bootstrapGate?.await()
        return EmptyResponse
    }

    override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest): SyncResponse {
        events.record("resolveBootstrap")
        return EmptyResponse
    }

    override suspend fun createChallenge(): NativeChallenge = error("unused")
    override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("unused")
    override suspend fun refresh(refreshToken: String): TokenPair = error("unused")
    override suspend fun logout(accessToken: String) = Unit
    override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource = SilentRevisionStream

    private object SilentRevisionStream : EventSource {
        override fun request(): Request = Request.Builder().url("https://example.test/revisions").build()
        override fun cancel() = Unit
    }

    private companion object {
        val EmptyResponse = SyncResponse(
            acknowledgements = emptyList(),
            revision = 0,
            canonicalTimer = null,
            history = emptyList(),
            serverTime = "2026-08-31T12:00:00Z",
            serverHlcWallMs = AndroidLegacyDependencyWitness.Wall,
            serverHlcCounter = 0,
            durationAcknowledgements = emptyList(),
            durationsMs = DurationsMs(),
            taskAcknowledgements = emptyList(),
            tasks = emptyList(),
        )
    }
}

/**
 * Stands in for the Iroh controller. A route change stores [arriving] as the workspace the
 * new mode reloads, the way the real controller restores a room's return state. Every route
 * call is recorded, so a held room entry shows that the controller was never asked.
 */
internal class LegacyRouteController(
    private val dao: TimerDao,
    initialMode: ReplicationMode,
    private val events: LegacyQueueEvents? = null,
) : IrohReplicationController {
    override val state = MutableStateFlow(IrohNetworkState(mode = initialMode))
    @Volatile var arriving: suspend (TimerDao) -> Unit = {}

    override suspend fun initialize() = Unit

    override suspend fun setMode(mode: ReplicationMode) {
        events?.record("setMode:$mode")
        arrive(mode)
    }

    override suspend fun leaveRoom() {
        events?.record("leaveRoom")
        arrive(ReplicationMode.OFFLINE)
    }

    override suspend fun createRoom(name: String) {
        events?.record("createRoom")
        arrive(ReplicationMode.IROH)
    }

    override suspend fun joinRoom(invite: String) {
        events?.record("joinRoom")
        arrive(ReplicationMode.IROH)
    }

    override suspend fun refreshInvite(): Unit = error("unused")
    override suspend fun syncNow() = Unit
    override suspend fun afterLocalMutation() = Unit
    override suspend fun clearAccountData() = Unit
    override fun onForeground() = Unit

    private suspend fun arrive(mode: ReplicationMode) {
        arriving(dao)
        state.value = state.value.copy(mode = mode)
    }
}

/** One in-memory database, one Core, and every repository a scenario starts. */
internal class LegacyQueueHarness {
    val context: Context = ApplicationProvider.getApplicationContext()
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    val events = LegacyQueueEvents()
    val database: PomodoroughDatabase = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
        .allowMainThreadQueries().build()
    val dao = FlakyLegacyDao(database.timerDao(), events)
    val server = LegacyQueueServer(events)
    private val core = SharedCore.fromAssets(context.assets)
    private val repositories = mutableListOf<TimerRepository>()

    val notSaved: String get() = context.getString(R.string.legacy_dependency_recovery_not_saved)
    val recovery: String get() = context.getString(R.string.legacy_dependencies_need_recovery)

    init {
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(PendingTaskDeletionMarkerStore.PreferencesName, Context.MODE_PRIVATE)
            .edit().clear().commit()
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
    }

    fun dispatch(corruptAdoption: Boolean = false) = LegacyCoreDispatch(core::dispatch, events, corruptAdoption)

    fun repository(
        auth: AuthSession,
        dispatch: LegacyCoreDispatch = dispatch(),
        online: Boolean = false,
        replication: IrohReplicationController? = null,
        retryDelayMs: Long = 1_000L,
        remoteSyncIntervalMs: Long = 15_000L,
    ): TimerRepository = TimerRepository(
        context = context,
        dao = dao,
        api = server,
        auth = auth,
        json = json,
        networkAvailable = { online },
        currentTimeMillis = { java.time.Instant.parse(AndroidLegacyDependencyWitness.At).toEpochMilli() },
        initialSyncRetryDelayMs = retryDelayMs,
        remoteSyncIntervalMs = remoteSyncIntervalMs,
        sharedCoreDispatch = dispatch::dispatch,
        replication = replication,
    ).also(repositories::add)

    fun close() = runBlocking {
        repositories.asReversed().forEach { it.shutdownForTest() }
        repositories.clear()
        database.close()
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }
}

/**
 * Installs [witness] through a reload whose decision write keeps failing, so the retained
 * queue is held for adoption. Set [LegacyQueueHarness.dao] refusals to 0 to let it adopt.
 */
internal suspend fun LegacyQueueHarness.holdWitnessByReload(
    repository: TimerRepository,
    witness: LocalWorkspaceSnapshot,
    mode: ReplicationMode = ReplicationMode.CENTRALIZED,
) {
    dao.replaceWorkspace(witness)
    dao.refusals.set(Int.MAX_VALUE)
    repository.reloadWorkspace(mode)
}

/** A signed-in repository on a modern workspace, and the witness queue a later reload installs. */
internal data class LegacyArrival(val repository: TimerRepository, val witness: LocalWorkspaceSnapshot)

internal suspend fun LegacyQueueHarness.signedInArrival(
    dispatch: LegacyCoreDispatch = dispatch(),
    online: Boolean = false,
    retryDelayMs: Long = 1_000L,
): LegacyArrival {
    val witness = prepareLegacyArrival(dao, signedIn = true)
    val repository = repository(LegacyQueueAuth(signedIn = true), dispatch, online, retryDelayMs = retryDelayMs)
    repository.initialize()
    check(repository.state.value.authStatus == AuthStatus.SignedIn) { "The modern workspace must restore its account" }
    return LegacyArrival(repository, witness)
}

/** A signed-out repository on a modern workspace without an owner, and the witness queue. */
internal suspend fun LegacyQueueHarness.signedOutArrival(
    auth: LegacyQueueAuth = LegacyQueueAuth(signedIn = false),
    dispatch: LegacyCoreDispatch = dispatch(),
    replication: IrohReplicationController? = null,
): LegacyArrival {
    val witness = prepareLegacyArrival(dao, signedIn = false)
    val repository = repository(auth, dispatch, replication = replication)
    repository.initialize()
    check(repository.state.value.authStatus == AuthStatus.SignedOut) { "The modern workspace has no account" }
    return LegacyArrival(repository, witness)
}

/** A signed-in Iroh room on a modern workspace. Its next route change brings back the witness queue. */
internal data class LegacyRoomArrival(val repository: TimerRepository, val route: LegacyRouteController)

internal suspend fun LegacyQueueHarness.irohArrival(
    dispatch: LegacyCoreDispatch = dispatch(),
    remoteSyncIntervalMs: Long = 15_000L,
): LegacyRoomArrival {
    val witness = prepareLegacyArrival(dao, signedIn = true)
    val route = LegacyRouteController(dao, ReplicationMode.IROH, events)
    route.arriving = { arrivalDao -> arrivalDao.replaceWorkspace(witness) }
    val repository = repository(
        LegacyQueueAuth(signedIn = true),
        dispatch,
        online = true,
        replication = route,
        remoteSyncIntervalMs = remoteSyncIntervalMs,
    )
    repository.initialize()
    return LegacyRoomArrival(repository, route)
}

/** The stored workspace still carries the marker that asks Core for a decision. */
internal suspend fun TimerDao.legacyAdoptionPending(): Boolean =
    LegacyDependencyStartup.pending(checkNotNull(localState()))

private const val SendableAutoStartOperationId = "auto-start-proof"

/** Signs in without a credential screen; the auth fake decides how the exchange ends. */
internal object LegacyCredentialProvider : GoogleCredentialProvider {
    override suspend fun identityToken(serverClientId: String, nonce: String): String = "token"
}

/**
 * Stores the witness queue with its pending marker, and the full profile startup decodes.
 * The storage witness names its auto-start operation `auto-op`, which is shorter than the
 * 8-byte wire identifier minimum, so the send validator would refuse the queue before any row
 * sync. These scenarios send the adopted queue, so they store the same operation under a
 * sendable identifier.
 */
internal suspend fun seedLegacyWitness(dao: TimerDao) {
    AndroidLegacyDependencyWitness.seed(dao)
    val stored = dao.pendingAutoStartOperations().single()
    dao.deleteAllAutoStartOperations()
    dao.insertAutoStartOperation(stored.copy(id = SendableAutoStartOperationId))
    val profile = Json.encodeToString(User.serializer(), LegacyProofProfile)
    dao.updateState(checkNotNull(dao.localState()).copy(userJson = profile))
}

/**
 * Captures the witness workspace for a later route change, then leaves a modern workspace
 * with no queue and no markers. [signedIn] keeps the witness account as the owner.
 */
internal suspend fun prepareLegacyArrival(dao: TimerDao, signedIn: Boolean): LocalWorkspaceSnapshot {
    seedLegacyWitness(dao)
    val witness = dao.legacyDependencySnapshot()
    val profile = Json.encodeToString(User.serializer(), LegacyProofProfile)
    val modern = LocalStateEntity(
        deviceId = witness.local.deviceId,
        settingsJson = Json.encodeToString(TimerSettings.serializer(), TimerSettings()),
        userJson = profile.takeIf { signedIn },
        ownerUserId = LegacyProofProfile.id.takeIf { signedIn },
    )
    dao.replaceWorkspace(LocalWorkspaceSnapshot(modern))
    return witness
}

internal suspend fun awaitCondition(timeoutMs: Long = 8_000, condition: suspend () -> Boolean): Boolean =
    withTimeoutOrNull(timeoutMs) {
        while (!condition()) delay(10)
        true
    } == true

/**
 * The runtime runs on Dispatchers.IO, so a quiet window shows that no further attempt follows.
 * The cap keeps a looping mutant from hanging the suite; its extra events fail the assertions.
 */
internal suspend fun awaitQuiet(events: LegacyQueueEvents, quietMs: Long = 400, capMs: Long = 5_000) {
    withTimeoutOrNull(capMs) {
        var seen = -1
        while (seen != events.all().size) {
            seen = events.all().size
            delay(quietMs)
        }
    }
}
