package me.egigoka.pomodorough.data.auth

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal class LogoutRevocationRetryController(
    private val auth: AuthSession,
    private val scope: CoroutineScope,
    private val initialDelayMs: Long = 5_000,
    private val maximumDelayMs: Long = 300_000,
) {
    private val running = AtomicBoolean(false)

    fun startIfNeeded() {
        if (!auth.hasPendingLogout()) return
        start(auth::retryPendingLogout)
    }

    fun startPreparedLogout() {
        start(auth::logout)
    }

    private fun start(initialAttempt: suspend () -> Unit) {
        if (!scope.isActive || !running.compareAndSet(false, true)) return
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                retryUntilTerminal(initialAttempt)
            } finally {
                running.set(false)
                if (scope.isActive && auth.hasPendingLogout()) startIfNeeded()
            }
        }
    }

    private suspend fun retryUntilTerminal(initialAttempt: suspend () -> Unit) {
        var retryDelay = initialDelayMs
        var operation = initialAttempt
        while (true) {
            attempt(operation)
            if (!auth.hasPendingLogout()) return
            delay(retryDelay)
            retryDelay = (retryDelay * 2).coerceAtMost(maximumDelayMs)
            operation = auth::retryPendingLogout
        }
    }

    private suspend fun attempt(operation: suspend () -> Unit) {
        try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Pending obligation is durable retry state; next attempt uses bounded backoff.
        }
    }
}
