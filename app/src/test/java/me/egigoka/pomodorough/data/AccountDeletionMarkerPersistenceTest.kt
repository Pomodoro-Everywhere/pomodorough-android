package me.egigoka.pomodorough.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountDeletionMarkerPersistenceTest {
    @Test
    fun cancellationAfterDurableCommitCannotSkipInMemoryInstallation() = runTest {
        val durableCommitCompleted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        var installed = false

        val operation = launch {
            AccountDeletionMarkerPersistence.complete(
                persist = {
                    durableCommitCompleted.complete(Unit)
                    releasePersistence.await()
                },
                install = { installed = true },
            )
        }

        durableCommitCompleted.await()
        operation.cancel()
        releasePersistence.complete(Unit)
        operation.join()

        assertTrue(installed)
    }
}
