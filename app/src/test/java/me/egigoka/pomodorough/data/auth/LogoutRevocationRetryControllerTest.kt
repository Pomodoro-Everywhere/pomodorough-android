package me.egigoka.pomodorough.data.auth

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.egigoka.pomodorough.data.TokenPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LogoutRevocationRetryControllerTest {
    @Test
    fun preparedLogoutRetriesWithoutRestartAndCapsBackoff() = runTest {
        val attempts = mutableListOf<Long>()
        val auth = FakeAuthSession(pending = true).apply {
            logoutAction = { throw IOException("first attempt failed") }
            retryAction = {
                attempts += currentTime
                if (retryCalls == 4) pending = false else throw IOException("retry failed")
            }
        }
        val controller = controller(auth, backgroundScope)

        controller.startPreparedLogout()
        runCurrent()
        advanceTimeBy(110)
        runCurrent()

        assertEquals(1, auth.logoutCalls)
        assertEquals(listOf(10L, 30L, 70L, 110L), attempts)
        assertFalse(auth.pending)
    }

    @Test
    fun repeatedStartsShareOneWorker() = runTest {
        val release = CompletableDeferred<Unit>()
        val auth = FakeAuthSession(pending = true).apply {
            logoutAction = {
                release.await()
                pending = false
            }
        }
        val controller = controller(auth, backgroundScope)

        controller.startPreparedLogout()
        controller.startPreparedLogout()
        controller.startIfNeeded()

        assertEquals(1, auth.logoutCalls)
        assertEquals(0, auth.retryCalls)
        release.complete(Unit)
        runCurrent()
        assertFalse(auth.pending)
    }

    @Test
    fun reconstructedControllerDrainsPersistedWork() = runTest {
        val auth = FakeAuthSession(pending = true).apply {
            logoutAction = { throw IOException("process stopped") }
            retryAction = { pending = false }
        }
        val processScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        controller(auth, processScope).startPreparedLogout()
        runCurrent()

        processScope.cancel()
        runCurrent()
        controller(auth, backgroundScope).startIfNeeded()
        runCurrent()

        assertEquals(1, auth.logoutCalls)
        assertEquals(1, auth.retryCalls)
        assertFalse(auth.pending)
    }

    @Test
    fun pendingWorkCreatedAfterIdleWakesController() = runTest {
        val auth = FakeAuthSession(pending = false).apply {
            retryAction = { pending = false }
        }
        val controller = controller(auth, backgroundScope)

        controller.startIfNeeded()
        auth.pending = true
        controller.startIfNeeded()
        runCurrent()

        assertEquals(1, auth.retryCalls)
        assertFalse(auth.pending)
    }

    private fun controller(auth: AuthSession, scope: CoroutineScope) =
        LogoutRevocationRetryController(
            auth = auth,
            scope = scope,
            initialDelayMs = 10,
            maximumDelayMs = 40,
        )

    private class FakeAuthSession(var pending: Boolean) : AuthSession {
        var logoutCalls = 0
        var retryCalls = 0
        var logoutAction: suspend () -> Unit = {}
        var retryAction: suspend () -> Unit = {}

        override suspend fun signIn(
            credentialProvider: GoogleCredentialProvider,
            deviceId: String,
        ): TokenPair = error("Unused")

        override fun hasTokens(): Boolean = false
        override suspend fun <T> authorized(block: suspend (String) -> T): T = error("Unused")
        override suspend fun logout() {
            logoutCalls += 1
            logoutAction()
        }
        override suspend fun retryPendingLogout() {
            retryCalls += 1
            retryAction()
        }
        override fun hasPendingLogout(): Boolean = pending
        override fun clear() = Unit
    }
}
