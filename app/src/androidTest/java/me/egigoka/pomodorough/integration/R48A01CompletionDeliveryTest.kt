package me.egigoka.pomodorough.integration

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.egigoka.pomodorough.data.CommandType
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.TimerStatus
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

// R48-A01: ticker-first expiry must still deliver exactly one alert.
// The ticker simulation below mirrors the unified foreground path in
// PomodoroughViewModel: raw finish, then shared consumeCompletionAlert
// with a counting notifier. The receiver uses real deliverTimerAlarm
// with the same counter, so the total counts every alert exactly once.
@RunWith(AndroidJUnit4::class)
class R48A01CompletionDeliveryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val repositories = mutableListOf<TimerRepository>()
    private val alerts = AtomicInteger()
    private val now = System.currentTimeMillis()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DatabaseName)
        context.getSharedPreferences(AlertPreferences, Context.MODE_PRIVATE).edit().clear().commit()
        openDatabase()
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach {
                it.stopCompletionAlert("timer-1")
                it.shutdownForTest()
            }
            TimerAlarmScheduler(context).cancel()
            database.close()
            context.deleteDatabase(DatabaseName)
            context.getSharedPreferences(AlertPreferences, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun tickerFirstThenLateInexactAlarmAlertsExactlyOnce() = runBlocking {
        val repository = repository(savedState())
        assertTrue(ticker(repository))

        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertExactlyOneCompletion(repository)
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertFalse(ticker(repository))
        assertExactlyOneCompletion(repository)
    }

    @Test
    fun receiverFirstThenTickerSkipsWithoutSecondFinish() = runBlocking {
        val repository = repository(savedState())
        assertEquals(TimerAlarmDeliveryResult.CompletedAndNotified, deliver(repository))

        assertFalse(ticker(repository))
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertExactlyOneCompletion(repository)
    }

    @Test
    fun tickerCommitThenConcurrentReceiversNotifyOnlyOnce() = runBlocking {
        val repository = repository(savedState())
        val committed = CompletableDeferred<Unit>()
        val ticker = async(Dispatchers.Default) {
            assertTrue(ticker(repository))
            committed.complete(Unit)
        }
        val receivers = List(8) {
            async(Dispatchers.Default) {
                committed.await()
                deliver(repository)
            }
        }
        ticker.await()
        val results = receivers.awaitAll()

        assertTrue(results.all { it == TimerAlarmDeliveryResult.NotExpired })
        assertExactlyOneCompletion(repository)
    }

    @Test
    fun concurrentTickerAndReceiversPersistOneFinishAtMostOneAlert() = runBlocking {
        val repository = repository(savedState())
        val start = CompletableDeferred<Unit>()
        val ticker = async(Dispatchers.Default) {
            start.await()
            ticker(repository)
        }
        val receivers = List(7) {
            async(Dispatchers.Default) {
                start.await()
                deliver(repository)
            }
        }
        start.complete(Unit)
        ticker.await()
        receivers.awaitAll()

        assertEquals(listOf(CommandType.Finish), database.timerDao().pendingCommands().map { it.type })
        assertTrue(alerts.get() <= 1)
    }

    @Test
    fun restartAfterUnifiedDeliveryKeepsOneFinishAndOneAlert() = runBlocking {
        val repository = repository(savedState())
        assertTrue(ticker(repository))
        val commands = database.timerDao().pendingCommands()
        repository.shutdownForTest()
        repositories.remove(repository)
        database.close()
        openDatabase()
        val restarted = repository()

        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(restarted))
        assertEquals(commands, database.timerDao().pendingCommands())
        assertEquals("timer-1", restarted.state.value.completionAlertTimerId)
        assertExactlyOneCompletion(restarted)
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(restarted))
    }

    @Test
    fun pausedReplacedUnownedQuarantinedAndUnexpiredNeverAlert() = runBlocking {
        listOf(
            savedState(status = TimerStatus.Paused),
            savedState(timerId = "replacement"),
            savedState().copy(ownedTimerId = null),
            savedState().copy(accountDeletionState = "prepared"),
            testState(
                user = testUser(),
                timer = testTimer(anchorAt = Instant.ofEpochMilli(now + 3_600_000).toString()),
            ),
        ).forEachIndexed { index, stored ->
            val repository = repository(stored)
            assertEquals("control $index receiver", TimerAlarmDeliveryResult.NotExpired, deliver(repository))
            assertFalse("control $index ticker", ticker(repository))
            assertTrue("control $index queues", database.timerDao().pendingCommands().isEmpty())
            assertEquals("control $index alerts", 0, alerts.get())
            repository.shutdownForTest()
            repositories.remove(repository)
        }
    }

    @Test
    fun tickerFirstWithAutomaticBreakSuppressesAlert() = runBlocking {
        val repository = repository(savedState(autoStart = true))
        assertFalse(ticker(repository))

        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(commands.first().id, commands.last().generatedByFinishCommandId)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertEquals(0, alerts.get())
        assertFalse(repository.finishExpiredTimer())
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertEquals(commands, database.timerDao().pendingCommands())
    }

    @Test
    fun receiverFirstWithAutomaticBreakSuppressesAlert() = runBlocking {
        val repository = repository(savedState(autoStart = true))
        assertEquals(TimerAlarmDeliveryResult.CompletedWithActiveReplacement, deliver(repository))

        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(CommandType.Finish, CommandType.Start), commands.map { it.type })
        assertEquals(commands.first().id, commands.last().generatedByFinishCommandId)
        assertEquals(TimerStatus.Running, repository.state.value.timer?.status)
        assertEquals(0, alerts.get())
        assertFalse(ticker(repository))
        assertEquals(TimerAlarmDeliveryResult.NotExpired, deliver(repository))
        assertEquals(commands, database.timerDao().pendingCommands())
    }

    private suspend fun assertExactlyOneCompletion(repository: TimerRepository) {
        val commands = database.timerDao().pendingCommands()
        assertEquals(listOf(CommandType.Finish), commands.map { it.type })
        assertEquals(listOf("timer-1"), commands.map { it.timerId })
        assertEquals(listOf(10L), commands.map { it.deviceSequence })
        assertEquals(TimerStatus.Completed, repository.state.value.timer?.status)
        assertEquals(listOf("timer-1"), repository.state.value.history.map { it.timerId })
        assertEquals(1, alerts.get())
    }

    // Mirrors the unified foreground path: only the finish winner
    // consumes the shared alert obligation, exactly like the ticker.
    private suspend fun ticker(
        repository: TimerRepository,
        timerId: String = "timer-1",
    ): Boolean {
        if (!repository.finishExpiredTimer(timerId)) return false
        return repository.consumeCompletionAlert(timerId) {
            alerts.incrementAndGet()
            true
        }
    }

    private suspend fun deliver(repository: TimerRepository) = withTimeout(5_000) {
        deliverTimerAlarm(
            repository, "timer-1", TimerCompletionNotifying {
                alerts.incrementAndGet()
                true
            },
        )
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, PomodoroughDatabase::class.java, DatabaseName).build()
    }

    private fun savedState(
        autoStart: Boolean = false,
        timerId: String = "timer-1",
        status: String = TimerStatus.Running,
    ) = testState(
        user = testUser(),
        timer = testTimer(id = timerId, status = status, elapsedMs = 1_500_000, anchorAt = Instant.EPOCH.toString()),
        settings = TimerSettings(autoStartBreaks = autoStart),
        deviceSequence = 9,
    )

    private suspend fun repository(stored: LocalStateEntity? = null): TimerRepository {
        stored?.let { database.timerDao().insertState(it) }
        return TimerRepository(
            context, database.timerDao(), TestRepositoryService(), TestAuthSession(tokensAvailable = true),
            repositoryJson,
            networkAvailable = { false },
            currentTimeMillis = { now },
            elapsedRealtimeMillis = { 10_000L },
            bootId = { "r48-a01-boot" },
            remoteSyncIntervalMs = Long.MAX_VALUE,
        ).also(repositories::add)
    }

    private companion object {
        const val DatabaseName = "r48-a01-completion.db"
        const val AlertPreferences = "completion-alert"
    }
}
