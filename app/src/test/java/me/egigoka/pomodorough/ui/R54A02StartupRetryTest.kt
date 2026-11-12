package me.egigoka.pomodorough.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.data.AppState
import me.egigoka.pomodorough.data.TimerRepository
import me.egigoka.pomodorough.data.TimerSettings
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import me.egigoka.pomodorough.data.local.TimerDao
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * R54-A02: a startup storage-read failure publishes a retryable startup state,
 * the screen offers Retry instead of an endless loading indicator, and Retry
 * reads storage again through the ViewModel. Production repository, Room and
 * bundled Core; the signed-out workspace needs no server or token vault.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class R54A02StartupRetryTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private var repository: TimerRepository? = null
    private var previousDelegate: ((Throwable) -> Unit)? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            database.timerDao().insertState(
                LocalStateEntity(deviceId = "device-1", settingsJson = json.encodeToString(TimerSettings())),
            )
        }
        previousDelegate = CrashReporter.delegate
        CrashReporter.delegate = {}
    }

    @After
    fun tearDown() {
        runBlocking {
            repository?.shutdownForTest()
            database.close()
        }
        CrashReporter.delegate = previousDelegate ?: CrashReporter.delegate
    }

    @Test
    fun viewModelRetryReadsStorageAgainAndClearsStartupFailure() = runBlocking {
        val main = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        try {
            val dao = FailingStateDao(database.timerDao()).apply { failure = IOException("r54 a02 disk read") }
            val repo = repository(dao)
            val viewModel = PomodoroughViewModel(repo)
            awaitCondition("startup failure published") { repo.state.value.localStorageReadFailed }
            assertFalse(repo.state.value.ready)

            viewModel.retryStartup()
            awaitCondition("failed retry read again") { dao.reads.get() == 2 }
            assertTrue("a failed retry keeps the retry offer", repo.state.value.localStorageReadFailed)
            dao.failure = null
            viewModel.retryStartup()

            awaitCondition("retry completed startup") { repo.state.value.ready }
            assertFalse(repo.state.value.localStorageReadFailed)
            assertTrue("the successful retry read storage again", dao.reads.get() >= 3)
        } finally {
            Dispatchers.resetMain()
            main.close()
        }
    }

    @Test
    fun startupFailureOffersRetry() {
        var retries = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { Screen(AppState(localStorageReadFailed = true)) { retries += 1 } }

        compose.onNodeWithText(context.getString(R.string.local_storage_read_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, retries)
        compose.onNodeWithText(context.getString(R.string.syncing_your_clock)).assertDoesNotExist()
    }

    @Test
    fun ordinaryStartupKeepsLoadingWithoutRetry() {
        compose.mainClock.autoAdvance = false
        compose.setContent { Screen(AppState()) {} }

        compose.onNodeWithText(context.getString(R.string.syncing_your_clock)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.retry)).assertDoesNotExist()
    }

    @Test
    fun activityWiresStartupRetryToViewModel() {
        // The compiler requires every caller to pass Retry, but a no-op lambda would still
        // compile. The activity composes only with the real application, so its call is pinned here.
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java")).firstOrNull(File::isDirectory)
        val activity = File(requireNotNull(root), "me/egigoka/pomodorough/MainActivity.kt").readText()
        assertTrue("onRetryStartup = viewModel::retryStartup" in activity)
    }

    @androidx.compose.runtime.Composable
    private fun Screen(state: AppState, onRetryStartup: () -> Unit) = PomodoroughTheme {
        PomodoroughScreen(
            state = state, onSignIn = {}, onLogout = {}, onResetLocalAccount = {}, onRefresh = {},
            onToggleTimer = {}, onFinishTimer = {}, onCancelTimer = {}, onSelectPhase = {},
            onChangeDuration = { _, _ -> }, onSetAutoStart = {}, onSelectTask = {},
            onAddTask = { _, _ -> }, onDeleteTask = {}, onResolveHistory = {},
            onRecoverHistoryResolution = {}, onConfirmAccountSwitch = {}, onCancelAccountSwitch = {},
            onDismissConflict = {}, onDismissNotice = {}, onSetReplicationMode = {},
            onCreateIrohRoom = {}, onJoinIrohRoom = {}, onLeaveIrohRoom = {},
            onRefreshIrohInvite = {}, onSyncIrohNow = {}, onShareIrohInvite = {},
            onRetryStartup = onRetryStartup,
        )
    }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private fun repository(dao: TimerDao) = TimerRepository(
        context = context,
        dao = dao,
        api = unusedService(),
        auth = SignedOutAuth(),
        json = json,
        networkAvailable = { false },
    ).also { repository = it }

    // A signed-out workspace never reaches the server; any call fails the test.
    private fun unusedService(): PomodoroughService = Proxy.newProxyInstance(
        PomodoroughService::class.java.classLoader,
        arrayOf(PomodoroughService::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "unused-service"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> error("Unexpected service call: ${method.name}")
        }
    } as PomodoroughService

    private class SignedOutAuth : AuthSession {
        override suspend fun signIn(credentialProvider: GoogleCredentialProvider, deviceId: String) =
            error("unused")
        override fun hasTokens() = false
        override suspend fun <T> authorized(block: suspend (String) -> T) = error("unused")
        override suspend fun logout() = Unit
        override fun clear() = Unit
    }

    private class FailingStateDao(private val delegate: TimerDao) : TimerDao by delegate {
        @Volatile var failure: Exception? = null
        val reads = AtomicInteger()

        override suspend fun localState(): LocalStateEntity? {
            reads.incrementAndGet()
            failure?.let { throw it }
            return delegate.localState()
        }
    }
}
