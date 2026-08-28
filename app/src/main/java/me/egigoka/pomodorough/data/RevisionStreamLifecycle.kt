package me.egigoka.pomodorough.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.egigoka.pomodorough.data.auth.AuthenticationRequired
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener

internal sealed interface RevisionStreamEvent {
    data class RevisionObserved(val revision: Long?) : RevisionStreamEvent

    data object Unauthorized : RevisionStreamEvent

    data object AuthenticationExpired : RevisionStreamEvent
}

internal class RevisionStreamLifecycle(
    scope: CoroutineScope,
    private val initialized: Deferred<Unit>,
    private val json: Json,
    private val reconnectDelayMs: Long = 5_000,
    initialOnline: Boolean,
    private val eligible: () -> Boolean,
    private val open: suspend (EventSourceListener) -> EventSource,
    private val emit: (RevisionStreamEvent) -> Unit,
    private val expireAuthentication: suspend () -> Unit = {
        emit(RevisionStreamEvent.AuthenticationExpired)
    },
) {
    private val lifecycleJob = SupervisorJob(scope.coroutineContext[Job])
    private val lifecycleScope = CoroutineScope(scope.coroutineContext + lifecycleJob)
    private val streamMutex = Mutex()
    private val signals = Channel<Boolean>(Channel.UNLIMITED)
    private var eventSource: EventSource? = null

    @Volatile
    var foreground: Boolean = false
        private set

    @Volatile
    var online: Boolean = initialOnline
        private set

    @Volatile
    private var generation: Long = 0

    init {
        lifecycleScope.launch { consumeSignals() }
    }

    fun onForeground() {
        markForeground(true)
        requestOpen()
    }

    fun onBackground() {
        markForeground(false)
        requestClose()
    }

    fun markForeground(value: Boolean) {
        foreground = value
        generation += 1
    }

    fun setOnline(value: Boolean) {
        online = value
    }

    fun requestOpen() {
        signals.trySend(true)
    }

    fun requestClose() {
        signals.trySend(false)
    }

    suspend fun closeNow() {
        streamMutex.withLock {
            val source = eventSource
            eventSource = null
            source?.cancel()
        }
    }

    suspend fun shutdown() {
        lifecycleJob.cancelAndJoin()
        closeNow()
    }

    private suspend fun consumeSignals() {
        for (shouldOpen in signals) {
            try {
                if (shouldOpen) openNow() else closeNow()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (shouldOpen) scheduleOpenRetry(generation)
            }
        }
    }

    private suspend fun openNow() {
        initialized.await()
        streamMutex.withLock {
            if (!foreground || !online || !eligible() || eventSource != null) return
            try {
                eventSource = open(listener())
            } catch (_: AuthenticationRequired) {
                expireAuthentication()
            }
        }
    }

    private fun listener() = object : EventSourceListener() {
        override fun onEvent(source: EventSource, id: String?, type: String?, data: String) {
            emit(RevisionStreamEvent.RevisionObserved(parseRevision(data)))
        }

        override fun onClosed(source: EventSource) {
            handleEnd(source)
        }

        override fun onFailure(source: EventSource, t: Throwable?, response: Response?) {
            handleEnd(source, response?.code)
        }
    }

    private fun parseRevision(data: String): Long? = data.toLongOrNull() ?: runCatching {
        json.parseToJsonElement(data).jsonObject["revision"]?.jsonPrimitive?.content?.toLong()
    }.getOrNull()

    private fun handleEnd(source: EventSource, responseCode: Int? = null) {
        lifecycleScope.launch {
            val reconnectGeneration = streamMutex.withLock {
                if (eventSource !== source) return@withLock null
                eventSource = null
                generation.takeIf { foreground && online && eligible() }
            }
            if (responseCode == 401) emit(RevisionStreamEvent.Unauthorized)
            reconnectGeneration?.let { scheduleOpenRetry(it) }
        }
    }

    private fun scheduleOpenRetry(expectedGeneration: Long) {
        lifecycleScope.launch {
            delay(reconnectDelayMs)
            if (foreground && generation == expectedGeneration) requestOpen()
        }
    }
}
