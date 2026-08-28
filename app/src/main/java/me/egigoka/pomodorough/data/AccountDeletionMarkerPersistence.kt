package me.egigoka.pomodorough.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal object AccountDeletionMarkerPersistence {
    suspend fun complete(
        persist: suspend () -> Unit,
        install: () -> Unit,
    ) {
        withContext(NonCancellable) {
            persist()
            install()
        }
    }
}
