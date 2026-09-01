package me.egigoka.pomodorough.data.api

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.egigoka.pomodorough.data.ApiError
import me.egigoka.pomodorough.data.BootstrapResolutionRequest
import me.egigoka.pomodorough.data.DeleteAccountRequest
import me.egigoka.pomodorough.data.MeResponse
import me.egigoka.pomodorough.data.NativeChallenge
import me.egigoka.pomodorough.data.NativeExchangeRequest
import me.egigoka.pomodorough.data.RefreshRequest
import me.egigoka.pomodorough.data.SyncRequest
import me.egigoka.pomodorough.data.SyncResponse
import me.egigoka.pomodorough.data.TokenPair
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer

// Bounds decoded HTTP bodies before JSON parsing while retaining one byte for oversize detection.
internal const val API_RESPONSE_BODY_LIMIT_BYTES = 16L * 1024L * 1024L
private const val RESPONSE_BODY_READ_BYTES = 8L * 1024L

open class ApiException(
    val statusCode: Int,
    message: String,
) : IOException(message)

enum class BootstrapConflictKind { Revision, RequestId, Unknown }

class BootstrapConflictException(
    val kind: BootstrapConflictKind,
    message: String,
) : ApiException(409, message)

interface PomodoroughService {
    suspend fun createChallenge(): NativeChallenge
    suspend fun exchange(request: NativeExchangeRequest): TokenPair
    suspend fun refresh(refreshToken: String): TokenPair
    suspend fun me(accessToken: String): MeResponse
    suspend fun bootstrap(accessToken: String): SyncResponse
    suspend fun resolveBootstrap(
        accessToken: String,
        request: BootstrapResolutionRequest,
    ): SyncResponse
    suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse
    suspend fun logout(accessToken: String)
    suspend fun deleteAccount(accessToken: String, confirmation: String) {
        throw UnsupportedOperationException("Account deletion is not implemented")
    }
    fun revisionStream(accessToken: String, listener: EventSourceListener): EventSource {
        throw UnsupportedOperationException("Revision streaming is not implemented")
    }
}

class PomodoroughApi internal constructor(
    private val baseUrl: String,
    private val client: OkHttpClient,
    val json: Json,
    private val callFactory: Call.Factory,
) : PomodoroughService {
    constructor(
        baseUrl: String,
        client: OkHttpClient,
        json: Json,
    ) : this(baseUrl, client, json, client)

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
    private val eventSourceFactory = EventSources.createFactory(client)

    override suspend fun createChallenge(): NativeChallenge =
        post("auth/google/challenge", "{}", null)

    override suspend fun exchange(request: NativeExchangeRequest): TokenPair =
        post("auth/google/exchange", json.encodeToString(request), null)

    override suspend fun refresh(refreshToken: String): TokenPair =
        post("auth/refresh", json.encodeToString(RefreshRequest(refreshToken)), null)

    override suspend fun me(accessToken: String): MeResponse = get("me", accessToken)

    override suspend fun bootstrap(accessToken: String): SyncResponse = get("bootstrap", accessToken)

    override suspend fun resolveBootstrap(
        accessToken: String,
        request: BootstrapResolutionRequest,
    ): SyncResponse = post("bootstrap/resolve", json.encodeToString(request), accessToken)

    override suspend fun sync(accessToken: String, request: SyncRequest): SyncResponse =
        post("sync", json.encodeToString(request), accessToken)

    override suspend fun logout(accessToken: String) {
        val request = requestBuilder("auth/logout", accessToken)
            .post(ByteArray(0).toRequestBody())
            .build()
        execute(request).use(::requireSuccess)
    }

    override suspend fun deleteAccount(accessToken: String, confirmation: String) {
        val request = requestBuilder("account", accessToken)
            .delete(json.encodeToString(DeleteAccountRequest(confirmation)).toRequestBody(jsonMediaType))
            .build()
        execute(request).use(::requireSuccess)
    }

    override fun revisionStream(
        accessToken: String,
        listener: EventSourceListener,
    ): EventSource {
        val request = requestBuilder("stream", accessToken).build()
        return eventSourceFactory.newEventSource(request, listener)
    }

    private suspend inline fun <reified T> get(path: String, accessToken: String?): T {
        val request = requestBuilder(path, accessToken).get().build()
        return executeJson(request)
    }

    private suspend inline fun <reified T> post(
        path: String,
        body: String,
        accessToken: String?,
    ): T {
        val request = requestBuilder(path, accessToken)
            .post(body.toRequestBody(jsonMediaType))
            .build()
        return executeJson(request)
    }

    private fun requestBuilder(path: String, accessToken: String?): Request.Builder {
        return Request.Builder()
            .url("${baseUrl.trimEnd('/')}/${path.trimStart('/')}")
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .apply {
                if (accessToken != null) header("Authorization", "Bearer $accessToken")
            }
    }

    private suspend inline fun <reified T> executeJson(request: Request): T {
        return execute(request).use { response ->
            requireSuccess(response)
            val body = readBody(response)
            if (body.isBlank()) throw IOException("Server returned an empty response")
            json.decodeFromString(body)
        }
    }

    private fun requireSuccess(response: Response): Response {
        if (response.isSuccessful) return response
        val body = readBody(response)
        val error = runCatching { json.decodeFromString<ApiError>(body).error }.getOrNull()
        if (response.code == 409 && response.request.url.encodedPath.endsWith("/bootstrap/resolve")) {
            val normalized = error.orEmpty().lowercase().replace('-', '_').replace(' ', '_')
            val kind = when {
                "revision" in normalized -> BootstrapConflictKind.Revision
                "request" in normalized && "id" in normalized -> BootstrapConflictKind.RequestId
                else -> BootstrapConflictKind.Unknown
            }
            throw BootstrapConflictException(kind, error ?: "Bootstrap resolution conflict")
        }
        throw ApiException(response.code, error ?: "Request failed (${response.code})")
    }

    private fun readBody(response: Response): String {
        val body = response.body ?: return ""
        return body.limitedBody().string()
    }

    private fun ResponseBody.limitedBody(): ResponseBody {
        val original = this
        val limitedSource = ResponseBodyLimitSource(source()).buffer()
        return object : ResponseBody() {
            override fun contentType() = original.contentType()
            override fun contentLength() = original.contentLength()
            override fun source(): BufferedSource = limitedSource
        }
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = callFactory.newCall(request)
        val callbackDelivered = AtomicBoolean(false)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (!continuation.isActive || !callbackDelivered.compareAndSet(false, true)) return
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                if (!callbackDelivered.compareAndSet(false, true)) {
                    response.close()
                    return
                }
                continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }
}

internal class ResponseBodyLimitSource(
    private val upstream: BufferedSource,
) : Source {
    private var acceptedBytes = 0L
    private var reachedEof = false
    private var terminalFailure: Throwable? = null
    private val scratch = Buffer()

    override fun read(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount < 0: $byteCount" }
        if (byteCount == 0L) return 0L
        terminalFailure?.let { throw it }
        if (reachedEof) return -1L
        val probeBytes = API_RESPONSE_BODY_LIMIT_BYTES - acceptedBytes + 1L
        val requested = minOf(byteCount, RESPONSE_BODY_READ_BYTES, probeBytes)
        val read = readUpstream(requested)
        if (read == -1L) {
            if (scratch.size != 0L) failTerminal("Server response body returned data with EOF")
            reachedEof = true
            return -1L
        }
        validateRead(read, requested)
        acceptedBytes += read
        if (acceptedBytes > API_RESPONSE_BODY_LIMIT_BYTES) {
            failTerminal("Server response exceeds $API_RESPONSE_BODY_LIMIT_BYTES bytes")
        }
        sink.write(scratch, read)
        return read
    }

    override fun timeout(): Timeout = upstream.timeout()
    override fun close() = upstream.close()

    private fun readUpstream(requested: Long): Long = try {
        upstream.read(scratch, requested)
    } catch (error: Exception) {
        if (scratch.size != 0L) {
            failTerminal("Server response body returned data before failing", error)
        }
        terminalFailure = error
        throw error
    }

    private fun validateRead(read: Long, requested: Long) {
        if (read <= 0L) failTerminal("Server response body read made no progress: $read")
        if (read > requested || scratch.size != read) {
            failTerminal("Server response body returned invalid read count: $read for $requested bytes")
        }
    }

    private fun failTerminal(message: String, cause: Throwable? = null): Nothing {
        scratch.clear()
        val error = IOException(message, cause)
        terminalFailure = error
        throw error
    }
}
