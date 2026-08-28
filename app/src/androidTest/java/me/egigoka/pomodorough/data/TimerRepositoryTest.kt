package me.egigoka.pomodorough.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.core.SharedCore
import me.egigoka.pomodorough.core.SharedCoreException
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.iroh.IrohNetworkState
import me.egigoka.pomodorough.data.iroh.IrohReplicationController
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PendingCommandEntity
import me.egigoka.pomodorough.data.local.PendingDurationOperationEntity
import me.egigoka.pomodorough.data.local.PendingTaskOperationEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimerRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() = runBlocking {
        repositories.asReversed().forEach { it.shutdownForTest() }
        repositories.clear()
        database.close()
    }

    @Test
    fun differentSignedInAccountRequiresExplicitDestructiveConfirmation() = runBlocking {
        val oldUser = user("old-user")
        val oldTimer = timer("old-timer")
        val oldSettings = TimerSettings(
            selectedPhase = TimerPhase.LongBreak,
            autoStartBreaks = true,
        ).withDurations(DurationsMs(focus = 45 * 60_000L))
        val state = state(oldUser, oldTimer).copy(settingsJson = json.encodeToString(oldSettings))
        database.timerDao().insertState(state)
        database.timerDao().insertCommand(PendingCommandEntity.from(command("old-command", "old-timer")))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation()))
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(durationOperation()),
        )
        val service = FakeService(user("new-user"))
        val repository = repository(service)

        repository.initialize()
        await { repository.state.value.authStatus == AuthStatus.SignedIn }

        assertEquals("old-user@example.com", repository.state.value.accountSwitch?.localAccount)
        assertEquals("new-user@example.com", repository.state.value.accountSwitch?.incomingAccount)
        assertEquals("old-user", database.timerDao().localState()?.ownerUserId)
        assertEquals(3, repository.state.value.pendingCount)
        assertEquals("old-timer", repository.state.value.timer?.id)

        repository.confirmAccountSwitch()

        val stored = database.timerDao().localState()
        assertNull(repository.state.value.accountSwitch)
        assertEquals("new-user", repository.state.value.user?.id)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals("new-user", stored?.ownerUserId)
        assertNull(stored?.canonicalTimerJson)
        assertEquals("[]", stored?.historyJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals(DurationsMs(), repository.state.value.settings.effectiveDurationsMs())
        assertEquals(TimerPhase.LongBreak, repository.state.value.settings.selectedPhase)
        assertTrue(!repository.state.value.settings.autoStartBreaks)
    }

    @Test
    fun taskMutationFailsClosedWhenSharedCoreIsUnavailable() = runBlocking {
        val account = user("account")
        database.timerDao().insertState(emptyState(account))
        val core = SharedCore.fromAssets(context.assets)
        var coreAvailable = true
        val repository = repository(
            FakeService(account),
            networkAvailable = { false },
            sharedCoreDispatch = { operation, input ->
                if (!coreAvailable) throw SharedCoreException.Load("core unavailable")
                core.dispatch(operation, input)
            },
        )

        repository.initialize()
        await { repository.state.value.authStatus == AuthStatus.SignedIn }
        coreAvailable = false

        assertFalse(repository.addTask("Blocked task"))
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertEquals(
            context.getString(R.string.shared_core_unavailable),
            repository.state.value.notice,
        )
    }

    @Test
    fun taskMutationUsesSharedCoreIdentityBeforePersistence() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val account = user("account")
        database.timerDao().insertState(emptyState(account))
        val core = SharedCore.fromAssets(context.assets)
        val expectedIdentity = core.dispatch(
            "task.identity.v1",
            json.encodeToString(mapOf("title" to "client task")),
        ).jsonObject
        val repository = repository(
            FakeService(account),
            networkAvailable = { false },
            sharedCoreDispatch = { operation, input ->
                calls += operation to input
                core.dispatch(operation, input)
            },
        )

        repository.initialize()
        await { repository.state.value.authStatus == AuthStatus.SignedIn }
        calls.clear()

        val added = repository.addTask("client task")
        assertTrue(repository.state.value.notice, added)

        val saved = database.timerDao().pendingTaskOperations().single().toModel()
        val selected = database.timerDao().pendingSelectedTaskOperations().single().toModel()
        assertEquals(
            listOf("task.identity.v1", "hlc.tick.v1", "hlc.tick.v1", "projection.apply.v2"),
            calls.map { it.first },
        )
        assertTrue(
            saved.hlcWallMs < selected.hlcWallMs ||
                saved.hlcWallMs == selected.hlcWallMs && saved.hlcCounter < selected.hlcCounter,
        )
        assertEquals(saved.taskId, selected.taskId)
        assertEquals(expectedIdentity.getValue("id").jsonPrimitive.content, saved.taskId)
        assertEquals(expectedIdentity.getValue("title").jsonPrimitive.content, saved.title)
    }

    @Test
    fun reconciliationFailureRollsBackCanonicalSyncAtomically() = runBlocking {
        val account = user("account")
        database.timerDao().insertState(state(account, timer("timer-1")))
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        val core = SharedCore.fromAssets(context.assets)
        var reconciliationCalls = 0
        val service = FakeService(account).apply {
            blockSync = true
            syncResponse = SyncResponse(
                acknowledgements = listOf(Acknowledgement("command-1", "applied", "")),
                revision = 5,
                canonicalTimer = timer("timer-1"),
                history = listOf(history("old-history")),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_000,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
        }
        val repository = repository(
            service,
            sharedCoreDispatch = { operation, input ->
                if (operation == "reconcile.rebase.v1" && ++reconciliationCalls == 2) {
                    throw SharedCoreException.Load("core unavailable")
                }
                core.dispatch(operation, input)
            },
        )

        repository.initialize()
        withTimeout(5_000) { service.syncStarted.await() }
        val localBefore = database.timerDao().localState()
        val commandsBefore = database.timerDao().pendingCommands()

        service.releaseSync.complete(Unit)
        val coreUnavailable = context.getString(R.string.shared_core_unavailable)
        await("reconciliation failure") { repository.state.value.conflict == coreUnavailable }

        assertEquals(SyncStatus.Conflict, repository.state.value.syncStatus)
        assertEquals(localBefore, database.timerDao().localState())
        assertEquals(commandsBefore, database.timerDao().pendingCommands())
        assertEquals(2, reconciliationCalls)
    }

    @Test
    fun projectionFailureRollsBackTimerAndSettingsMutations() = runBlocking {
        val account = user("account")
        database.timerDao().insertState(emptyState(account))
        val core = SharedCore.fromAssets(context.assets)
        var failProjection = false
        val repository = repository(
            FakeService(account),
            networkAvailable = { false },
            sharedCoreDispatch = { operation, input ->
                if (failProjection && operation == "projection.apply.v2") {
                    throw SharedCoreException.Load("core unavailable")
                }
                core.dispatch(operation, input)
            },
        )

        repository.initialize()
        val storedBefore = requireNotNull(database.timerDao().localState())
        failProjection = true

        repository.toggleTimer()
        repository.changeDuration(TimerPhase.Focus, 1)
        repository.setAutoStart(true)

        assertEquals(storedBefore, database.timerDao().localState())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertNull(repository.state.value.timer)
        assertEquals(DurationsMs(), repository.state.value.settings.effectiveDurationsMs())
        assertFalse(repository.state.value.settings.autoStartBreaks)
        assertEquals(
            context.getString(R.string.shared_core_unavailable),
            repository.state.value.notice,
        )
    }

    @Test
    fun projectionFailureAfterTaskIdentityRollsBackTaskAndSelection() = runBlocking {
        val account = user("account")
        database.timerDao().insertState(emptyState(account))
        val core = SharedCore.fromAssets(context.assets)
        var failProjection = false
        val repository = repository(
            FakeService(account),
            networkAvailable = { false },
            sharedCoreDispatch = { operation, input ->
                if (failProjection && operation == "projection.apply.v2") {
                    throw SharedCoreException.Load("core unavailable")
                }
                core.dispatch(operation, input)
            },
        )

        repository.initialize()
        val storedBefore = requireNotNull(database.timerDao().localState())
        failProjection = true

        assertFalse(repository.addTask("Blocked task"))

        assertEquals(storedBefore, database.timerDao().localState())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingSelectedTaskOperations().isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertNull(repository.state.value.selectedTaskId)
        assertEquals(
            context.getString(R.string.shared_core_unavailable),
            repository.state.value.notice,
        )
    }

    @Test
    fun successfulAccountDeletionScrubsCorruptedLocalAccountData() = runBlocking {
        val account = user("account-1")
        val malformed = state(account, timer("timer-1")).copy(settingsJson = "{malformed")
        database.timerDao().insertState(malformed)
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation()))
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(durationOperation()),
        )
        val auth = FakeAuthSession()
        val replication = FakeReplicationController()
        val repository = repository(FakeService(account), auth, replication = replication)

        repository.initialize()
        assertEquals(
            "Persisted timer state is corrupted. Sync and local mutations are blocked.",
            repository.state.value.conflict,
        )

        repository.deleteAccount("DELETE")

        val stored = requireNotNull(database.timerDao().localState())
        assertEquals(listOf("DELETE"), auth.deleteAccountConfirmations)
        assertEquals(1, auth.clearCalls)
        assertEquals(1, replication.clearAccountDataCalls)
        assertNull(stored.ownerUserId)
        assertNull(stored.userJson)
        assertNull(stored.canonicalTimerJson)
        assertEquals("[]", stored.historyJson)
        assertEquals("[]", stored.tasksJson)
        assertEquals("[]", stored.knownTasksJson)
        assertNull(stored.selectedTaskId)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.conflict)
    }

    @Test
    fun accountDeletionPersistsPreparedMarkerBeforeRemoteRequestCompletes() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(state(account, timer("timer-1")))
        val auth = FakeAuthSession().apply { blockDeleteAccount = true }
        val service = FakeService(account)
        val replication = FakeReplicationController()
        val repository = repository(service, auth, replication = replication)
        repository.initialize()

        val deletion = async { repository.deleteAccount("DELETE") }
        auth.deleteAccountStarted.await()
        val syncCallsAtQuarantine = service.syncCalls

        assertEquals("prepared", database.timerDao().localState()?.accountDeletionState)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(1, replication.quarantineAccountCalls)
        repository.scheduleWorkspaceReload()
        delay(100)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        repository.refresh()
        repository.onForeground()
        delay(100)
        assertEquals(syncCallsAtQuarantine, service.syncCalls)
        assertEquals(0, replication.foregroundCalls)
        auth.releaseDeleteAccount.complete(Unit)
        deletion.await()
        assertNull(database.timerDao().localState()?.accountDeletionState)
    }

    @Test
    fun staleRevisionAuthenticationFailureCannotStrandConfirmedDeletion() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(emptyState(account))
        val auth = FakeAuthSession().apply { blockDeleteAccount = true }
        val service = FakeService(account).apply { blockRevisionAuthentication = true }
        val replication = FakeReplicationController()
        val repository = repository(service, auth, replication = replication)
        repository.initialize()
        repository.onForeground()
        assertTrue(
            "revision stream did not begin opening",
            withTimeoutOrNull(5_000) { service.revisionStreamStarted.await(); true } == true,
        )

        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            repository.deleteAccount("DELETE")
        }
        assertTrue(
            "deletion did not persist its prepared marker",
            withTimeoutOrNull(5_000) {
                while (database.timerDao().localState()?.accountDeletionState != "prepared") yield()
                true
            } == true,
        )

        service.releaseRevisionAuthentication.complete(Unit)
        assertTrue(
            "deletion did not advance past the stale revision failure",
            withTimeoutOrNull(5_000) { auth.deleteAccountStarted.await(); true } == true,
        )
        auth.releaseDeleteAccount.complete(Unit)
        assertTrue(
            "deletion did not finish after confirmed remote success",
            withTimeoutOrNull(5_000) { deletion.await(); true } == true,
        )

        assertEquals(1, replication.clearAccountDataCalls)
        assertNull(database.timerDao().localState()?.accountDeletionState)
        assertNull(database.timerDao().localState()?.ownerUserId)
    }

    @Test
    fun revisionOpenAfterDeletionGenerationAdvanceIsRejectedBeforePreparedMarker() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(emptyState(account))
        val dao = PausingDeletionMarkerDao(database.timerDao())
        val auth = FakeAuthSession().apply { blockDeleteAccount = true }
        val service = FakeService(account).apply { failRevisionAuthentication = true }
        val replication = FakeReplicationController().apply { blockClearAccountData = true }
        val repository = repository(service, auth, replication = replication, dao = dao)
        repository.initialize()

        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            repository.deleteAccount("DELETE")
        }
        dao.preparedWriteStarted.await()

        repository.onForeground()
        repository.requestRevisionOpen()
        repository.awaitPendingRevisionSignals()

        assertEquals(0, service.revisionStreamCalls)
        dao.releasePreparedWrite.complete(Unit)
        auth.deleteAccountStarted.await()
        auth.releaseDeleteAccount.complete(Unit)
        replication.clearAccountDataStarted.await()

        assertEquals("remote_committed", database.timerDao().localState()?.accountDeletionState)
        assertFalse(deletion.isCompleted)

        replication.releaseClearAccountData.complete(Unit)
        deletion.await()
        assertNull(database.timerDao().localState()?.accountDeletionState)
        assertNull(database.timerDao().localState()?.ownerUserId)
    }

    @Test
    fun confirmedAccountSwitchWaitsForAdmittedRouteChange() = runBlocking {
        val oldUser = user("old-user")
        database.timerDao().insertState(state(oldUser, timer("old-timer")))
        val replication = FakeReplicationController().apply { blockSetMode = true }
        val repository = repository(FakeService(user("new-user")), replication = replication)
        repository.initialize()
        await { repository.state.value.accountSwitch != null }

        val routeChange = async(start = CoroutineStart.UNDISPATCHED) {
            repository.setReplicationMode(ReplicationMode.OFFLINE)
        }
        replication.setModeStarted.await()
        val accountSwitch = async(start = CoroutineStart.UNDISPATCHED) {
            repository.confirmAccountSwitch()
        }

        assertFalse("account switch did not wait for the admitted route change", accountSwitch.isCompleted)

        replication.releaseSetMode.complete(Unit)
        routeChange.await()
        accountSwitch.await()

        assertEquals("new-user", database.timerDao().localState()?.ownerUserId)
        assertNull(repository.state.value.accountSwitch)
    }

    @Test
    fun accountDeletionWaitsForAdmittedReplicationModeChange() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(state(account, timer("timer-1")))
        val auth = FakeAuthSession().apply { blockDeleteAccount = true }
        val replication = FakeReplicationController().apply { blockSetMode = true }
        val repository = repository(FakeService(account), auth, replication = replication)
        repository.initialize()

        val routeChange = async(start = CoroutineStart.UNDISPATCHED) {
            repository.setReplicationMode(ReplicationMode.OFFLINE)
        }
        replication.setModeStarted.await()
        val deletion = async(start = CoroutineStart.UNDISPATCHED) {
            repository.deleteAccount("DELETE")
        }

        assertFalse("account deletion did not wait for the admitted route change", deletion.isCompleted)
        assertFalse(auth.deleteAccountStarted.isCompleted)

        replication.releaseSetMode.complete(Unit)
        routeChange.await()
        auth.deleteAccountStarted.await()
        auth.releaseDeleteAccount.complete(Unit)
        deletion.await()

        assertNull(database.timerDao().localState()?.accountDeletionState)
        assertNull(database.timerDao().localState()?.ownerUserId)
    }

    @Test
    fun preparedDeletionMarkerQuarantinesColdStartWithoutNetworkingOrScrub() = runBlocking {
        val account = user("account-1")
        val task = FocusTask("aaf83054-24b2-4c0e-901f-a974147bfe82", "Private task")
        val stored = state(account, timer("timer-1")).copy(
            tasksJson = json.encodeToString(listOf(task)),
            knownTasksJson = json.encodeToString(listOf(task)),
            selectedTaskId = task.id,
            accountDeletionState = "prepared",
        )
        database.timerDao().insertState(stored)
        context.getSharedPreferences("completion-alert", Context.MODE_PRIVATE)
            .edit().putString("timer-id", "timer-1").commit()
        val service = FakeService(account)
        val repository = repository(service, FakeAuthSession())

        repository.initialize()

        assertEquals(stored, database.timerDao().localState())
        assertEquals(0, service.syncCalls)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertTrue(repository.state.value.knownTasks.isEmpty())
        assertNull(repository.state.value.selectedTaskId)
        assertNull(repository.state.value.completionAlertTimerId)
        assertEquals(TimerSettings(), repository.state.value.settings)
        assertEquals(0, repository.state.value.pendingCount)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertTrue(repository.state.value.notice.orEmpty().contains("outcome is unresolved"))

        repository.refresh()
        repository.onForeground()
        delay(100)

        assertEquals(0, service.syncCalls)
        repository.toggleTimer()
        repository.changeDuration(TimerPhase.Focus, 1)
        repository.setAutoStart(true)

        assertEquals(stored, database.timerDao().localState())
    }

    @Test
    fun foregroundBeforeInitializationDoesNotStartReplicationForQuarantinedAccount() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(emptyState(account).copy(accountDeletionState = "prepared"))
        val replication = FakeReplicationController()
        val repository = repository(
            FakeService(account),
            FakeAuthSession(),
            replication = replication,
        )

        repository.onForeground()
        repository.initialize()

        assertEquals(0, replication.foregroundCalls)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(repository.state.value.localAccountResetRequired)

        repository.setReplicationMode(ReplicationMode.OFFLINE)
        repository.createIrohRoom("Blocked")
        repository.joinIrohRoom("blocked-invite")
        repository.leaveIrohRoom()
        repository.refreshIrohInvite()
        repository.syncIrohNow()

        assertEquals(0, replication.routeOperationCalls)
    }

    @Test
    fun preparedDeletionWithoutCredentialsCanBeExplicitlyReset() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(emptyState(account).copy(accountDeletionState = "prepared"))
        val auth = FakeAuthSession().apply { tokensAvailable = false }
        val replication = FakeReplicationController()
        val repository = repository(FakeService(account), auth, replication = replication)

        repository.initialize()
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(repository.state.value.localAccountResetRequired)

        repository.resetLocalAccount()

        assertEquals(1, replication.clearAccountDataCalls)
        val reset = requireNotNull(database.timerDao().localState())
        assertNull(reset.accountDeletionState)
        assertNull(reset.ownerUserId)
        assertNull(reset.userJson)
        assertFalse(repository.state.value.localAccountResetRequired)
    }

    @Test
    fun committedDeletionMarkerFinishesLocalScrubOnColdStart() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(accountDeletionState = "remote_committed"),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        val auth = FakeAuthSession()
        val service = FakeService(account)
        val repository = repository(service, auth)

        repository.initialize()

        val stored = requireNotNull(database.timerDao().localState())
        assertNull(stored.accountDeletionState)
        assertNull(stored.ownerUserId)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(1, auth.clearCalls)
        assertEquals(0, service.syncCalls)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
    }

    @Test
    fun committedDeletionScrubFailureRemainsExplicitlyResettable() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(accountDeletionState = "remote_committed"),
        )
        val replication = FakeReplicationController().apply {
            clearAccountDataFailure = IllegalStateException("disk unavailable")
        }
        val repository = repository(FakeService(account), FakeAuthSession(), replication = replication)
        val observed = mutableListOf<AppState>()
        val collector = launch { repository.state.collect(observed::add) }
        yield()

        repository.initialize()
        yield()
        collector.cancel()

        assertEquals("remote_committed", database.timerDao().localState()?.accountDeletionState)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertTrue(observed.none { state ->
            state.user != null || state.timer != null || state.history.isNotEmpty() || state.tasks.isNotEmpty()
        })
        replication.clearAccountDataFailure = null

        repository.resetLocalAccount()

        assertEquals(2, replication.clearAccountDataCalls)
        assertNull(database.timerDao().localState()?.accountDeletionState)
        assertFalse(repository.state.value.localAccountResetRequired)
    }

    @Test
    fun preparedDeletionRejectsSignInBeforeRouteOrAuthenticationSideEffects() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(accountDeletionState = "prepared"),
        )
        val auth = FakeAuthSession()
        val replication = FakeReplicationController(initialMode = ReplicationMode.IROH)
        val repository = repository(FakeService(account), auth, replication = replication)

        repository.signIn(object : GoogleCredentialProvider {
            override suspend fun identityToken(serverClientId: String, nonce: String) = "unused"
        })

        assertEquals(0, auth.signInCalls)
        assertEquals(0, replication.routeOperationCalls)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertTrue(repository.state.value.localAccountResetRequired)
    }

    @Test
    fun unreadableCredentialsPublishExplicitLocalResetRecoveryWithoutNetworking() = runBlocking {
        val account = user("account-1")
        val stored = emptyState(account)
        database.timerDao().insertState(stored)
        val auth = FakeAuthSession().apply {
            credentialStateOverride = AuthCredentialState.Unreadable
        }
        val service = FakeService(account)
        val repository = repository(service, auth)

        repository.initialize()

        assertEquals(stored, database.timerDao().localState())
        assertEquals(0, service.syncCalls)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertTrue(repository.state.value.notice.orEmpty().contains("credentials are unreadable"))

        assertFalse(repository.addTask("Blocked task"))
        repository.changeDuration(TimerPhase.Focus, 1)
        repository.setAutoStart(true)

        assertEquals(stored, database.timerDao().localState())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        repository.resetLocalAccount()

        val reset = requireNotNull(database.timerDao().localState())
        assertEquals(1, auth.clearCalls)
        assertNull(reset.ownerUserId)
        assertNull(reset.userJson)
        assertFalse(repository.state.value.localAccountResetRequired)
    }

    @Test
    fun malformedAccountJsonStaysQuarantinedUntilExplicitReset() = runBlocking {
        val account = user("account-1")
        val malformed = state(account, timer("timer-1")).copy(userJson = "{malformed")
        database.timerDao().insertState(malformed)
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        val auth = FakeAuthSession()
        val service = FakeService(account)
        val repository = repository(service, auth)

        repository.initialize()

        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertTrue(auth.tokensAvailable)
        assertEquals(0, service.syncCalls)
        assertEquals(malformed, database.timerDao().localState())
        assertEquals(1, database.timerDao().pendingCommands().size)

        repository.resetLocalAccount()

        assertFalse(auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertFalse(repository.state.value.localAccountResetRequired)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(repository.addTask("Fresh after JSON reset"))
    }

    @Test
    fun malformedMutationRangeResetClearsCredentialsAndAccountData() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(
                deviceSequence = SyncWireBounds.MaxSafeInteger + 1,
                hlcWallMs = SyncWireBounds.MaxSafeInteger + 1,
                hlcCounter = SyncWireBounds.MaxSafeInteger + 1,
                lastUuidV7 = "018cc251-f400-7000-8000-000000000001",
            ),
        )
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        database.timerDao().insertTaskOperation(PendingTaskOperationEntity.from(taskOperation()))
        database.timerDao().upsertDurationOperation(
            PendingDurationOperationEntity.from(durationOperation()),
        )
        val auth = FakeAuthSession()
        val service = FakeService(account)
        val repository = repository(service, auth, networkAvailable = { false })

        repository.initialize()
        assertTrue(repository.state.value.localAccountResetRequired)
        assertEquals(0, service.syncCalls)

        repository.resetLocalAccount()

        val stored = requireNotNull(database.timerDao().localState())
        assertEquals(1, auth.logoutCalls)
        assertEquals(1, auth.clearCalls)
        assertFalse(auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertFalse(repository.state.value.localAccountResetRequired)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals(TimerSettings(), repository.state.value.settings)
        assertEquals(0L, stored.deviceSequence)
        assertEquals(0L, stored.hlcWallMs)
        assertEquals(0L, stored.hlcCounter)
        assertEquals(0L, stored.revision)
        assertNull(stored.lastUuidV7)
        assertNull(stored.ownerUserId)
        assertNull(stored.userJson)
        assertNull(stored.canonicalTimerJson)
        assertEquals("[]", stored.historyJson)
        assertEquals("[]", stored.tasksJson)
        assertEquals("[]", stored.knownTasksJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(database.timerDao().pendingTaskOperations().isEmpty())
        assertTrue(database.timerDao().pendingDurationOperations().isEmpty())
        assertTrue(database.timerDao().pendingAutoStartOperations().isEmpty())
        assertTrue(database.timerDao().pendingSelectedTaskOperations().isEmpty())
        assertNull(database.timerDao().pendingBootstrapResolution())
        assertTrue(repository.addTask("Fresh after range reset"))
    }

    @Test
    fun localAccountResetSurvivesProcessRestartWithFreshWorkspace() = runBlocking {
        val account = user("account-1")
        val credentialStore = FakeCredentialStore()
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(userJson = "{malformed"),
        )
        val firstAuth = FakeAuthSession(credentialStore)
        val firstRepository = repository(
            FakeService(account),
            firstAuth,
            networkAvailable = { false },
        )
        firstRepository.initialize()
        firstRepository.resetLocalAccount()

        val restartedAuth = FakeAuthSession(credentialStore)
        val restartedRepository = repository(
            FakeService(account),
            restartedAuth,
            networkAvailable = { false },
        )
        restartedRepository.initialize()

        assertNull(credentialStore.tokens)
        assertFalse(firstAuth.tokensAvailable)
        assertFalse(restartedAuth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, restartedRepository.state.value.authStatus)
        assertFalse(restartedRepository.state.value.localAccountResetRequired)
        assertNull(restartedRepository.state.value.conflict)
        assertNull(restartedRepository.state.value.user)
        assertNull(restartedRepository.state.value.timer)
        assertEquals(DurationsMs(), restartedRepository.state.value.settings.effectiveDurationsMs())
        assertEquals(TimerPhase.Focus, restartedRepository.state.value.settings.selectedPhase)
        assertFalse(restartedRepository.state.value.settings.autoStartBreaks)
        assertTrue(restartedRepository.addTask("Fresh local task"))
        assertEquals(listOf("Fresh local task"), restartedRepository.state.value.tasks.map(FocusTask::title))
    }

    @Test
    fun offlineLocalAccountResetClearsCredentialsWhenRemoteLogoutFails() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(
            state(account, timer("timer-1")).copy(userJson = "{malformed"),
        )
        val auth = FakeAuthSession().apply {
            logoutFailure = IOException("offline")
        }
        val repository = repository(
            FakeService(account),
            auth,
            networkAvailable = { false },
        )

        repository.initialize()
        repository.resetLocalAccount()

        assertEquals(1, auth.logoutCalls)
        assertEquals(1, auth.clearCalls)
        assertFalse(auth.tokensAvailable)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertFalse(repository.state.value.localAccountResetRequired)
        assertEquals(
            context.getString(R.string.local_account_reset_complete_remote_logout_not_confirmed),
            repository.state.value.notice,
        )
        assertNull(database.timerDao().localState()?.ownerUserId)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertTrue(repository.addTask("Fresh offline task"))
    }

    @Test
    fun lateSyncResponseCannotRestoreLoggedOutAccount() = runBlocking {
        val account = user("account-1")
        val running = timer("timer-1")
        database.timerDao().insertState(state(account, running))
        database.timerDao().insertCommand(PendingCommandEntity.from(command("command-1", "timer-1")))
        val service = FakeService(account).apply {
            syncResponse = SyncResponse(
                acknowledgements = listOf(Acknowledgement("command-1", "applied", "")),
                revision = 99,
                canonicalTimer = timer("server-timer"),
                history = listOf(history("server-history")),
                serverTime = "2026-01-01T00:00:00Z",
                serverHlcWallMs = 1_767_225_600_000,
                serverHlcCounter = 0,
                durationAcknowledgements = emptyList(),
                durationsMs = DurationsMs(),
                taskAcknowledgements = emptyList(),
                tasks = emptyList(),
            )
            blockSync = true
        }
        val auth = FakeAuthSession()
        val replication = FakeReplicationController()
        val repository = repository(service, auth, replication = replication)

        repository.initialize()
        withTimeout(5_000) { service.syncStarted.await() }
        repository.logout()
        service.releaseSync.complete(Unit)
        withTimeout(5_000) { service.syncCompleted.await() }

        val stored = database.timerDao().localState()
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertEquals(0, repository.state.value.pendingCount)
        assertEquals(0L, stored?.revision)
        assertNull(stored?.ownerUserId)
        assertNull(stored?.canonicalTimerJson)
        assertEquals("[]", stored?.historyJson)
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(1, auth.logoutCalls)
        assertEquals(1, replication.clearAccountDataCalls)
    }

    @Test
    fun failedLogoutScrubRemainsDurablyResettableAfterRestart() = runBlocking {
        val account = user("account-1")
        database.timerDao().insertState(state(account, timer("timer-1")))
        val auth = FakeAuthSession()
        val failingReplication = FakeReplicationController().apply {
            clearAccountDataFailure = IOException("scrub unavailable")
        }
        val repository = repository(
            FakeService(account),
            auth,
            replication = failingReplication,
        )
        repository.initialize()

        repository.logout()

        assertEquals("local_scrub_required", database.timerDao().localState()?.accountDeletionState)
        assertEquals(account.id, database.timerDao().localState()?.ownerUserId)
        assertEquals(AuthStatus.SignedOut, repository.state.value.authStatus)
        assertNull(repository.state.value.user)
        assertNull(repository.state.value.timer)
        assertTrue(repository.state.value.history.isEmpty())
        assertTrue(repository.state.value.tasks.isEmpty())
        assertEquals(0, repository.state.value.pendingCount)
        assertTrue(repository.state.value.localAccountResetRequired)
        var notificationCalls = 0
        val completionAlertShown = repository.showCompletionAlert("timer-1") {
            notificationCalls += 1
            true
        }
        assertFalse(completionAlertShown)
        assertEquals(0, notificationCalls)
        assertNull(repository.state.value.completionAlertTimerId)
        assertNull(
            context.getSharedPreferences("completion-alert", Context.MODE_PRIVATE)
                .getString("timer-id", null),
        )

        auth.credentialStateOverride = AuthCredentialState.LogoutPending
        val recoveryReplication = FakeReplicationController()
        val restarted = repository(
            FakeService(account),
            auth,
            replication = recoveryReplication,
        )
        restarted.initialize()

        assertEquals(AuthStatus.SignedOut, restarted.state.value.authStatus)
        assertNull(restarted.state.value.user)
        assertTrue(restarted.state.value.localAccountResetRequired)
        restarted.resetLocalAccount()
        assertEquals(1, recoveryReplication.clearAccountDataCalls)
        assertNull(database.timerDao().localState()?.accountDeletionState)
        assertNull(database.timerDao().localState()?.ownerUserId)
    }

    @Test
    fun immediateDifferentAccountSignInSurvivesDelayedOldAccountLogout() = runBlocking {
        val accountA = user("account-a")
        val accountB = user("account-b")
        database.timerDao().insertState(emptyState(accountA))
        val service = FakeService(accountA)
        val auth = FakeAuthSession().apply { blockLogout = true }
        val repository = repository(service, auth)

        repository.initialize()
        await("account A initialization") {
            repository.state.value.authStatus == AuthStatus.SignedIn
        }
        repository.logout()
        assertTrue("old-account logout did not start", auth.logoutStarted.isCompleted)

        service.profile = accountB
        repository.signIn(object : GoogleCredentialProvider {
            override suspend fun identityToken(serverClientId: String, nonce: String) = "account-b-id-token"
        })
        await("account B sign-in") {
            repository.state.value.authStatus == AuthStatus.SignedIn
        }

        auth.releaseLogout.complete(Unit)
        withTimeout(5_000) { auth.logoutCompleted.await() }

        assertEquals(1, auth.logoutCalls)
        assertEquals(1, auth.signInCalls)
        assertTrue(auth.tokensAvailable)
        assertEquals(accountB, repository.state.value.user)
        assertEquals(accountB.id, database.timerDao().localState()?.ownerUserId)
        assertNull(repository.state.value.timer)
    }

    private fun repository(
        service: FakeService,
        auth: FakeAuthSession = FakeAuthSession(),
        networkAvailable: () -> Boolean = { true },
        sharedCoreDispatch: ((String, String) -> JsonElement)? = null,
        replication: IrohReplicationController? = null,
        dao: TimerDao = database.timerDao(),
    ) = TimerRepository(
        context = context,
        dao = dao,
        api = service,
        auth = auth,
        json = json,
        networkAvailable = networkAvailable,
        sharedCoreDispatch = sharedCoreDispatch,
        replication = replication,
    ).also(repositories::add)

    private suspend fun await(label: String = "condition", condition: () -> Boolean) {
        val completed = withTimeoutOrNull(5_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("Timed out waiting for $label", completed == true)
    }

    private fun state(user: User, timer: CanonicalTimer) = LocalStateEntity(
        deviceId = "device-1",
        revision = 4,
        canonicalTimerJson = json.encodeToString(timer),
        historyJson = json.encodeToString(listOf(history("old-history"))),
        settingsJson = json.encodeToString(TimerSettings()),
        userJson = json.encodeToString(user),
        ownerUserId = user.id,
    )

    private fun emptyState(user: User) = LocalStateEntity(
        deviceId = "device-1",
        settingsJson = json.encodeToString(TimerSettings()),
        userJson = json.encodeToString(user),
        ownerUserId = user.id,
    )

    private fun user(id: String) = User(
        id = id,
        email = "$id@example.com",
        name = id,
        avatarUrl = "",
    )

    private fun timer(id: String) = CanonicalTimer(
        id = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Running,
        plannedDurationMs = 1_500_000,
        elapsedAtAnchorMs = 0,
        anchorAt = Instant.now().toString(),
    )

    private fun history(id: String) = HistoryItem(
        id = id,
        timerId = id,
        phase = TimerPhase.Focus,
        status = TimerStatus.Completed,
        plannedDurationMs = 1_500_000,
        completedAt = "2026-01-01T00:25:00Z",
    )

    private fun command(id: String, timerId: String) = TimerCommand(
        id = id,
        deviceSequence = 1,
        timerId = timerId,
        type = CommandType.Pause,
        phase = TimerPhase.Focus,
        plannedDurationMs = 1_500_000,
        occurredAt = "2026-01-01T00:05:00Z",
        hlcWallMs = 1_767_225_900_000,
        hlcCounter = 0,
        observedElapsedMs = 300_000,
    )

    private fun taskOperation() = TaskOperation(
        id = "task-operation-1",
        taskId = "aaf83054-24b2-8c0e-901f-a974147bfe82",
        type = TaskOperationType.Upsert,
        title = "Café",
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_000,
        hlcCounter = 0,
    )

    private fun durationOperation() = DurationOperation(
        id = "duration-operation-1",
        phase = TimerPhase.Focus,
        durationMs = 1_800_000,
        occurredAt = "2026-01-01T00:00:00Z",
        hlcWallMs = 1_767_225_600_001,
        hlcCounter = 0,
    )

    private class FakeReplicationController(
        initialMode: ReplicationMode = ReplicationMode.CENTRALIZED,
    ) : IrohReplicationController {
        private val mutableState = MutableStateFlow(IrohNetworkState(mode = initialMode))
        override val state: StateFlow<IrohNetworkState> = mutableState
        var clearAccountDataCalls = 0
        var clearAccountDataFailure: Exception? = null
        var quarantineAccountCalls = 0
        var foregroundCalls = 0
        var routeOperationCalls = 0
        var blockSetMode = false
        val setModeStarted = CompletableDeferred<Unit>()
        val releaseSetMode = CompletableDeferred<Unit>()
        var blockClearAccountData = false
        val clearAccountDataStarted = CompletableDeferred<Unit>()
        val releaseClearAccountData = CompletableDeferred<Unit>()

        override suspend fun initialize() = Unit
        override suspend fun setMode(mode: ReplicationMode) {
            routeOperationCalls += 1
            setModeStarted.complete(Unit)
            if (blockSetMode) releaseSetMode.await()
            mutableState.value = mutableState.value.copy(mode = mode)
        }
        override suspend fun createRoom(name: String) { routeOperationCalls += 1 }
        override suspend fun joinRoom(invite: String) { routeOperationCalls += 1 }
        override suspend fun leaveRoom() { routeOperationCalls += 1 }
        override suspend fun refreshInvite() { routeOperationCalls += 1 }
        override suspend fun syncNow() { routeOperationCalls += 1 }
        override suspend fun afterLocalMutation() = Unit
        override suspend fun quarantineAccount() {
            quarantineAccountCalls += 1
        }
        override suspend fun clearAccountData() {
            clearAccountDataCalls += 1
            clearAccountDataStarted.complete(Unit)
            if (blockClearAccountData) releaseClearAccountData.await()
            clearAccountDataFailure?.let { throw it }
        }
        override fun onForeground() {
            foregroundCalls += 1
        }
    }

    private class FakeCredentialStore(
        var tokens: TokenPair? = TokenPair(
            accessToken = "access-token",
            accessTokenExpiresAt = "2999-01-01T00:00:00Z",
            refreshToken = "refresh-token",
            refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
        ),
    )

    private class FakeAuthSession(
        private val credentialStore: FakeCredentialStore = FakeCredentialStore(),
    ) : AuthSession {
        var logoutCalls = 0
        var clearCalls = 0
        var signInCalls = 0
        var credentialStateOverride: AuthCredentialState? = null
        var tokensAvailable: Boolean
            get() = credentialStore.tokens != null
            set(value) {
                if (!value) credentialStore.tokens = null
            }
        var blockLogout = false
        var logoutFailure: Exception? = null
        val logoutStarted = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val logoutCompleted = CompletableDeferred<Unit>()
        val deleteAccountConfirmations = mutableListOf<String>()
        var blockDeleteAccount = false
        val deleteAccountStarted = CompletableDeferred<Unit>()
        val releaseDeleteAccount = CompletableDeferred<Unit>()

        override suspend fun signIn(
            credentialProvider: GoogleCredentialProvider,
            deviceId: String,
        ): TokenPair {
            signInCalls += 1
            return TokenPair(
                accessToken = "new-access-token",
                accessTokenExpiresAt = "2999-01-01T00:00:00Z",
                refreshToken = "new-refresh-token",
                refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
            ).also { credentialStore.tokens = it }
        }
        override fun hasTokens(): Boolean = tokensAvailable
        override fun credentialState(): AuthCredentialState = credentialStateOverride ?: if (hasTokens()) {
            AuthCredentialState.Active
        } else {
            AuthCredentialState.Empty
        }
        override suspend fun <T> authorized(block: suspend (String) -> T): T = block("access-token")
        override suspend fun logout() {
            try {
                logoutCalls += 1
                logoutStarted.complete(Unit)
                logoutFailure?.let { throw it }
                credentialStore.tokens = null
                if (blockLogout) releaseLogout.await()
            } finally {
                logoutCompleted.complete(Unit)
            }
        }
        override suspend fun deleteAccount(confirmation: String) {
            deleteAccountConfirmations += confirmation
            deleteAccountStarted.complete(Unit)
            if (blockDeleteAccount) releaseDeleteAccount.await()
        }
        override fun clear() {
            clearCalls += 1
            credentialStore.tokens = null
        }
    }

    private class FakeService(var profile: User) : PomodoroughService {
        var blockSync = false
        var syncCalls = 0
        val syncStarted = CompletableDeferred<Unit>()
        val releaseSync = CompletableDeferred<Unit>()
        val syncCompleted = CompletableDeferred<Unit>()
        var blockRevisionAuthentication = false
        var failRevisionAuthentication = false
        var revisionStreamCalls = 0
        val revisionStreamStarted = CompletableDeferred<Unit>()
        val releaseRevisionAuthentication = CompletableDeferred<Unit>()
        var syncResponse = SyncResponse(
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
            tasks = emptyList(),
        )

        override suspend fun me(accessToken: String) = MeResponse(profile, "")

        override suspend fun bootstrap(accessToken: String): SyncResponse = syncResponse.copy(
            acknowledgements = emptyList(),
            durationAcknowledgements = emptyList(),
            taskAcknowledgements = emptyList(),
        )

        override suspend fun resolveBootstrap(
            accessToken: String,
            request: BootstrapResolutionRequest,
        ): SyncResponse = syncResponse

        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse {
            return try {
                syncCalls += 1
                syncStarted.complete(Unit)
                if (blockSync) releaseSync.await()
                syncResponse
            } finally {
                syncCompleted.complete(Unit)
            }
        }

        override suspend fun createChallenge(): NativeChallenge = error("Unused")
        override suspend fun exchange(request: NativeExchangeRequest): TokenPair = error("Unused")
        override suspend fun refresh(refreshToken: String): TokenPair = error("Unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource {
            revisionStreamCalls += 1
            revisionStreamStarted.complete(Unit)
            if (blockRevisionAuthentication) {
                runBlocking { releaseRevisionAuthentication.await() }
                throw AuthenticationRequired()
            }
            if (failRevisionAuthentication) throw AuthenticationRequired()
            error("Unused")
        }
    }

    private class PausingDeletionMarkerDao(
        private val delegate: TimerDao,
    ) : TimerDao by delegate {
        val preparedWriteStarted = CompletableDeferred<Unit>()
        val releasePreparedWrite = CompletableDeferred<Unit>()

        override suspend fun updateState(state: LocalStateEntity) {
            if (state.accountDeletionState == "prepared" && !preparedWriteStarted.isCompleted) {
                preparedWriteStarted.complete(Unit)
                releasePreparedWrite.await()
            }
            delegate.updateState(state)
        }
    }
}
