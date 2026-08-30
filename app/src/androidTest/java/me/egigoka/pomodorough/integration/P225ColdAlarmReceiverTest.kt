package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerPhase
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.iroh.ReplicationMode
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.timer.TimerAlarmDeliveryResult
import me.egigoka.pomodorough.timer.TimerAlarmScheduler
import me.egigoka.pomodorough.timer.TimerCompletionNotifying
import me.egigoka.pomodorough.timer.deliverTimerAlarm
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class P225ColdAlarmReceiverTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val repositories = mutableListOf<TimerRepository>()
    private val notifications = AtomicInteger()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java).build()
    }

    @After
    fun tearDown() = runBlocking {
        repositories.asReversed().forEach { repository ->
            repository.stopCompletionAlert(null)
            repository.shutdownForTest()
        }
        TimerAlarmScheduler(context).cancel()
        database.close()
    }

    @Test
    fun coldReceiverPersistsAutoBreakBeforeDelayedProfile() = runBlocking {
        val service = P225DelayedAccountService()
        val repository = repository(savedState(autoStart = true), service)

        assertEquals(TimerAlarmDeliveryResult.CompletedWithActiveReplacement, deliver(repository))

        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(listOf(10L, 11L), commands.map { it.deviceSequence })
        assertEquals(commands.first().id, commands.last().generatedByFinishCommandId)
        commands.forEach { assertEquals(7, UUID.fromString(it.id).version()) }
        assertEquals(TimerPhase.ShortBreak, repository.state.value.timer?.phase)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertEquals(0, notifications.get())
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertEquals(commands, database.timerDao().pendingCommands())
    }

    @Test
    fun coldReceiverAlertsWithoutAutoBreakBeforeDelayedProfile() = runBlocking {
        val service = P225DelayedAccountService()
        val repository = repository(savedState(), service)

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliver(repository))

        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(CommandType.Finish), commands.map { it.type })
        assertEquals(TimerStatus.Completed, repository.state.value.timer?.status)
        assertEquals(1, notifications.get())
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertEquals(commands, database.timerDao().pendingCommands())
        assertEquals(1, notifications.get())
    }

    @Test
    fun receiverCompletesAndAlertsWhileProfileSuspended() = runBlocking {
        assertAlarmDuringAccountRestore(P225DelayedAccountService())
    }

    @Test
    fun receiverCompletesAndAlertsWhileBootstrapSuspended() = runBlocking {
        assertAlarmDuringAccountRestore(P225DelayedAccountService(delayProfile = false))
    }

    @Test
    fun concurrentReceiverDeliveriesPersistAndNotifyOnlyOnce() = runBlocking {
        val repository = repository(savedState())
        val start = CompletableDeferred<Unit>()
        val attempts = List(8) {
            async(Dispatchers.Default) {
                start.await()
                deliver(repository)
            }
        }
        start.complete(Unit)
        val results = attempts.awaitAll()

        assertEquals(1, results.count { it == TimerAlarmDeliveryResult.CompletedAndNotified })
        assertEquals(7, results.count { it == TimerAlarmDeliveryResult.NotExpired })
        assertEquals(listOf(CommandType.Finish), database.timerDao().pendingCommands().map { it.type })
        assertEquals(1, notifications.get())
    }

    @Test
    fun staleReceiverIdCannotCompleteCurrentTimer() = runBlocking {
        val repository = repository(savedState())

        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository, "stale-timer"))
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0, notifications.get())
        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliver(repository))
    }

    @Test
    fun receiverRechecksIdentityAfterReplicationReload() = runBlocking {
        assertRejectedAfterReload(savedState(timerId = "replacement-timer"))
    }

    @Test
    fun receiverRechecksPausedStateAfterReplicationReload() = runBlocking {
        assertRejectedAfterReload(savedState(status = TimerStatus.Paused))
    }

    @Test
    fun coldReceiverRejectsClearedTimerAfterOfflineReload() = runBlocking {
        assertRejectedAfterReload(savedState().copy(canonicalTimerJson = null, ownedTimerId = null))
    }

    @Test
    fun coldReceiverRejectsCompletedTimerAfterOfflineReload() = runBlocking {
        assertRejectedAfterReload(savedState(status = TimerStatus.Completed))
    }

    @Test
    fun coldReceiverRejectsPausedTimerAfterIrohRefresh() = runBlocking {
        assertIrohRefresh(savedState(status = TimerStatus.Paused), TimerAlarmDeliveryResult.NotExpired)
    }

    @Test
    fun coldReceiverRejectsReplacementTimerAfterIrohRefresh() = runBlocking {
        assertIrohRefresh(savedState(timerId = "replacement-timer"), TimerAlarmDeliveryResult.NotExpired)
    }

    @Test
    fun coldReceiverRejectsClearedTimerAfterIrohRefresh() = runBlocking {
        assertIrohRefresh(
            savedState().copy(canonicalTimerJson = null, ownedTimerId = null),
            TimerAlarmDeliveryResult.NotExpired,
        )
    }

    @Test
    fun coldReceiverAlertsCompletedTimerAfterIrohRefresh() = runBlocking {
        assertIrohRefresh(savedState(status = TimerStatus.Completed), TimerAlarmDeliveryResult.CompletedAndNotified)
    }

    @Test
    fun coldReceiverCompletesWithoutStartingBootstrap() = runBlocking {
        val service = P225DelayedAccountService(delayProfile = false)
        val repository = repository(savedState(), service)

        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliver(repository))
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
    }

    @Test
    fun coldReceiverRejectsPendingLogoutBeforeReplication() = runBlocking {
        val auth = object : AuthSession by TestAuthSession() {
            override fun credentialState() = AuthCredentialState.LogoutPending
        }
        val replication = P225ReloadReplication()
        assertRejected(repository(savedState(), auth = auth, replication = replication))
        assertEquals(0, replication.initializeCalls.get())
        assertEquals(0, replication.mutationCalls.get())
    }

    @Test
    fun coldReceiverRejectsReplicationTransition() = runBlocking {
        val replication = P225ReloadReplication(ReplicationMode.IROH)
        replication.state.value = replication.state.value.copy(transitioning = true)
        assertRejected(repository(savedState(), replication = replication))
        assertEquals(0, replication.initializeCalls.get())
        assertEquals(0, replication.mutationCalls.get())
    }

    @Test
    fun coldReceiverRejectsCredentialsLostDuringReplication() = runBlocking {
        p225CredentialLossScenarios().forEach { scenario -> assertCredentialLoss(scenario) }
    }

    @Test
    fun receiverRejectsUnreadableCredentialsWithoutNetwork() = runBlocking {
        val auth = object : AuthSession by TestAuthSession() {
            override fun credentialState() = AuthCredentialState.Unreadable
        }
        val repository = repository(savedState(), auth = auth)

        assertRejected(repository)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertFalse(repository.showCompletionAlert("timer-1") { error("Notification must be blocked") })
    }

    @Test
    fun receiverRejectsCorruptSnapshotWithoutNetwork() = runBlocking {
        val repository = repository(savedState().copy(canonicalTimerJson = "{invalid"))

        assertRejected(repository)
        assertTrue(repository.state.value.localAccountResetRequired)
        assertEquals("{invalid", database.timerDao().localState()?.canonicalTimerJson)
    }

    @Test
    fun receiverRejectsDeletionMarkersWithoutNetwork() = runBlocking {
        listOf("prepared", "remote_committed", "local_scrub_required", "unknown-marker").forEach { marker ->
            val repository = repository(savedState().copy(accountDeletionState = marker))
            assertRejected(repository)
            assertEquals(marker, database.timerDao().localState()?.accountDeletionState)
            assertFalse(repository.showCompletionAlert("timer-1") { error("Notification must be blocked") })
            repository.shutdownForTest()
            repositories.remove(repository)
        }
    }

    @Test
    fun receiverRejectsUnownedExpiredTimerWithoutNetwork() = runBlocking {
        assertRejected(repository(savedState().copy(ownedTimerId = null)))
    }

    @Test
    fun receiverRejectsUnexpiredTimerWithoutNetwork() = runBlocking {
        val timer = testTimer(anchorAt = Instant.ofEpochMilli(now + 3_600_000).toString())
        assertRejected(repository(testState(user = testUser(), timer = timer)))
    }

    @Test
    fun receiverStopsAlertWhenNotifierFails() = runBlocking {
        val repository = repository(savedState())
        val result = withTimeout(5_000) {
            deliverTimerAlarm(repository, "timer-1", TimerCompletionNotifying { error("Notification failed") })
        }

        assertEquals(TimerAlarmDeliveryResult.CompletedWithoutNotification, result)
        assertFalse(repository.stopCompletionAlert("timer-1"))
        assertEquals(listOf(CommandType.Finish), database.timerDao().pendingCommands().map { it.type })
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
    }

    private suspend fun assertAlarmDuringAccountRestore(service: P225DelayedAccountService) {
        val repository = repository(savedState(), service)
        kotlinx.coroutines.coroutineScope {
            val restore = launch(Dispatchers.Default) { repository.initialize() }
            try {
                withTimeout(5_000) { service.blocked.await() }
                assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliver(repository))
                assertFalse(restore.isCompleted)
                assertEquals(1, notifications.get())
                assertEquals(listOf(CommandType.Finish), database.timerDao().pendingCommands().map { it.type })
            } finally {
                restore.cancelAndJoin()
            }
        }
    }

    private suspend fun assertRejectedAfterReload(replacement: LocalStateEntity) {
        val replication = P225ReloadReplication()
        val service = P225DelayedAccountService()
        val repository = repository(savedState(), service, replication = replication)
        replication.onInitialize = { database.timerDao().insertState(replacement) }

        assertRejected(repository)
        assertEquals(1, replication.initializeCalls.get())
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
        assertEquals(replacement.canonicalTimerJson, database.timerDao().localState()?.canonicalTimerJson)
    }

    private suspend fun assertCredentialLoss(scenario: P225CredentialLossScenario) {
        var credentialState = scenario.initial
        val auth = object : AuthSession by TestAuthSession() {
            override fun credentialState() = credentialState
            override fun hasTokens() = credentialState == AuthCredentialState.Active
        }
        val replication = P225ReloadReplication(scenario.mode)
        val service = P225DelayedAccountService()
        val stored = savedState(autoStart = scenario.autoStart)
        val repository = repository(stored, service, auth, replication)
        val invalidate: suspend () -> Unit = { credentialState = scenario.lost }
        if (scenario.mode == ReplicationMode.IROH) replication.onMutation = invalidate
        else replication.onInitialize = invalidate

        assertRejected(repository)
        assertEquals(1, replication.initializeCalls.get())
        assertEquals(if (scenario.mode == ReplicationMode.IROH) 1 else 0, replication.mutationCalls.get())
        assertEquals(stored.deviceSequence, database.timerDao().localState()?.deviceSequence)
        assertEquals(stored.canonicalTimerJson, database.timerDao().localState()?.canonicalTimerJson)
        assertFalse(repository.showCompletionAlert("timer-1") { error("Notification must be blocked") })
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
        repository.shutdownForTest()
        repositories.remove(repository)
    }

    private suspend fun assertIrohRefresh(replacement: LocalStateEntity, expected: TimerAlarmDeliveryResult) {
        val replication = P225ReloadReplication(ReplicationMode.IROH)
        val service = P225DelayedAccountService()
        val initial = testState(user = testUser(), timer = testTimer(anchorAt = Instant.EPOCH.toString()))
        val repository = repository(initial, service, replication = replication)
        replication.onMutation = { database.timerDao().insertState(replacement) }

        assertEquals(expected, deliver(repository))
        assertEquals(1, replication.initializeCalls.get())
        assertEquals(1, replication.mutationCalls.get())
        assertEquals(if (expected == TimerAlarmDeliveryResult.CompletedAndNotified) 1 else 0, notifications.get())
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0, service.profileCalls.get())
        assertEquals(0, service.bootstrapCalls.get())
        assertEquals(replacement.canonicalTimerJson, database.timerDao().localState()?.canonicalTimerJson)
    }

    private suspend fun assertRejected(repository: TimerRepository) {
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertTrue(database.timerDao().pendingCommands().isEmpty())
        assertEquals(0, notifications.get())
    }

    private suspend fun deliver(repository: TimerRepository, timerId: String = "timer-1") = withTimeout(5_000) {
        deliverTimerAlarm(repository, timerId, TimerCompletionNotifying {
            notifications.incrementAndGet()
            true
        })
    }

    private fun savedState(
        autoStart: Boolean = false,
        timerId: String = "timer-1",
        status: String = TimerStatus.Running,
    ): LocalStateEntity = testState(
        user = testUser(),
        timer = testTimer(id = timerId, status = status, elapsedMs = 1_500_000, anchorAt = Instant.EPOCH.toString()),
        settings = TimerSettings(autoStartBreaks = autoStart),
        deviceSequence = 9,
    )

    private suspend fun repository(
        stored: LocalStateEntity,
        service: PomodoroughService = P225DelayedAccountService(),
        auth: AuthSession = TestAuthSession(tokensAvailable = true),
        replication: P225ReloadReplication = P225ReloadReplication(ReplicationMode.CENTRALIZED),
    ): TimerRepository {
        database.timerDao().insertState(stored)
        return TimerRepository(
            context = context,
            dao = database.timerDao(),
            api = service,
            auth = auth,
            json = repositoryJson,
            networkAvailable = { false },
            currentTimeMillis = { now },
            elapsedRealtimeMillis = { 10_000L },
            bootId = { "p225-test-boot" },
            replication = replication,
            remoteSyncIntervalMs = Long.MAX_VALUE,
        ).also(repositories::add)
    }
}
