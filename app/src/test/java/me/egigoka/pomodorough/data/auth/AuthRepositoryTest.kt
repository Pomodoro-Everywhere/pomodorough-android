package me.egigoka.pomodorough.data.auth

import java.io.IOException
import java.time.Instant
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TokenPair
import me.egigoka.pomodorough.data.api.ApiException
import me.egigoka.pomodorough.data.api.PomodoroughService
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthRepositoryTest {
    @Test
    fun signInForwardsChallengeToCredentialProviderAndPersistsExchange() = runTest {
        val tokens = freshTokens()
        val store = FakeTokenStore(null)
        val service = FakeService().apply {
            challenge = NativeChallenge(
                challenge = "signed-challenge",
                nonce = "challenge-nonce",
                expiresAt = "2026-01-01T00:05:00Z",
            )
            exchangeHandler = { tokens }
        }
        val credentials = FakeGoogleCredentialProvider("google-id-token")

        val result = repository(service, store).signIn(credentials, "device-123")

        assertEquals(tokens, result)
        assertEquals(listOf("client-id" to "challenge-nonce"), credentials.requests)
        assertEquals(
            listOf(
                NativeExchangeRequest(
                    idToken = "google-id-token",
                    challenge = "signed-challenge",
                    deviceId = "device-123",
                    platform = "android",
                ),
            ),
            service.exchangeRequests,
        )
        assertEquals(tokens, store.tokens)
        assertEquals(1, store.writeCalls)
    }

    @Test
    fun credentialFailureStopsBeforeExchangeAndDoesNotPersistTokens() = runTest {
        val store = FakeTokenStore(null)
        val service = FakeService()
        val failure = IOException("credential provider unavailable")
        val credentials = FakeGoogleCredentialProvider(failure = failure)

        val error = capture<IOException> {
            repository(service, store).signIn(credentials, "device-123")
        }

        assertSame(failure, error)
        assertTrue(service.exchangeRequests.isEmpty())
        assertNull(store.tokens)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun credentialCancellationPropagatesWithoutExchangeOrTokenMutation() = runTest {
        val store = FakeTokenStore(null)
        val service = FakeService()
        val cancellation = CancellationException("user cancelled")
        val credentials = FakeGoogleCredentialProvider(failure = cancellation)

        val error = capture<CancellationException> {
            repository(service, store).signIn(credentials, "device-123")
        }

        assertSame(cancellation, error)
        assertTrue(service.exchangeRequests.isEmpty())
        assertNull(store.tokens)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun blankCredentialTokenIsRejectedBeforeExchange() = runTest {
        val store = FakeTokenStore(null)
        val service = FakeService()

        val error = capture<AuthenticationRequired> {
            repository(service, store).signIn(FakeGoogleCredentialProvider("   "), "device-123")
        }

        assertEquals("Google did not return an ID token", error.message)
        assertTrue(service.exchangeRequests.isEmpty())
        assertNull(store.tokens)
    }

    @Test
    fun exchangeFailureDoesNotPersistTokens() = runTest {
        val store = FakeTokenStore(null)
        val failure = ApiException(401, "invalid identity token")
        val service = FakeService().apply { exchangeHandler = { throw failure } }

        val error = capture<ApiException> {
            repository(service, store).signIn(FakeGoogleCredentialProvider("id-token"), "device-123")
        }

        assertSame(failure, error)
        assertEquals(1, service.exchangeRequests.size)
        assertNull(store.tokens)
        assertEquals(0, store.writeCalls)
    }

    @Test
    fun hasTokensReflectsStoreContents() {
        val store = FakeTokenStore(null)
        val repository = repository(FakeService(), store)

        assertFalse(repository.hasTokens())

        store.tokens = freshTokens()

        assertTrue(repository.hasTokens())
    }

    @Test
    fun clearDelegatesToTokenStoreOnce() {
        val store = FakeTokenStore(freshTokens())

        repository(FakeService(), store).clear()

        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun logoutWithoutTokensClearsOnceWithoutCallingApi() = runTest {
        val store = FakeTokenStore(null)
        val service = FakeService()

        repository(service, store).logout()

        assertEquals(0, store.clearCalls)
        assertTrue(service.logoutTokens.isEmpty())
    }

    @Test
    fun refreshForwardsExactRefreshToken() = runTest {
        val store = FakeTokenStore(expiredTokens().copy(refreshToken = "exact-refresh-token"))
        val service = FakeService().apply { refreshHandler = { freshTokens() } }

        repository(service, store).authorized { it }

        assertEquals(listOf("exact-refresh-token"), service.refreshTokens)
    }

    @Test
    fun accessTokenExpiringInThirtySecondsIsRefreshed() = runTest {
        val tokens = freshTokens().copy(
            accessToken = "expiring-access",
            accessTokenExpiresAt = Instant.now().plusSeconds(30).toString(),
        )
        val store = FakeTokenStore(tokens)
        val service = FakeService().apply { refreshHandler = { freshTokens() } }

        val accessToken = repository(service, store).authorized { it }

        assertEquals("fresh-access", accessToken)
        assertEquals(1, service.refreshCalls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentExpiredRequestsShareOneRefresh() = runTest {
        val store = FakeTokenStore(expiredTokens())
        val service = FakeService()
        val releaseRefresh = CompletableDeferred<Unit>()
        service.refreshHandler = {
            releaseRefresh.await()
            freshTokens()
        }
        val repository = repository(service, store)

        val first = async { repository.authorized { it } }
        val second = async { repository.authorized { it } }
        runCurrent()

        assertEquals(1, service.refreshCalls)
        releaseRefresh.complete(Unit)
        assertEquals("fresh-access", first.await())
        assertEquals("fresh-access", second.await())
        assertEquals(1, service.refreshCalls)
        assertEquals(freshTokens(), store.tokens)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun refreshCompletingAfterLogoutCannotRestoreTokens() = runTest {
        val store = FakeTokenStore(expiredTokens())
        val refreshStarted = CompletableDeferred<Unit>()
        val releaseRefresh = CompletableDeferred<Unit>()
        val service = FakeService().apply {
            refreshHandler = {
                refreshStarted.complete(Unit)
                releaseRefresh.await()
                freshTokens()
            }
        }
        val repository = repository(service, store)

        val request = async {
            capture<AuthenticationRequired> { repository.authorized { it } }
        }
        refreshStarted.await()
        val logout = async { repository.logout() }
        runCurrent()
        releaseRefresh.complete(Unit)
        logout.await()
        request.await()

        assertNull(store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun unauthorizedRequestRefreshesAndRetriesOnce() = runTest {
        val store = FakeTokenStore(freshTokens(accessToken = "old-access"))
        val service = FakeService().apply { refreshHandler = { freshTokens() } }
        val repository = repository(service, store)
        val attempts = mutableListOf<String>()

        val result = repository.authorized { token ->
            attempts += token
            if (token == "old-access") throw ApiException(401, "expired")
            "accepted"
        }

        assertEquals("accepted", result)
        assertEquals(listOf("old-access", "fresh-access"), attempts)
        assertEquals(1, service.refreshCalls)
    }

    @Test
    fun secondUnauthorizedResponseClearsSession() = runTest {
        val store = FakeTokenStore(freshTokens(accessToken = "old-access"))
        val service = FakeService().apply { refreshHandler = { freshTokens() } }
        val repository = repository(service, store)

        val error = capture<AuthenticationRequired> {
            repository.authorized<Nothing> { throw ApiException(401, "expired") }
        }

        assertEquals("Session expired", error.message)
        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun refreshUnauthorizedClearsSession() = runTest {
        val store = FakeTokenStore(expiredTokens())
        val service = FakeService().apply {
            refreshHandler = { throw ApiException(401, "invalid refresh token") }
        }

        capture<AuthenticationRequired> { repository(service, store).authorized { it } }

        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun ambiguousRefreshFailurePreservesCurrentPair() = runTest {
        val original = expiredTokens()
        val store = FakeTokenStore(original)
        val failure = IOException("connection reset")
        val service = FakeService().apply { refreshHandler = { throw failure } }

        val thrown = capture<IOException> { repository(service, store).authorized { it } }

        assertSame(failure, thrown)
        assertEquals(original, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun logoutServerFailureRetiresCredentialsButKeepsDurableRevocation() = runTest {
        val original = freshTokens()
        val store = FakeTokenStore(original)
        val service = FakeService().apply { logoutFailure = ApiException(500, "unavailable") }

        capture<ApiException> { repository(service, store).logout() }

        assertNull(store.tokens)
        assertEquals(original, store.pendingLogout)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun reconstructedRepositoryAllowsSignInWithoutRestoringPendingLogout() = runTest {
        val original = freshTokens()
        val replacement = freshTokens("replacement-access")
        val store = FakeTokenStore(original).apply { pendingLogout = original }
        val service = FakeService().apply { exchangeHandler = { replacement } }

        repository(service, store).signIn(FakeGoogleCredentialProvider("new-id-token"), "device-123")

        assertTrue(service.logoutTokens.isEmpty())
        assertEquals(replacement, store.tokens)
        assertEquals(original, store.pendingLogout)
    }

    @Test
    fun refreshedRevocationSurvivesLogoutFailureWithReplacementPair() = runTest {
        val original = expiredTokens()
        val replacement = freshTokens("replacement-access").copy(refreshToken = "replacement-refresh")
        val store = FakeTokenStore(original)
        val service = FakeService().apply {
            refreshHandler = { replacement }
            logoutFailure = ApiException(503, "offline")
        }

        capture<ApiException> { repository(service, store).logout() }

        assertNull(store.tokens)
        assertEquals(replacement, store.pendingLogout)
        assertEquals(listOf(original.refreshToken), service.refreshTokens)
        assertEquals(listOf(replacement.accessToken), service.logoutTokens)
    }

    @Test
    fun unattemptedRevocationExpiresWithRefreshAuthority() = runTest {
        val expired = freshTokens().copy(refreshTokenExpiresAt = "2000-01-01T00:00:00Z")
        val store = FakeTokenStore(expired)
        val service = FakeService()

        repository(service, store, now = Instant.parse("2026-01-01T00:00:00Z")).logout()

        assertNull(store.tokens)
        assertNull(store.pendingLogout)
        assertTrue(service.logoutTokens.isEmpty())
        assertTrue(service.refreshTokens.isEmpty())
    }

    @Test
    fun ordinaryLogoutUnauthorizedRefreshesAndRevokesReplacement() = runTest {
        val original = freshTokens("old-access")
        val replacement = freshTokens("fresh-access").copy(refreshToken = "fresh-refresh")
        val store = FakeTokenStore(original)
        val service = FakeService().apply {
            var first = true
            logoutHandler = {
                if (first) {
                    first = false
                    throw ApiException(401, "access unauthorized")
                }
            }
            refreshHandler = { replacement }
        }

        repository(service, store).logout()

        assertNull(store.pendingLogout)
        assertEquals(listOf("old-access", "fresh-access"), service.logoutTokens)
        assertEquals(listOf(original.refreshToken), service.refreshTokens)
    }

    @Test
    fun ambiguousRefreshIsNotDroppedAtOldExpiry() = runTest {
        val expired = freshTokens().copy(refreshTokenExpiresAt = "2000-01-01T00:00:00Z")
        val obligation = LogoutRevocation(
            tokens = expired,
            refreshOutcomeUnknown = true,
        )
        val store = FakeTokenStore(null).apply { installPending(obligation) }
        val service = FakeService().apply {
            refreshHandler = { throw ApiException(401, "reuse or invalid") }
        }

        repository(service, store, now = Instant.parse("2026-01-01T00:00:00Z")).retryPendingLogout()

        assertEquals(listOf(expired.refreshToken), service.refreshTokens)
        assertNull(store.pendingLogout)
    }

    @Test
    fun unreadableCredentialStateFailsClosedWithoutDestroyingEvidence() = runTest {
        val store = FakeTokenStore(null).apply {
            unreadableCause = IOException("keystore unavailable")
        }
        val repository = repository(FakeService(), store)

        assertFalse(repository.hasTokens())
        capture<AuthenticationRequired> { repository.authorized { it } }
        capture<AuthenticationRequired> {
            repository.signIn(FakeGoogleCredentialProvider("new-id-token"), "device-123")
        }
        assertEquals(0, store.clearCalls)
        assertSame(store.unreadableCause, (store.state() as TokenStoreState.Unreadable).cause)
    }

    @Test
    fun logoutUnauthorizedRefreshesBeforeTreatingSessionAsTerminal() = runTest {
        val original = freshTokens()
        val store = FakeTokenStore(original)
        val service = FakeService().apply {
            logoutFailure = ApiException(401, "unauthorized")
            refreshHandler = { throw ApiException(401, "refresh unauthorized") }
        }

        repository(service, store).logout()

        assertNull(store.tokens)
        assertNull(store.pendingLogout)
        assertEquals(listOf(original.accessToken), service.logoutTokens)
        assertEquals(listOf(original.refreshToken), service.refreshTokens)
    }

    @Test
    fun deleteAccountClearsTokensOnlyAfterServerConfirmation() = runTest {
        val store = FakeTokenStore(freshTokens())
        val service = FakeService()

        repository(service, store).deleteAccount("DELETE")

        assertEquals(listOf("fresh-access" to "DELETE"), service.deleteAccountRequests)
        assertNull(store.tokens)
        assertEquals(1, store.clearCalls)
    }

    @Test
    fun deleteAccountFailureRetainsTokensForSafeRetry() = runTest {
        val original = freshTokens()
        val store = FakeTokenStore(original)
        val service = FakeService().apply { deleteAccountFailure = ApiException(500, "unavailable") }

        capture<ApiException> { repository(service, store).deleteAccount("DELETE") }

        assertEquals(original, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun completedDeletionDoesNotClearTokensFromAConcurrentNewSignIn() = runTest {
        val accountA = freshTokens()
        val accountB = freshTokens().copy(
            accessToken = "account-b-access",
            refreshToken = "account-b-refresh",
        )
        val store = FakeTokenStore(accountA)
        val deletionStarted = CompletableDeferred<Unit>()
        val releaseDeletion = CompletableDeferred<Unit>()
        val service = FakeService().apply {
            deleteAccountHandler = {
                deletionStarted.complete(Unit)
                releaseDeletion.await()
            }
            exchangeHandler = { accountB }
        }
        val repository = repository(service, store)

        val deletion = async { repository.deleteAccount("DELETE") }
        deletionStarted.await()
        repository.signIn(FakeGoogleCredentialProvider("account-b-id-token"), "device-b")
        releaseDeletion.complete(Unit)
        deletion.await()

        assertEquals(accountB, store.tokens)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun secondAccountLogoutCannotOverwriteOlderRevocationObligation() = runTest {
        val accountA = freshTokens("account-a-access")
        val accountB = freshTokens("account-b-access").copy(refreshToken = "account-b-refresh")
        val store = FakeTokenStore(accountA)
        val service = FakeService().apply {
            logoutFailure = ApiException(503, "offline")
            exchangeHandler = { accountB }
        }
        val repository = repository(service, store)

        capture<ApiException> { repository.logout() }
        repository.signIn(FakeGoogleCredentialProvider("account-b-id-token"), "device-b")
        service.logoutFailure = null
        repository.logout()

        assertEquals(listOf(accountA.accessToken, accountB.accessToken), service.logoutTokens)
        assertEquals(accountA, store.pendingLogout)
        assertNull(store.tokens)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun concurrentSignInCannotLoseInFlightLogoutObligation() = runTest {
        val accountA = freshTokens()
        val accountB = freshTokens().copy(
            accessToken = "account-b-access",
            refreshToken = "account-b-refresh",
        )
        val store = FakeTokenStore(accountA)
        val logoutStarted = CompletableDeferred<Unit>()
        val releaseLogout = CompletableDeferred<Unit>()
        val service = FakeService().apply {
            logoutHandler = {
                logoutStarted.complete(Unit)
                releaseLogout.await()
            }
            exchangeHandler = { accountB }
        }
        val repository = repository(service, store)

        val logout = async { repository.logout() }
        logoutStarted.await()
        val signIn = async {
            repository.signIn(FakeGoogleCredentialProvider("account-b-id-token"), "device-b")
        }
        runCurrent()

        assertEquals(accountB, store.tokens)
        assertEquals(accountA, store.pendingLogout)
        releaseLogout.complete(Unit)
        signIn.await()
        logout.await()

        assertEquals(listOf(accountA.accessToken), service.logoutTokens)
        assertEquals(accountB, store.tokens)
        assertNull(store.pendingLogout)
        assertEquals(0, store.clearCalls)
    }

    private fun repository(
        service: FakeService,
        store: FakeTokenStore,
        now: Instant = Instant.now(),
    ) = AuthRepository(
        api = service,
        tokenVault = store,
        googleServerClientId = "client-id",
        now = { now },
    )

    private fun freshTokens(accessToken: String = "fresh-access") = TokenPair(
        accessToken = accessToken,
        accessTokenExpiresAt = "2999-01-01T00:00:00Z",
        refreshToken = "fresh-refresh",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private fun expiredTokens() = TokenPair(
        accessToken = "expired-access",
        accessTokenExpiresAt = "2000-01-01T00:00:00Z",
        refreshToken = "current-refresh",
        refreshTokenExpiresAt = "2999-02-01T00:00:00Z",
    )

    private suspend inline fun <reified T : Throwable> capture(crossinline block: suspend () -> Unit): T {
        return try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            error as T
        }
    }

    private class FakeTokenStore(initial: TokenPair?) : TokenStore {
        var tokens = initial
        private val pendingRevocations = mutableListOf<LogoutRevocation>()
        var pendingLogout: TokenPair?
            get() = pendingRevocations.firstOrNull()?.tokens
            set(value) {
                pendingRevocations.clear()
                if (value != null) pendingRevocations += LogoutRevocation(tokens = value)
            }
        var unreadableCause: Throwable? = null
        var clearCalls = 0
        var writeCalls = 0

        fun installPending(revocation: LogoutRevocation) {
            pendingRevocations += revocation
        }

        override fun read(): TokenPair? = tokens

        override fun state(): TokenStoreState = unreadableCause?.let(TokenStoreState::Unreadable)
            ?: tokens?.let(TokenStoreState::Active)
            ?: pendingRevocations.takeIf(List<*>::isNotEmpty)?.let(TokenStoreState::LogoutPending)
            ?: TokenStoreState.Empty

        override fun write(tokens: TokenPair) {
            this.tokens = tokens
            writeCalls += 1
        }

        override fun markLogoutPending(tokens: TokenPair) {
            retireForLogout(tokens)
        }

        override fun retireForLogout(tokens: TokenPair): LogoutRevocation {
            check(this.tokens == tokens)
            val revocation = LogoutRevocation(tokens = tokens)
            pendingRevocations += revocation
            this.tokens = null
            return revocation
        }

        override fun pendingLogoutRevocations(): List<LogoutRevocation> = pendingRevocations.toList()

        override fun replaceLogoutRevocation(
            previous: LogoutRevocation,
            replacement: LogoutRevocation,
        ) {
            val index = pendingRevocations.indexOfFirst { it.id == previous.id }
            check(index >= 0)
            pendingRevocations[index] = replacement
        }

        override fun completeLogoutRevocation(revocation: LogoutRevocation) {
            pendingRevocations.removeAll { it.id == revocation.id }
        }

        override fun clear() {
            tokens = null
            clearCalls += 1
        }
    }

    private class FakeService : PomodoroughService {
        var refreshCalls = 0
        val refreshTokens = mutableListOf<String>()
        var refreshHandler: suspend (String) -> TokenPair = { error("Unexpected refresh") }
        val logoutTokens = mutableListOf<String>()
        var logoutFailure: Throwable? = null
        var logoutHandler: (suspend () -> Unit)? = null
        val deleteAccountRequests = mutableListOf<Pair<String, String>>()
        var deleteAccountFailure: Throwable? = null
        var deleteAccountHandler: (suspend () -> Unit)? = null
        var challenge = NativeChallenge(
            challenge = "challenge",
            nonce = "nonce",
            expiresAt = "2026-01-01T00:05:00Z",
        )
        val exchangeRequests = mutableListOf<NativeExchangeRequest>()
        var exchangeHandler: suspend (NativeExchangeRequest) -> TokenPair = {
            error("Unexpected exchange")
        }

        override suspend fun refresh(refreshToken: String): TokenPair {
            refreshCalls += 1
            refreshTokens += refreshToken
            return refreshHandler(refreshToken)
        }

        override suspend fun logout(accessToken: String) {
            logoutTokens += accessToken
            logoutFailure?.let { throw it }
            logoutHandler?.invoke()
        }

        override suspend fun deleteAccount(accessToken: String, confirmation: String) {
            deleteAccountRequests += accessToken to confirmation
            deleteAccountFailure?.let { throw it }
            deleteAccountHandler?.invoke()
        }

        override suspend fun createChallenge(): NativeChallenge = challenge
        override suspend fun exchange(request: NativeExchangeRequest): TokenPair {
            exchangeRequests += request
            return exchangeHandler(request)
        }
        override suspend fun me(accessToken: String): MeResponse = error("Unused")
        override suspend fun bootstrap(accessToken: String): SyncResponse = error("Unused")
        override suspend fun resolveBootstrap(
            accessToken: String,
            request: BootstrapResolutionRequest,
        ): SyncResponse = error("Unused")
        override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse = error("Unused")
        override fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource =
            error("Unused")
    }

    private class FakeGoogleCredentialProvider(
        private val token: String = "",
        private val failure: Throwable? = null,
    ) : GoogleCredentialProvider {
        val requests = mutableListOf<Pair<String, String>>()

        override suspend fun identityToken(serverClientId: String, nonce: String): String {
            requests += serverClientId to nonce
            failure?.let { throw it }
            return token
        }
    }
}
