package me.egigoka.pomodorough.data

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.R
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import me.egigoka.pomodorough.data.api.PomodoroughService
import me.egigoka.pomodorough.data.auth.AuthCredentialState
import me.egigoka.pomodorough.data.auth.AuthSession
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import me.egigoka.pomodorough.data.auth.GoogleCredentialProvider
import me.egigoka.pomodorough.data.local.LocalStateEntity
import me.egigoka.pomodorough.data.local.PomodoroughDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * R54-A01: a failed account restore that keeps credentials and owner
 * metadata must be re-admitted by refresh, foreground, and restored
 * connectivity through initialization's single-flight restore. Each
 * request admits at most one attempt, failure waits for the next request,
 * and account transitions never overlap the retry. A network restored
 * while an attempt is in flight earns one follow-up once that attempt
 * fails. Real Room and bundled Core; only the server and token vault are
 * faked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class R54A01RestoreRetryTest {
    private lateinit var context: Context
    private lateinit var database: PomodoroughDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val account = User("account-1", "a1@example.test", "A", "")
    private val reported = mutableListOf<Throwable>()
    private val repositories = mutableListOf<TimerRepository>()
    private var previousDelegate: ((Throwable) -> Unit)? = null
    private var previousEnabled = false

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, PomodoroughDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            database.timerDao().insertState(
                LocalStateEntity(
                    deviceId = "device-1",
                    settingsJson = json.encodeToString(TimerSettings()),
                    userJson = json.encodeToString(account),
                    ownerUserId = account.id,
                ),
            )
        }
        previousDelegate = CrashReporter.delegate
        previousEnabled = CrashReportingRuntime.reportingEnabled
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        AccountBootstrapReporter.resetForTest()
    }

    @After
    fun tearDown() {
        runBlocking {
            repositories.asReversed().forEach { it.shutdownForTest() }
            repositories.clear()
            database.close()
        }
        CrashReporter.delegate = previousDelegate ?: CrashReporter.delegate
        CrashReportingRuntime.reportingEnabled = previousEnabled
        AccountBootstrapReporter.resetForTest()
    }

    @Test
    fun refreshRetriesFailedRestoreWithoutCredentialProvider() = runBlocking {
        val (repo, service, auth) = failedRestore()
        service.profile = ProfileMode.Healthy
        repo.refresh()
        awaitStatus(repo, AuthStatus.SignedIn)
        assertEquals(2, service.profileCalls.get())
        assertEquals(1, service.bootstrapCalls.get())
        assertEquals(0, auth.signInCalls)
        assertEquals(0, auth.clearCalls)
        assertEquals(account, repo.state.value.user)
        assertEquals(account.id, durable().local.ownerUserId)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun foregroundRetriesFailedRestore() = runBlocking {
        val (repo, service, auth) = failedRestore()
        service.profile = ProfileMode.Healthy
        repo.onForeground()
        awaitStatus(repo, AuthStatus.SignedIn)
        assertEquals(2, service.profileCalls.get())
        assertEquals(1, service.bootstrapCalls.get())
        assertEquals(0, auth.signInCalls)
        assertEquals(account, repo.state.value.user)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun restoredConnectivityRetriesFailedRestore() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, auth) = failedRestore(networkAvailable = null)
        service.profile = ProfileMode.Healthy
        restoreConnectivity(connectivity, network)
        awaitStatus(repo, AuthStatus.SignedIn)
        assertEquals(2, service.profileCalls.get())
        assertEquals(0, auth.signInCalls)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun repeatedFailuresAdmitOneAttemptPerRequestThenRecover() = runBlocking {
        val (repo, service, auth) = failedRestore()
        // addTask runs explicit initialize(), which already re-attempts the restore once.
        assertTrue(repo.addTask("r54-offline-task"))
        val attempts = service.profileCalls.get()
        val before = durable()
        retryUntilFailurePublished(repo) { refresh() }
        retryUntilFailurePublished(repo) { onForeground() }
        delay(300)
        assertEquals("no automatic retry loop", attempts + 2, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(before, durable())
        assertTrue("transport failures stay quiet", reported.isEmpty())
        service.profile = ProfileMode.Healthy
        repo.refresh()
        awaitStatus(repo, AuthStatus.SignedIn)
        assertEquals(attempts + 3, service.profileCalls.get())
        assertEquals(1, service.bootstrapCalls.get())
        assertEquals(0, auth.clearCalls)
        assertTrue(repo.state.value.tasks.any { it.title == "r54-offline-task" })
        assertEquals("offline queue survives recovery", before.taskOperations, durable().taskOperations)
    }

    // UX round 2, L1: Retry leaves the restore notice up until the retry has an
    // outcome. A refused request keeps it; success clears it; expiry replaces it.
    @Test
    fun refusedRetryKeepsTheRestoreNotice() = runBlocking {
        val (repo, service, _) = failedRestore()
        val shown = repo.state.value.notice
        assertEquals(NoticeAction.RetryAccountRestore, repo.state.value.noticeAction)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        repo.refresh()
        awaitCondition("retry entered profile request") { service.profileCalls.get() == 2 }
        repo.refresh()
        delay(200)
        assertEquals("the in-flight retry refuses the second request", 2, service.profileCalls.get())
        assertEquals(shown, repo.state.value.notice)
        assertEquals(NoticeAction.RetryAccountRestore, repo.state.value.noticeAction)
        service.gate = null
        gate.complete(Unit)
        awaitAccountTransitionsIdle(repo)
    }

    @Test
    fun successfulRetryClearsTheRestoreNotice() = runBlocking {
        val (repo, service, _) = failedRestore()
        assertEquals(NoticeAction.RetryAccountRestore, repo.state.value.noticeAction)
        service.profile = ProfileMode.Healthy
        repo.refresh()
        awaitStatus(repo, AuthStatus.SignedIn)
        assertNull(repo.state.value.notice)
        assertNull(repo.state.value.noticeAction)
    }

    @Test
    fun expiredRetryReplacesTheRestoreNoticeWithSignIn() = runBlocking {
        val (repo, service, _) = failedRestore()
        assertEquals(NoticeAction.RetryAccountRestore, repo.state.value.noticeAction)
        service.profile = ProfileMode.Expired
        repo.refresh()
        awaitCondition("expired retry offers sign-in") {
            repo.state.value.noticeAction == NoticeAction.RetrySignIn
        }
        assertEquals(context.getString(R.string.session_expired_sign_in_again), repo.state.value.notice)
    }

    @Test
    fun overlappingRetryRequestsAdmitOneRestore() = runBlocking {
        val (repo, service, _) = failedRestore()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        service.profile = ProfileMode.Healthy
        repo.refresh()
        repo.onForeground()
        awaitCondition("retry entered profile request") { service.profileCalls.get() == 2 }
        repeat(3) {
            repo.refresh()
            repo.onForeground()
        }
        delay(200)
        assertEquals(2, service.profileCalls.get())
        gate.complete(Unit)
        awaitStatus(repo, AuthStatus.SignedIn)
        repo.refresh()
        repo.onForeground()
        delay(200)
        assertEquals("signed-in refresh syncs instead of restoring", 2, service.profileCalls.get())
        assertEquals(1, service.bootstrapCalls.get())
    }

    @Test
    fun cancelledRetryReleasesAdmissionForNextRequest() = runBlocking {
        val (repo, service, auth) = failedRestore()
        repo.dismissNotice()
        service.profile = ProfileMode.Cancelled
        repo.refresh()
        awaitCondition("cancelled retry reached profile") { service.profileCalls.get() == 2 }
        service.profile = ProfileMode.Healthy
        awaitCondition("next request admitted after cancellation") {
            repo.refresh()
            repo.state.value.authStatus == AuthStatus.SignedIn
        }
        assertEquals(3, service.profileCalls.get())
        assertEquals(0, auth.clearCalls)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun retryHoldsAccountTransitionsUntilRestoreSettles() = runBlocking {
        val (repo, service, auth) = failedRestore()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        service.profile = ProfileMode.Healthy
        repo.refresh()
        awaitCondition("retry entered profile request") { service.profileCalls.get() == 2 }
        val signIn = async(Dispatchers.IO) { repo.signIn(FakeCredentialProvider()) }
        delay(200)
        assertEquals("sign-in waits for the serialized retry", 0, auth.signInCalls)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        service.gate = null
        gate.complete(Unit)
        signIn.await()
        assertEquals(1, auth.signInCalls)
        assertEquals(AuthStatus.SignedIn, repo.state.value.authStatus)
        assertEquals(account, repo.state.value.user)
    }

    @Test
    fun retiredCredentialsAreNotRetried() = runBlocking {
        val (repo, service, auth) = failedRestore(initialProfile = ProfileMode.Expired)
        assertEquals(1, auth.clearCalls)
        service.profile = ProfileMode.Healthy
        repo.refresh()
        repo.onForeground()
        delay(300)
        assertEquals(1, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    @Test
    fun pendingLogoutCredentialsAreNotRetried() = runBlocking {
        val (repo, service, auth) = failedRestore()
        auth.credentials = AuthCredentialState.LogoutPending
        repo.dismissNotice()
        service.profile = ProfileMode.Healthy
        repo.refresh()
        repo.onForeground()
        delay(300)
        assertEquals(1, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNull("retry must not republish logout notices", repo.state.value.notice)
    }

    @Test
    fun requestsQueuedBehindAccountTransitionAdmitOneAttempt() = runBlocking {
        val (repo, service, _) = failedRestore()
        repeat(3) { round ->
            repo.dismissNotice()
            val attempts = service.profileCalls.get()
            val release = holdAccountTransitions(repo)
            repeat(4) {
                repo.refresh()
                repo.onForeground()
            }
            release.complete(Unit)
            awaitCondition("round $round failure published") { repo.state.value.notice != null }
            delay(300)
            assertEquals("round $round admits one attempt", attempts + 1, service.profileCalls.get())
            assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        }
        assertTrue(reported.isEmpty())
    }

    @Test
    fun retryQueuedBehindLogoutRechecksAndStaysNoOp() = runBlocking {
        val (repo, service, auth) = failedRestore()
        auth.revocationOffline = true
        repo.dismissNotice()
        val release = holdAccountTransitions(repo)
        val logout = async(Dispatchers.IO) { repo.logout() }
        // Let logout queue on the transition lock before the retry request does.
        delay(200)
        repo.refresh()
        repo.onForeground()
        delay(200)
        assertEquals("nothing runs while transitions are held", 1, service.profileCalls.get())
        release.complete(Unit)
        logout.await()
        delay(300)
        assertEquals("only logout's own initialize re-attempts", 2, service.profileCalls.get())
        assertNotEquals(context.getString(R.string.pending_logout), repo.state.value.notice)
        assertNotEquals(context.getString(R.string.account_deletion_outcome_unknown), repo.state.value.notice)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNull(durable().local.ownerUserId)
        assertEquals(AuthCredentialState.LogoutPending, auth.credentials)
    }

    @Test
    fun ownerlessFailedSignInIsNotRetried() = runBlocking {
        database.timerDao().insertState(
            LocalStateEntity(deviceId = "device-1", settingsJson = json.encodeToString(TimerSettings())),
        )
        val service = FakeRestoreService(account)
        val auth = FakeRestoreAuth().apply { credentials = AuthCredentialState.Empty }
        val repo = repository(service, auth, networkAvailable = { false })
        repo.initialize()
        repo.signIn(FakeCredentialProvider())
        assertEquals(1, auth.signInCalls)
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        service.profile = ProfileMode.Healthy
        repo.refresh()
        repo.onForeground()
        delay(300)
        assertEquals(1, service.profileCalls.get())
        assertEquals("retry must not reach the stale-credential clear", AuthCredentialState.Active, auth.credentials)
        assertEquals(0, auth.clearCalls)
    }

    @Test
    fun connectivityRestoredDuringFailingRetryEarnsOneFollowUp() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, auth) = failedRestore(networkAvailable = null)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        repo.onForeground()
        awaitCondition("retry entered profile request") { service.profileCalls.get() == 2 }
        // The in-flight attempt already chose to fail; only a follow-up reaches the healthy server.
        service.profile = ProfileMode.Healthy
        restoreConnectivity(connectivity, network)
        service.gate = null
        gate.complete(Unit)
        awaitStatus(repo, AuthStatus.SignedIn)
        assertEquals(3, service.profileCalls.get())
        assertEquals(1, service.bootstrapCalls.get())
        assertEquals(0, auth.signInCalls)
    }

    @Test
    fun connectivityRestoredDuringFailingInitializeEarnsOneFollowUp() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, _) = failedRestore(networkAvailable = null)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val explicit = async(Dispatchers.IO) { repo.initialize() }
        awaitCondition("initialize entered profile request") { service.profileCalls.get() == 2 }
        restoreConnectivity(connectivity, network)
        service.gate = null
        gate.complete(Unit)
        explicit.await()
        awaitCondition("follow-up attempt") { service.profileCalls.get() == 3 }
        delay(300)
        assertEquals("one follow-up per restored network", 3, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertTrue("transport failures stay quiet", reported.isEmpty())
    }

    @Test
    fun failedAttemptStartedByRestoredConnectivityEarnsNoFollowUp() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, _) = failedRestore(networkAvailable = null)
        repo.dismissNotice()
        restoreConnectivity(connectivity, network)
        awaitCondition("restored network retry failed") { repo.state.value.notice != null }
        delay(300)
        assertEquals("the restored network is spent on its own attempt", 2, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    @Test
    fun unreadableCredentialsSurfaceRecoveryNoticeOnRetry() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, auth) = failedRestore(networkAvailable = null)
        auth.credentials = AuthCredentialState.Unreadable
        repo.dismissNotice()
        repo.onForeground()
        val unreadable = context.getString(R.string.unreadable_credential)
        awaitCondition("unreadable credential notice") { repo.state.value.notice == unreadable }
        assertTrue(repo.state.value.localAccountResetRequired)
        service.profile = ProfileMode.Healthy
        repeat(2) {
            // Restored connectivity reaches the retry gate without refresh's and foreground's
            // own network fence, so it alone proves the gate honors credential recovery.
            repo.dismissNotice()
            loseConnectivity(connectivity, network)
            restoreConnectivity(connectivity, network)
            repo.refresh()
            repo.onForeground()
            delay(300)
            assertNull("credential recovery notice is not republished", repo.state.value.notice)
        }
        assertEquals("credential recovery fences further retries", 1, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertNull(repo.state.value.user)
        assertEquals(0, auth.clearCalls)
    }

    @Test
    fun connectivityLostDuringFailingInitializeEarnsNoFollowUp() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, _) = failedRestore(networkAvailable = null)
        repo.dismissNotice()
        restoreConnectivity(connectivity, network)
        awaitCondition("restored network retry failed") { repo.state.value.notice != null }
        delay(100)
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val explicit = async(Dispatchers.IO) { repo.initialize() }
        awaitCondition("initialize entered profile request") { service.profileCalls.get() == 3 }
        loseConnectivity(connectivity, network)
        service.gate = null
        gate.complete(Unit)
        explicit.await()
        delay(300)
        assertEquals("a lost network carries no news for a follow-up", 3, service.profileCalls.get())
        restoreConnectivity(connectivity, network)
        awaitCondition("next restored network retries") { service.profileCalls.get() == 4 }
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    @Test
    fun retryRefusedForConcurrentRestoreLeavesRestorationToThatRestore() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val (repo, service, _) = failedRestore(networkAvailable = null)
        val release = holdAccountTransitions(repo)
        // Admitted by the gate, then queued behind the held transition.
        repo.onForeground()
        val gate = CompletableDeferred<Unit>()
        service.gate = gate
        val explicit = async(Dispatchers.IO) { repo.initialize() }
        awaitCondition("initialize entered profile request") { service.profileCalls.get() == 2 }
        // The explicit restore holds initialization: this request and the queued retry are refused.
        restoreConnectivity(connectivity, network)
        release.complete(Unit)
        awaitAccountTransitionsIdle(repo)
        delay(100)
        service.gate = null
        gate.complete(Unit)
        explicit.await()
        awaitCondition("follow-up attempt") { service.profileCalls.get() == 3 }
        delay(300)
        assertEquals("the restore that saw the restoration earns its follow-up", 3, service.profileCalls.get())
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
    }

    @Test
    fun restorationSpentOnRefusedRetryAdmitsNoLaterAttempt() = runBlocking {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(connectivity.activeNetwork)
        publishCapabilities(connectivity, network, validated = false)
        val clockStepMs = 2 * SyncWireBounds.MaxClockSkewMs
        var nowMs = System.currentTimeMillis()
        val (repo, service, _) = failedRestore(networkAvailable = null, clock = { nowMs })
        // Each local action re-runs initialize(), and each admitted initialize is one restore.
        repo.setAutoStart(true)
        nowMs -= clockStepMs
        repo.setAutoStart(false)
        assertTrue("a clock behind the retained HLC fails the mutation", repo.state.value.settings.autoStartBreaks)
        assertEquals(3, service.profileCalls.get())
        restoreConnectivity(connectivity, network)
        delay(300)
        assertEquals("admission refuses retries while the mutation failure stands", 3, service.profileCalls.get())
        nowMs += 2 * clockStepMs
        repo.setAutoStart(false)
        assertEquals(false, repo.state.value.settings.autoStartBreaks)
        delay(300)
        assertEquals("a spent restoration admits no later attempt", 3, service.profileCalls.get())
        repo.onForeground()
        awaitCondition("foreground retries") { service.profileCalls.get() == 4 }
    }

    private suspend fun failedRestore(
        initialProfile: ProfileMode = ProfileMode.Offline,
        networkAvailable: (() -> Boolean)? = { false },
        clock: () -> Long = System::currentTimeMillis,
    ): Harness {
        val service = FakeRestoreService(account).apply { profile = initialProfile }
        val auth = FakeRestoreAuth()
        val repo = repository(service, auth, networkAvailable, clock)
        repo.initialize()
        assertEquals(AuthStatus.SignedOut, repo.state.value.authStatus)
        assertEquals(1, service.profileCalls.get())
        return Harness(repo, service, auth)
    }

    private fun repository(
        service: FakeRestoreService,
        auth: FakeRestoreAuth,
        networkAvailable: (() -> Boolean)?,
        clock: () -> Long = System::currentTimeMillis,
    ) = TimerRepository(
        context = context,
        dao = database.timerDao(),
        api = service,
        auth = auth,
        json = json,
        networkAvailable = networkAvailable,
        currentTimeMillis = clock,
    ).also(repositories::add)

    // Holds the account-transition lock the way a running sign-in or logout does, so
    // requests and transitions queue behind it in a known order.
    private suspend fun CoroutineScope.holdAccountTransitions(repo: TimerRepository): CompletableDeferred<Unit> {
        val accountSync = TimerRepository::class.java.getDeclaredField("centralizedAccountSync")
            .apply { isAccessible = true }
            .get(repo) as CentralizedAccountSyncAggregate
        val held = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        launch {
            accountSync.workspace.serialize {
                held.complete(Unit)
                release.await()
            }
        }
        held.await()
        return release
    }

    // The workspace lock is FIFO, so a pass through it means every request queued earlier ran.
    private suspend fun awaitAccountTransitionsIdle(repo: TimerRepository) {
        val accountSync = TimerRepository::class.java.getDeclaredField("centralizedAccountSync")
            .apply { isAccessible = true }
            .get(repo) as CentralizedAccountSyncAggregate
        withTimeout(10_000) { accountSync.workspace.serialize {} }
    }

    private suspend fun retryUntilFailurePublished(
        repo: TimerRepository,
        retry: TimerRepository.() -> Unit,
    ) {
        repo.dismissNotice()
        repo.retry()
        awaitCondition("failed retry published notice") { repo.state.value.notice != null }
        // The notice publishes inside the attempt; let it release single-flight admission.
        delay(100)
    }

    private suspend fun awaitStatus(repo: TimerRepository, status: AuthStatus) =
        awaitCondition("auth status $status") { repo.state.value.authStatus == status }

    private suspend fun awaitCondition(label: String, condition: () -> Boolean) {
        val met = withTimeoutOrNull(10_000) {
            while (!condition()) delay(10)
            true
        }
        assertTrue("timed out waiting for $label", met == true)
    }

    private fun publishCapabilities(
        connectivity: ConnectivityManager,
        network: Network,
        validated: Boolean,
    ): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities)
        return capabilities
    }

    private fun loseConnectivity(connectivity: ConnectivityManager, network: Network) {
        val unvalidated = publishCapabilities(connectivity, network, validated = false)
        shadowOf(connectivity).networkCallbacks.forEach {
            it.onCapabilitiesChanged(network, unvalidated)
        }
    }

    private fun restoreConnectivity(connectivity: ConnectivityManager, network: Network) {
        val validated = publishCapabilities(connectivity, network, validated = true)
        shadowOf(connectivity).networkCallbacks.forEach {
            it.onCapabilitiesChanged(network, validated)
        }
    }

    private fun durable(): Durable = runBlocking {
        val dao = database.timerDao()
        Durable(
            local = requireNotNull(dao.localState()),
            taskOperations = dao.pendingTaskOperations().map { it.id },
            commands = dao.pendingCommands().map { it.id },
        )
    }

    private data class Harness(
        val repo: TimerRepository,
        val service: FakeRestoreService,
        val auth: FakeRestoreAuth,
    )

    private data class Durable(
        val local: LocalStateEntity,
        val taskOperations: List<String>,
        val commands: List<String>,
    )

    private enum class ProfileMode { Offline, Expired, Cancelled, Healthy }

    private class FakeRestoreAuth : AuthSession {
        @Volatile var credentials = AuthCredentialState.Active
        @Volatile var revocationOffline = false
        @Volatile var signInCalls = 0
        @Volatile var clearCalls = 0

        override suspend fun signIn(
            credentialProvider: GoogleCredentialProvider,
            deviceId: String,
        ): TokenPair {
            signInCalls += 1
            credentials = AuthCredentialState.Active
            return TokenPair("a", "2030-01-01T00:00:00Z", "r", "2030-01-01T00:00:00Z")
        }

        override fun hasTokens() = credentials == AuthCredentialState.Active
        override fun credentialState() = credentials
        override suspend fun <T> authorized(block: suspend (String) -> T) = block("token")

        override fun prepareLogout() {
            if (credentials == AuthCredentialState.Active) credentials = AuthCredentialState.LogoutPending
        }

        override suspend fun logout() {
            if (revocationOffline) throw IOException("r54 revocation offline")
            credentials = AuthCredentialState.Empty
        }

        override suspend fun retryPendingLogout() = logout()
        override fun hasPendingLogout() = credentials == AuthCredentialState.LogoutPending

        override fun clear() {
            clearCalls += 1
            credentials = AuthCredentialState.Empty
        }
    }

    private class FakeCredentialProvider : GoogleCredentialProvider {
        override suspend fun identityToken(serverClientId: String, nonce: String) = "id-token"
    }

    private class FakeRestoreService(private val account: User) : PomodoroughService {
        @Volatile var profile = ProfileMode.Offline
        @Volatile var gate: CompletableDeferred<Unit>? = null
        val profileCalls = AtomicInteger()
        val bootstrapCalls = AtomicInteger()
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

        override suspend fun me(accessToken: String): MeResponse {
            profileCalls.incrementAndGet()
            // An attempt's outcome is fixed when it reaches the server, before any gate opens.
            val mode = profile
            gate?.await()
            return when (mode) {
                ProfileMode.Offline -> throw IOException("r54 restore offline")
                ProfileMode.Expired -> throw AuthenticationRequired()
                ProfileMode.Cancelled -> throw CancellationException("r54 restore cancelled")
                ProfileMode.Healthy -> MeResponse(account, "")
            }
        }

        override suspend fun bootstrap(accessToken: String): SyncResponse {
            bootstrapCalls.incrementAndGet()
            return empty
        }

        override suspend fun resolveBootstrap(accessToken: String, request: BootstrapResolutionRequest) = empty
        override suspend fun sync(accessToken: String, request: SyncRequest) = empty
        override suspend fun createChallenge() = error("unused")
        override suspend fun exchange(request: NativeExchangeRequest) = error("unused")
        override suspend fun refresh(refreshToken: String) = error("unused")
        override suspend fun logout(accessToken: String) = Unit
        override fun revisionStream(accessToken: String, listener: okhttp3.sse.EventSourceListener) =
            throw IOException("r54 revision stream offline")
    }
}
