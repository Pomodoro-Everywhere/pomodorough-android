package me.egigoka.pomodorough.data.api

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Timeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PomodoroughApiCancellationA2Test {
    @Test
    fun cancellationBeforeResponseCancelsCallAndClosesLateResponseOnce() = runTest {
        val fixture = CancellationFixture()
        val request = launch { fixture.api.createChallenge() }
        runCurrent()

        request.cancel()
        runCurrent()
        val body = fixture.respond()

        assertEquals(1, fixture.call.cancelCount.get())
        assertEquals(1, body.closeCount.get())
        assertTrue(request.isCancelled)
    }

    @Test
    fun cancellationBetweenCallbackAndDeliveryClosesResponseOnce() = runTest {
        val fixture = CancellationFixture()
        val request = launch { fixture.api.createChallenge() }
        runCurrent()

        val body = fixture.respond()
        assertEquals(0, body.closeCount.get())
        request.cancel()
        runCurrent()

        assertEquals(1, fixture.call.cancelCount.get())
        assertEquals(1, body.closeCount.get())
        assertTrue(request.isCancelled)
    }

    @Test
    fun deliveredResponseRemainsOwnedByCallerUsePath() = runTest {
        val fixture = CancellationFixture()
        val request = async { fixture.api.createChallenge() }
        runCurrent()

        val body = fixture.respond()
        runCurrent()

        assertEquals("sealed", request.await().challenge)
        assertEquals(1, body.closeCount.get())
        assertEquals(0, fixture.call.cancelCount.get())
    }

    @Test
    fun failureAfterCancellationCannotResumeContinuationAgain() = runTest {
        val fixture = CancellationFixture()
        val request = launch { fixture.api.createChallenge() }
        runCurrent()

        request.cancel()
        runCurrent()
        fixture.call.fail(IOException("late failure"))

        assertEquals(1, fixture.call.cancelCount.get())
        assertTrue(request.isCancelled)
    }

    @Test
    fun ordinaryFailureReachesCallerWithoutCancellation() = runTest {
        val fixture = CancellationFixture()
        val expected = IOException("network unavailable")
        supervisorScope {
            val request = async { fixture.api.createChallenge() }
            testScheduler.runCurrent()
            fixture.call.fail(expected)
            testScheduler.runCurrent()
            val error = runCatching { request.await() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(expected.message, error?.message)
        }
        assertEquals(0, fixture.call.cancelCount.get())
    }

    @Test
    fun synchronousResponseCompletesAndClosesThroughCaller() = runTest {
        val fixture = CancellationFixture(synchronousResponse = true)

        val challenge = fixture.api.createChallenge()

        assertEquals("sealed", challenge.challenge)
        assertEquals(1, fixture.body.closeCount.get())
        assertEquals(0, fixture.call.cancelCount.get())
    }

    @Test
    fun duplicateFailureAfterResponseCannotDoubleResume() = runTest {
        val fixture = CancellationFixture()
        val request = async { fixture.api.createChallenge() }
        runCurrent()

        val body = fixture.respond()
        fixture.call.fail(IOException("duplicate callback"))
        runCurrent()

        assertEquals("sealed", request.await().challenge)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun repeatedCallbackCancellationRacesCloseEveryResponseOnce() = runTest {
        repeat(100) {
            val fixture = CancellationFixture()
            val request = launch { fixture.api.createChallenge() }
            runCurrent()

            val body = fixture.respond()
            request.cancel(CancellationException("race"))
            runCurrent()

            assertEquals(1, body.closeCount.get())
            assertEquals(1, fixture.call.cancelCount.get())
        }
    }
}

private class CancellationFixture(
    synchronousResponse: Boolean = false,
) {
    private val factory = ControlledCallFactory(synchronousResponse)
    val api = PomodoroughApi(
        baseUrl = "https://example.invalid/api/v1/",
        client = OkHttpClient(),
        json = Json { ignoreUnknownKeys = true },
        callFactory = factory,
    )
    val call: ControlledCall get() = factory.call
    val body: CountingResponseBody get() = factory.body

    fun respond(): CountingResponseBody {
        call.respond(body)
        return body
    }
}

private class ControlledCallFactory(
    private val synchronousResponse: Boolean,
) : Call.Factory {
    lateinit var call: ControlledCall
    val body = CountingResponseBody(challengeJson)

    override fun newCall(request: Request): Call {
        call = ControlledCall(request)
        if (synchronousResponse) call.onEnqueue = { it.respond(body) }
        return call
    }
}

private class ControlledCall(
    private val request: Request,
) : Call {
    private lateinit var callback: Callback
    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    var onEnqueue: ((ControlledCall) -> Unit)? = null
    val cancelCount = AtomicInteger(0)

    override fun request(): Request = request
    override fun execute(): Response = error("Only asynchronous execution is supported")

    override fun enqueue(responseCallback: Callback) {
        check(executed.compareAndSet(false, true))
        callback = responseCallback
        onEnqueue?.invoke(this)
    }

    override fun cancel() {
        cancelCount.incrementAndGet()
        cancelled.set(true)
    }

    override fun isExecuted(): Boolean = executed.get()
    override fun isCanceled(): Boolean = cancelled.get()
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = ControlledCall(request)

    fun respond(body: ResponseBody) {
        callback.onResponse(
            this,
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build(),
        )
    }

    fun fail(error: IOException) {
        callback.onFailure(this, error)
    }
}

private class CountingResponseBody(
    content: String,
) : ResponseBody() {
    private val content = Buffer().writeUtf8(content)
    val closeCount = AtomicInteger(0)

    override fun contentType(): MediaType? = null
    override fun contentLength(): Long = content.size
    override fun source(): BufferedSource = content

    override fun close() {
        closeCount.incrementAndGet()
        super.close()
    }
}

private const val challengeJson =
    """{"challenge":"sealed","nonce":"nonce","expiresAt":"2026-01-01T00:05:00Z"}"""
