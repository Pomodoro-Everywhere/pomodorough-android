package me.egigoka.pomodorough.unit.positive

import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.auth.AuthRepository
import me.egigoka.pomodorough.unit.TestAuthService
import me.egigoka.pomodorough.unit.TestTokenStore
import me.egigoka.pomodorough.unit.expiredTokens
import me.egigoka.pomodorough.unit.freshTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthRepositoryPositiveTest {
    @Test
    fun freshAccessTokenIsUsedWithoutRefresh() = runTest {
        val service = TestAuthService()
        val store = TestTokenStore(freshTokens("current-access"))

        val result = repository(service, store).authorized { "accepted:$it" }

        assertEquals("accepted:current-access", result)
        assertEquals(0, service.refreshCalls)
        assertEquals(freshTokens("current-access"), store.tokens)
    }

    @Test
    fun successfulLogoutRevokesDetachedAccessAndCompletesObligation() = runTest {
        val service = TestAuthService()
        val store = TestTokenStore(freshTokens("logout-access"))

        repository(service, store).logout()

        assertEquals(listOf("logout-access"), service.logoutTokens)
        assertNull(store.tokens)
        assertEquals(null, store.pendingLogout)
        assertEquals(0, store.clearCalls)
    }

    @Test
    fun expiredAccessTokenRefreshesDetachedSessionBeforeRevocation() = runTest {
        val service = TestAuthService().apply {
            refreshResult = freshTokens("replacement-access")
        }
        val tokens = expiredTokens()
        val store = TestTokenStore(tokens)

        repository(service, store).logout()

        assertEquals(1, service.refreshCalls)
        assertEquals(listOf("replacement-access"), service.logoutTokens)
        assertNull(store.tokens)
        assertNull(store.pendingLogout)
    }

    private fun repository(service: TestAuthService, store: TestTokenStore) = AuthRepository(
        api = service,
        tokenVault = store,
        googleServerClientId = "client-id",
    )
}
