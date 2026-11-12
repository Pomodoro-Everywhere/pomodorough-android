package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import okhttp3.sse.EventSourceListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rename mirrors the Core plan: the renamed record replaces the old one,
 * a title that already exists merges into that task, selection follows
 * the renamed task only when the old one was selected, and an unchanged
 * title issues no operations at all.
 *
 * Core 0.56.0: the old record leaves through a `rename` operation that names
 * the new title, and finished history follows the renamed task, also after a
 * restart reloads the saved queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TaskRenameRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val repositories = mutableListOf<TimerRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pomodorough_tokens", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        CrashReporter.delegate = {}
        CrashReportingRuntime.reportingEnabled = true
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        AccountDeletionReporter.resetForTest()
        MutationPersistenceReporter.resetForTest()
    }

    @Test
    fun renamingSelectedTaskSelectsRenamedAndRemovesOld() = runBlocking {
        val repo = readyRepository()
        val alpha = addTask(repo, "Alpha")
        assertEquals(alpha, repo.state.value.selectedTaskId)

        repo.renameTask(alpha, "Beta")

        awaitTitles(repo, setOf("Beta"))
        assertEquals(taskId(repo, "Beta"), repo.state.value.selectedTaskId)
    }

    @Test
    fun renamingIntoExistingTitleMergesIntoThatTask() = runBlocking {
        val repo = readyRepository()
        val alpha = addTask(repo, "Alpha")
        val beta = addTask(repo, "Beta")
        repo.selectTask(alpha)
        assertEquals(alpha, repo.state.value.selectedTaskId)

        repo.renameTask(alpha, "Beta")

        awaitTitles(repo, setOf("Beta"))
        assertEquals(beta, repo.state.value.selectedTaskId)
        assertEquals(1, repo.state.value.tasks.size)
    }

    @Test
    fun renamingUnselectedTaskKeepsSelection() = runBlocking {
        val repo = readyRepository()
        val alpha = addTask(repo, "Alpha")
        val beta = addTask(repo, "Beta")
        assertEquals(beta, repo.state.value.selectedTaskId)

        repo.renameTask(alpha, "Gamma")

        awaitTitles(repo, setOf("Beta", "Gamma"))
        assertEquals(beta, repo.state.value.selectedTaskId)
    }

    @Test
    fun renamingToSameTitleIssuesNoOperations() = runBlocking {
        val repo = readyRepository()
        val alpha = addTask(repo, "Alpha")
        val dao = database.timerDao()
        val taskOperations = dao.pendingTaskOperations().size
        val selectionOperations = dao.pendingSelectedTaskOperations().size

        repo.renameTask(alpha, "Alpha")

        assertEquals(listOf("Alpha"), repo.state.value.tasks.map { it.title })
        assertEquals(alpha, repo.state.value.selectedTaskId)
        assertEquals(taskOperations, dao.pendingTaskOperations().size)
        assertEquals(selectionOperations, dao.pendingSelectedTaskOperations().size)
    }

    @Test
    fun renamingQueuesOneRenameThatNamesTheNewTitleInPlaceOfADelete() = runBlocking {
        val repo = readyRepository()
        val alpha = addTask(repo, "Alpha")

        repo.renameTask(alpha, "Beta")

        awaitTitles(repo, setOf("Beta"))
        val operations = database.timerDao().pendingTaskOperations()
        assertEquals(
            listOf("upsert" to "Alpha", "rename" to "Beta"),
            operations.filter { it.taskId == alpha }.map { it.type to it.title },
        )
        assertEquals(emptyList<String>(), operations.filter { it.type == "delete" }.map { it.taskId })
    }

    @Test
    fun renamedTaskKeepsTodaysFinishedFocusAlsoAfterARestart() = runBlocking {
        val repo = readyRepository()
        val alphaId = addTask(repo, "Alpha")
        repo.toggleTimer()
        repo.finishTimer()
        assertEquals(listOf(alphaId), repo.state.value.history.map { it.taskId })
        assertEquals(1, finishedToday(repo, "Alpha"))

        repo.renameTask(alphaId, "Beta")

        awaitTitles(repo, setOf("Beta"))
        val betaId = taskId(repo, "Beta")
        assertEquals(listOf(betaId), repo.state.value.history.map { it.taskId })
        assertEquals(1, finishedToday(repo, "Beta"))
        repositories.removeAt(repositories.lastIndex).shutdownForTest()
        val restarted = readyRepository()
        awaitTitles(restarted, setOf("Beta"))
        assertEquals(listOf(betaId), restarted.state.value.history.map { it.taskId })
        assertEquals(1, finishedToday(restarted, "Beta"))
    }

    private fun finishedToday(repo: TimerRepository, title: String): Int? =
        repo.state.value.taskSummaries.firstOrNull { it.task.title == title }?.finishedPomodoros

    private suspend fun readyRepository(): TimerRepository {
        val repo = repository(database.timerDao())
        repo.initialize()
        awaitSignedIn(repo)
        return repo
    }

    private suspend fun addTask(repo: TimerRepository, title: String): String {
        assertTrue("add $title", repo.addTask(title))
        awaitTask(repo, title)
        return taskId(repo, title)
    }

    private fun taskId(repo: TimerRepository, title: String): String =
        repo.state.value.tasks.first { it.title == title }.id

    private fun repository(dao: TimerDao): TimerRepository {
        val account = User("account-1", "a1@example.test", "A", "")
        runBlocking {
            if (database.timerDao().localState() == null) {
                database.timerDao().insertState(
                    LocalStateEntity(
                        deviceId = "device-1",
                        settingsJson = json.encodeToString(TimerSettings()),
                        userJson = json.encodeToString(account),
                        ownerUserId = account.id,
                    ),
                )
            }
        }
        return TimerRepository(
            context = context,
            dao = dao,
            api = FakeRenameService(account),
            auth = FakeRenameAuth(),
            json = json,
            networkAvailable = { false },
        ).also(repositories::add)
    }

    private suspend fun awaitSignedIn(repo: TimerRepository) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.authStatus != AuthStatus.SignedIn) delay(10)
            true
        }
        assertTrue("timed out waiting for sign-in", done == true)
    }

    private suspend fun awaitTask(repo: TimerRepository, title: String) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.tasks.none { it.title == title }) delay(10)
            true
        }
        assertTrue("timed out waiting for task", done == true)
    }

    private suspend fun awaitTitles(repo: TimerRepository, titles: Set<String>) {
        val done = withTimeoutOrNull(5_000) {
            while (repo.state.value.tasks.map { it.title }.toSet() != titles) delay(10)
            true
        }
        assertTrue("timed out waiting for $titles, saw ${repo.state.value.tasks.map { it.title }}", done == true)
    }

    private class FakeRenameAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) = error("unused")
        override fun hasTokens() = true
        override fun credentialState() = AuthCredentialState.Active
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FakeRenameService(val profile: User) : PomodoroughService {
        private val empty = SyncResponse(
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
        override suspend fun bootstrap(accessToken: String) = empty
        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: EventSourceListener) = error("unused")
    }
}
