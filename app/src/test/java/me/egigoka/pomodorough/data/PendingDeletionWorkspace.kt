package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingBootstrapResolutionEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.Assert.assertTrue

/**
 * UX round 2 test workspace for the durable task delete: real Room, the real Shared Core and the
 * real marker preferences. A second repository on the same workspace stands for the next process.
 * The scripted server answers as [AccountService.account]; with no account it is offline.
 */
internal class PendingDeletionWorkspace(
    private val account: User? = null,
    private val online: Boolean = false,
) {
    val context: Context = ApplicationProvider.getApplicationContext()
    val markers = PendingTaskDeletionMarkerStore(
        context.getSharedPreferences(PendingTaskDeletionMarkerStore.PreferencesName, Context.MODE_PRIVATE),
    )
    val auth = RecordingAuth(signedIn = account != null)
    val service = AccountService()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
        .allowMainThreadQueries().build()
    private val core = SharedCore.fromAssets(context.assets)
    private val repositories = mutableListOf<TimerRepository>()

    /** Core refuses every projection whose pending queue deletes one of these tasks, so that delete fails to commit. */
    @Volatile var refusedDeleteTaskIds: Set<String> = emptySet()

    /** The base selection of every projection that a history-resolution preparation ran, in order. */
    val bootstrapBaseSelections = CopyOnWriteArrayList<String?>()

    init {
        val state = LocalStateEntity(
            deviceId = "device-1",
            settingsJson = json.encodeToString(TimerSettings()),
            userJson = account?.let { json.encodeToString(it) },
            ownerUserId = account?.id,
        )
        runBlocking { database.timerDao().insertState(state) }
    }

    /** Starts the repository of a process. The earlier ones stay open, as a killed process leaves no trace. */
    suspend fun startProcess(replication: IrohReplicationController? = null): TimerRepository {
        val repository = TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = service,
            auth = auth,
            json = json,
            networkAvailable = { online },
            sharedCoreDispatch = ::dispatch,
            replication = replication,
        ).also(repositories::add)
        repository.initialize()
        return repository
    }

    private fun dispatch(operation: String, input: String): JsonElement {
        val refused = operation == "projection.apply.v2" && deletesARefusedTask(input)
        if (refused) throw IllegalArgumentException("test refuses the task delete")
        if (operation == "projection.apply.v2" && preparesABootstrapResolution()) {
            val base = json.parseToJsonElement(input).jsonObject.getValue("base").jsonObject
            bootstrapBaseSelections += base["selectedTaskId"]?.jsonPrimitive?.contentOrNull
        }
        return core.dispatch(operation, input)
    }

    // The projection dispatch is synchronous, so the coordinator's preparation is on the call stack.
    private fun preparesABootstrapResolution(): Boolean = Throwable().stackTrace.any {
        it.className == CentralizedSyncCoordinator::class.java.name && it.methodName == "prepareBootstrapResolution"
    }

    private fun deletesARefusedTask(input: String): Boolean {
        val refusedIds = refusedDeleteTaskIds
        if (refusedIds.isEmpty()) return false
        val pending = json.parseToJsonElement(input).jsonObject["pending"]?.jsonObject ?: return false
        val operations = pending["taskOperations"]?.jsonArray.orEmpty().map { it.jsonObject }
        return operations.any { operation ->
            operation["type"]?.jsonPrimitive?.content == TaskOperationType.Delete &&
                operation["taskId"]?.jsonPrimitive?.content in refusedIds
        }
    }

    suspend fun queuedTaskOperationTypes(): List<String> =
        database.timerDao().pendingTaskOperations().map { it.type }

    /** A route that swaps this workspace with a second one, as entering and leaving a room does. */
    fun workspaceSwap(initial: ReplicationMode) = WorkspaceSwapReplication(database.timerDao(), initial)

    /** Task ids of the durable markers that belong to [owner]. */
    fun markerTaskIds(owner: String): Set<String> = markers.read(owner)

    fun writeMarker(taskId: String, owner: String) = markers.write(PendingTaskDeletionMarker(taskId, owner))

    /** The canonical task that Core derives from [title], as the server stores it. */
    fun canonicalTask(title: String): FocusTask {
        val output = core.dispatch("task.identity.v1", json.encodeToString(mapOf("title" to title))).jsonObject
        return FocusTask(output.getValue("id").jsonPrimitive.content, output.getValue("title").jsonPrimitive.content)
    }

    /** The stored local workspace row, as the next process reads it. */
    suspend fun storedLocal(): LocalStateEntity = requireNotNull(database.timerDao().localState())

    /** Saves a history resolution of [owner] that the next process cannot decode, so it asks to review it again. */
    suspend fun saveCorruptedResolution(owner: User) = database.timerDao().upsertBootstrapResolution(
        PendingBootstrapResolutionEntity(
            requestId = "ux-r2-corrupted-resolution",
            deviceId = "device-1",
            expectedRevision = 0,
            strategy = "not-a-strategy",
            commandsJson = "[]",
            taskOperationsJson = "[]",
            durationOperationsJson = "[]",
            ownerUserId = owner.id,
            userJson = json.encodeToString(owner),
        ),
    )

    fun close() = runBlocking {
        repositories.asReversed().forEach { it.shutdownForTest() }
        database.close()
    }

    class RecordingAuth(signedIn: Boolean) : AuthSession {
        var onPrepareLogout: suspend () -> Unit = {}
        var onDeleteAccount: suspend () -> Unit = {}

        /** Holds auth.signIn, which runs while the repository is in SigningIn. */
        @Volatile var signInGate: CompletableDeferred<Unit>? = null

        /** The next clear() calls that fail, as an unreadable credential store does. */
        @Volatile var failingClears = 0

        @Volatile var active = signedIn

        /** The credential store cannot be read, as a corrupt store at startup. clear() repairs it. */
        @Volatile var unreadable = false

        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String): TokenPair {
            signInGate?.await()
            active = true
            return TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")
        }
        override fun hasTokens() = active
        override fun credentialState() = when {
            unreadable -> AuthCredentialState.Unreadable
            active -> AuthCredentialState.Active
            else -> AuthCredentialState.Empty
        }
        override suspend fun <T> authorized(block: suspend (String) -> T): T =
            if (active) block("token") else throw AuthenticationRequired()
        override fun prepareLogout() = runBlocking { onPrepareLogout() }
        override suspend fun logout() {
            active = false
        }
        override suspend fun retryPendingLogout() = Unit
        override fun hasPendingLogout() = false
        override suspend fun deleteAccount(confirmation: String) = onDeleteAccount()
        override fun clear() {
            if (failingClears > 0) {
                failingClears -= 1
                throw IOException("credential store unreadable")
            }
            unreadable = false
            active = false
        }
    }

    object CredentialProvider : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String) = "id-token"
    }

    /** Sync stays offline, so every queued operation stays in Room where the test can read it. */
    class AccountService : PomodoroughService {
        @Volatile var account: User? = null

        /** The profile request answers 401, as an expired session does. */
        @Volatile var profileExpired = false

        /** The sync request answers 401. */
        @Volatile var syncExpired = false

        /** The history-resolution request fails offline after it is recorded. */
        @Volatile var resolutionOffline = false

        /** Every history-resolution request the workspace sent, in order. */
        val resolutionRequests = CopyOnWriteArrayList<BootstrapResolutionRequest>()

        /** The tasks the server holds for each account id. */
        val tasks = ConcurrentHashMap<String, List<FocusTask>>()

        /** The task the server holds as selected for each account id. */
        val selectedTaskIds = ConcurrentHashMap<String, String>()

        override suspend fun me(accessToken: String): MeResponse {
            if (profileExpired) throw AuthenticationRequired()
            val user = account ?: throw IOException("offline")
            return MeResponse(user, "")
        }
        override suspend fun bootstrap(accessToken: String): SyncResponse = serverState()
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest): SyncResponse {
            resolutionRequests += request
            if (resolutionOffline) throw IOException("offline")
            val user = account ?: throw IOException("offline")
            return acceptResolution(user, request)
        }
        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
            if (syncExpired) throw AuthenticationRequired()
            throw IOException("offline")
        }
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("offline")

        /**
         * Applies the request as the real server does (server/internal/store/sync.go:798-863): the
         * newest operation per task, and the newest selected-task operation, is "applied"; every older
         * one is "ignored" with the server's reason. Core requires one acknowledgement per sent
         * operation (pomodorough-core/src/reconciliation/acknowledgements.rs validate_set). Timer,
         * duration and auto-start operations are not modelled, so the fixture fails the test loudly.
         */
        private fun acceptResolution(user: User, request: BootstrapResolutionRequest): SyncResponse {
            if (request.commands.isNotEmpty() || request.durationOperations.isNotEmpty() ||
                !request.autoStartOperations.isNullOrEmpty()
            ) throw AssertionError("the fake server models only task and selected-task operations")
            val taskWinners = request.taskOperations.groupBy(TaskOperation::taskId)
                .mapValues { (_, operations) -> operations.maxWith(TaskOperationOrder) }
            val tasksById = tasks[user.id].orEmpty().associateBy(FocusTask::id).toMutableMap()
            taskWinners.values.sortedWith(TaskOperationOrder).forEach { operation ->
                when (operation.type) {
                    TaskOperationType.Upsert -> tasksById[operation.taskId] =
                        FocusTask(operation.taskId, requireNotNull(operation.title))
                    TaskOperationType.Delete -> tasksById.remove(operation.taskId)
                    else -> throw AssertionError("the fake server does not model ${operation.type}")
                }
            }
            tasks[user.id] = tasksById.values.toList()
            val selected = request.selectedTaskOperations.orEmpty()
            val selectedWinner = selected.maxWithOrNull(SelectedTaskOperationOrder)
            selectedWinner?.let { winner ->
                winner.taskId?.let { selectedTaskIds[user.id] = it } ?: selectedTaskIds.remove(user.id)
            }
            return serverState().copy(
                taskAcknowledgements = request.taskOperations.map {
                    if (taskWinners[it.taskId] === it) TaskAcknowledgement(it.id, "applied")
                    else TaskAcknowledgement(it.id, "ignored", "superseded by newer task operation")
                },
                selectedTaskAcknowledgements = selected.map {
                    if (it === selectedWinner) SelectedTaskAcknowledgement(it.id, "applied")
                    else SelectedTaskAcknowledgement(it.id, "ignored", "superseded by newer selected-task operation")
                },
            )
        }

        private fun serverState(): SyncResponse {
            val user = account ?: throw IOException("offline")
            return SyncResponse(
                acknowledgements = emptyList(),
                revision = 0,
                canonicalTimer = null,
                history = emptyList(),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_000,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = emptyList(),
                tasks = tasks[user.id].orEmpty(),
                selectedTaskId = selectedTaskIds[user.id],
            )
        }
    }
}

// Core's operation_clock_key (pomodorough-core/src/sync_projection.rs:92) also orders by device id;
// one resolution request comes from one device, so the wall clock, counter and id decide.
private val TaskOperationOrder =
    compareBy<TaskOperation>({ it.hlcWallMs }, { it.hlcCounter }, { it.id })
private val SelectedTaskOperationOrder =
    compareBy<SelectedTaskOperation>({ it.hlcWallMs }, { it.hlcCounter }, { it.id })

internal suspend fun awaitDeletionCondition(label: String, condition: () -> Boolean) {
    val met = withTimeoutOrNull(10_000) {
        while (!condition()) delay(10)
        true
    }
    assertTrue("timed out waiting for $label", met == true)
}
