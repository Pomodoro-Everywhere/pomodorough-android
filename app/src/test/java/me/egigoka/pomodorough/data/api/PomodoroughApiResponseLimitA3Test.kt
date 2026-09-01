package me.egigoka.pomodorough.data.api

import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.BufferedSource
import okio.GzipSink
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PomodoroughApiResponseLimitA3Test {
    private lateinit var server: MockWebServer
    private lateinit var api: PomodoroughApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = PomodoroughApi(
            server.url("/api/v1/").toString(),
            OkHttpClient(),
            Json { ignoreUnknownKeys = true },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fixedLengthSuccessOneByteBelowLimitIsAccepted() = runTest {
        server.enqueue(jsonResponseA3(challengeBodyA3(limitBytesA3 - 1)))

        assertEquals("sealed", api.createChallenge().challenge)
    }

    @Test
    fun chunkedSuccessAtExactRawByteLimitIsAccepted() = runTest {
        val body = challengeBodyA3(limitBytesA3)
        server.enqueue(jsonResponseA3(body).setChunkedBody(body, 64 * 1024))

        assertEquals("sealed", api.createChallenge().challenge)
    }

    @Test
    fun chunkedSuccessOneRawByteOverLimitIsRejected() = runTest {
        val body = challengeBodyA3(limitBytesA3 + 1)
        server.enqueue(jsonResponseA3(body).setChunkedBody(body, 64 * 1024))

        val error = captureA3<IOException> { api.createChallenge() }

        assertTrue(error.message.orEmpty().contains(API_RESPONSE_BODY_LIMIT_BYTES.toString()))
    }

    @Test
    fun structuredErrorOneByteBelowLimitKeepsApiClassification() = runTest {
        server.enqueue(jsonResponseA3(paddedJsonA3("""{"error":"limited"}""", limitBytesA3 - 1), 503))

        val error = captureA3<ApiException> { api.logout("access-token") }

        assertEquals(503, error.statusCode)
        assertEquals("limited", error.message)
    }

    @Test
    fun structuredErrorAtExactLimitKeepsApiClassification() = runTest {
        server.enqueue(jsonResponseA3(paddedJsonA3("""{"error":"limited"}""", limitBytesA3), 503))

        val error = captureA3<ApiException> { api.logout("access-token") }

        assertEquals(503, error.statusCode)
        assertEquals("limited", error.message)
    }

    @Test
    fun structuredErrorOneByteOverLimitIsRejectedBeforeDecode() = runTest {
        val body = paddedJsonA3("""{"error":"must-not-decode"}""", limitBytesA3 + 1)
        server.enqueue(jsonResponseA3(body, 503))

        val error = captureA3<IOException> { api.logout("access-token") }

        assertEquals(IOException::class.java, error::class.java)
        assertTrue(error.message.orEmpty().contains(API_RESPONSE_BODY_LIMIT_BYTES.toString()))
    }

    @Test
    fun utf8MultibytePayloadUsesBytesAtBoundary() = runTest {
        val allowed = challengeBodyA3(limitBytesA3, "scellé-火")
        val oversized = challengeBodyA3(limitBytesA3 + 1, "scellé-火")
        server.enqueue(jsonResponseA3(allowed))
        server.enqueue(jsonResponseA3(oversized))

        assertTrue(allowed.length < limitBytesA3)
        assertEquals("scellé-火", api.createChallenge().challenge)
        captureA3<IOException> { api.createChallenge() }
    }

    @Test
    fun utf8CharacterSplitAcrossSourceReadsDecodesExactly() = runTest {
        val jsonPrefix = "{\"challenge\":\""
        val padding = "a".repeat(responseReadBytesA3 - 1 - jsonPrefix.toByteArray().size)
        val challenge = "${padding}火"
        val bodyBytes = challengeJsonA3(challenge).toByteArray()
        val body = TrackingResponseBodyA3(Buffer().write(bodyBytes), bodyBytes.size.toLong())
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        assertEquals(responseReadBytesA3 - 1, jsonPrefix.toByteArray().size + padding.length)
        assertEquals(challenge, customApi.createChallenge().challenge)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun malformedUtf8KeepsReplacementCharacterDecodeSemantics() = runTest {
        val body = Buffer()
            .writeUtf8("{\"challenge\":\"seal")
            .writeByte(0xc3)
            .writeByte(0x28)
            .writeUtf8("\",\"nonce\":\"nonce\",\"expiresAt\":\"2026-01-01T00:05:00Z\"}")
        server.enqueue(rawJsonResponseA3(body))

        assertEquals("seal\uFFFD(", api.createChallenge().challenge)
    }

    @Test
    fun declaredCharsetAndBomMatchResponseBodyCharStreamSemantics() = runTest {
        val latinBytes = challengeJsonA3("scellé").toByteArray(Charsets.ISO_8859_1)
        val latinBody = TrackingResponseBodyA3(
            Buffer().write(latinBytes),
            latinBytes.size.toLong(),
            "application/json; charset=iso-8859-1".toMediaType(),
        )
        val utf16Bytes = challengeJsonA3("火").toByteArray(Charsets.UTF_16LE)
        val bomBuffer = Buffer().writeByte(0xff).writeByte(0xfe).write(utf16Bytes)
        val bomBody = TrackingResponseBodyA3(
            bomBuffer,
            bomBuffer.size,
            "application/json; charset=utf-8".toMediaType(),
        )

        assertEquals("scellé", apiWithFactoryA3(RespondingCallFactoryA3(latinBody, 200)).createChallenge().challenge)
        assertEquals("火", apiWithFactoryA3(RespondingCallFactoryA3(bomBody, 200)).createChallenge().challenge)
        assertEquals(1, latinBody.closeCount.get())
        assertEquals(1, bomBody.closeCount.get())
    }

    @Test
    fun transparentGzipUsesDecompressedByteCount() = runTest {
        server.enqueue(gzipResponseA3(challengeBodyA3(limitBytesA3)))
        server.enqueue(gzipResponseA3(challengeBodyA3(limitBytesA3 + 1)))

        assertEquals("sealed", api.createChallenge().challenge)
        captureA3<IOException> { api.createChallenge() }
    }

    @Test
    fun unknownLengthInfiniteBodyStopsWithinOneBoundedSourceRead() = runTest {
        val source = EndlessSourceA3()
        val body = TrackingResponseBodyA3(source.buffer(), -1L)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        captureA3<IOException> { customApi.createChallenge() }

        assertTrue(source.readBytes >= API_RESPONSE_BODY_LIMIT_BYTES + 1L)
        assertTrue(source.readBytes <= API_RESPONSE_BODY_LIMIT_BYTES + responseReadBytesA3)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun directBufferedSourceLeavesBytesBeyondLimitProbeUnread() = runTest {
        val unreadBytes = 1024L
        val source = Buffer().write(ByteArray(limitBytesA3 + 1 + unreadBytes.toInt()))
        val body = TrackingResponseBodyA3(source, -1L)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        captureA3<IOException> { customApi.createChallenge() }

        assertEquals(unreadBytes, source.size)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun streamingDecodeKeepsBodySourceDestinationBounded() = runTest {
        val encoded = Buffer().writeUtf8(challengeBodyA3(limitBytesA3))
        val probe = DestinationReadProbeA3()
        val source = destinationProbeBufferedSourceA3(encoded, probe)
        val body = TrackingResponseBodyA3(source, encoded.size)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        assertEquals("sealed", customApi.createChallenge().challenge)
        assertTrue(probe.maxDestinationBytes <= responseReadBytesA3)
        assertTrue(probe.maxRequestedBytes <= responseReadBytesA3)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun bodyDecodeBytecodeDoesNotUseReaderAccumulator() {
        val resource = "/${PomodoroughApi::class.java.name.replace('.', '/')}.class"
        val bytecode = requireNotNull(PomodoroughApi::class.java.getResourceAsStream(resource))
            .use { it.readBytes() }
            .toString(Charsets.ISO_8859_1)

        assertTrue("Reader.readText allocates a full StringWriter", "TextStreamsKt" !in bytecode)
        assertTrue("Response body decode must not allocate a StringWriter", "StringWriter" !in bytecode)
        assertTrue("Response body decode must not use Reader", "java/io/Reader" !in bytecode)
        assertTrue("Response body decode must not use charStream", "charStream" !in bytecode)
        assertTrue("ResponseBody.string keeps BOM-aware decoding", "string" in bytecode)
    }

    @Test
    fun dishonestLengthAndLateSourceGrowthAreRejectedAndClosed() = runTest {
        val initial = Buffer().writeUtf8(challengeBodyA3(limitBytesA3))
        val body = TrackingResponseBodyA3(GrowingSourceA3(initial).buffer(), 1L)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        captureA3<IOException> { customApi.createChallenge() }

        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun overreportedLengthDoesNotRejectSmallActualBody() = runTest {
        val payload = challengeBodyA3(1024)
        val body = TrackingResponseBodyA3(
            Buffer().writeUtf8(payload),
            API_RESPONSE_BODY_LIMIT_BYTES + 1L,
        )
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        assertEquals("sealed", customApi.createChallenge().challenge)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun sourceReadFailurePropagatesAndClosesResponseOnce() = runTest {
        val source = FailingSourceA3(Buffer().writeUtf8(challengeBodyA3(1024))).buffer()
        val body = TrackingResponseBodyA3(source, -1L)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        val error = captureA3<IOException> { customApi.createChallenge() }

        assertEquals("synthetic read failure", error.message)
        assertEquals(1, body.closeCount.get())
    }

    @Test
    fun zeroReadFailsClosedAndClosesResponse() = runTest {
        assertInvalidReadFailsClosedA3({ 0L }, "made no progress: 0")
    }

    @Test
    fun malformedNegativeReadFailsClosedAndClosesResponse() = runTest {
        assertInvalidReadFailsClosedA3({ -2L }, "made no progress: -2")
    }

    @Test
    fun oversizedReadFailsClosedAndClosesResponse() = runTest {
        assertInvalidReadFailsClosedA3({ requested -> requested + 1L }, "invalid read count")
    }

    @Test
    fun dataReturnedWithEofIsRejectedAndNeverExposed() {
        val source = malformedReadBufferedSourceA3 { sink, _ ->
            sink.writeUtf8("hidden")
            -1L
        }

        assertTerminalFailureScrubsDataA3(source, "data with EOF")
    }

    @Test
    fun dataWrittenBeforeFailureIsRejectedAndNeverExposed() {
        val source = malformedReadBufferedSourceA3 { sink, _ ->
            sink.writeUtf8("hidden")
            throw IllegalStateException("unchecked upstream failure")
        }

        assertTerminalFailureScrubsDataA3(source, "data before failing")
    }

    @Test
    fun cleanEofRemainsTerminalAcrossRepeatedReads() {
        val readCount = AtomicInteger(0)
        val upstream = malformedReadBufferedSourceA3 { _, _ ->
            readCount.incrementAndGet()
            -1L
        }
        val limited = ResponseBodyLimitSource(upstream)
        val sink = Buffer()

        assertEquals(-1L, limited.read(sink, responseReadBytesA3.toLong()))
        assertEquals(-1L, limited.read(sink, responseReadBytesA3.toLong()))
        assertEquals(1, readCount.get())
        assertEquals(0L, sink.size)
    }

    @Test
    fun sourceErrorRemainsTerminalAcrossRepeatedReads() {
        val expected = IOException("terminal source failure")
        val readCount = AtomicInteger(0)
        val upstream = malformedReadBufferedSourceA3 { _, _ ->
            readCount.incrementAndGet()
            throw expected
        }
        val limited = ResponseBodyLimitSource(upstream)
        val sink = Buffer()

        val first = captureSynchronousA3<IOException> { limited.read(sink, responseReadBytesA3.toLong()) }
        val repeated = captureSynchronousA3<IOException> { limited.read(sink, responseReadBytesA3.toLong()) }

        assertTrue(expected === first)
        assertTrue(first === repeated)
        assertEquals(1, readCount.get())
        assertEquals(0L, sink.size)
    }

    private suspend fun assertInvalidReadFailsClosedA3(
        readResult: (Long) -> Long,
        expectedMessage: String,
    ) {
        val body = TrackingResponseBodyA3(malformedReadBufferedSourceA3(readResult), -1L)
        val customApi = apiWithFactoryA3(RespondingCallFactoryA3(body, 200))

        val error = captureA3<IOException> { customApi.createChallenge() }

        assertTrue(error.message.orEmpty().contains(expectedMessage))
        assertEquals(1, body.closeCount.get())
    }

    private fun assertTerminalFailureScrubsDataA3(
        upstream: BufferedSource,
        expectedMessage: String,
    ) {
        val limited = ResponseBodyLimitSource(upstream)
        val sink = Buffer()
        val first = captureSynchronousA3<IOException> { limited.read(sink, responseReadBytesA3.toLong()) }
        val repeated = captureSynchronousA3<IOException> { limited.read(sink, responseReadBytesA3.toLong()) }

        assertTrue(first.message.orEmpty().contains(expectedMessage))
        assertTrue(first === repeated)
        assertEquals(0L, sink.size)
    }

    @Test
    fun successAndErrorBodiesCloseExactlyOnce() = runTest {
        val successBody = trackedBodyA3(challengeBodyA3(1024))
        val errorBody = trackedBodyA3("""{"error":"maintenance"}""")

        val challenge = apiWithFactoryA3(RespondingCallFactoryA3(successBody, 200)).createChallenge()
        val error = captureA3<ApiException> {
            apiWithFactoryA3(RespondingCallFactoryA3(errorBody, 503)).logout("token")
        }

        assertEquals("sealed", challenge.challenge)
        assertEquals("maintenance", error.message)
        assertEquals(1, successBody.closeCount.get())
        assertEquals(1, errorBody.closeCount.get())
    }

    @Test
    fun boundedErrorsPreserveMultipleStatusClassifications() = runTest {
        server.enqueue(jsonResponseA3("""{"error":"forbidden"}""", 403))
        server.enqueue(jsonResponseA3("not-json", 502))

        val forbidden = captureA3<ApiException> { api.me("token") }
        val gateway = captureA3<ApiException> { api.me("token") }

        assertEquals(403, forbidden.statusCode)
        assertEquals("forbidden", forbidden.message)
        assertEquals(502, gateway.statusCode)
        assertEquals("Request failed (502)", gateway.message)
    }

    @Test
    fun emptyAndBodylessContractsRemainUnchanged() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(jsonResponseA3(""))
        server.enqueue(jsonResponseA3("", 502))

        api.logout("token")
        val emptySuccess = captureA3<IOException> { api.createChallenge() }
        val emptyError = captureA3<ApiException> { api.logout("token") }

        assertEquals("Server returned an empty response", emptySuccess.message)
        assertEquals(502, emptyError.statusCode)
        assertEquals("Request failed (502)", emptyError.message)
    }

    @Test
    fun repeatedRequestsKeepIndependentReadBudgets() = runTest {
        repeat(4) { server.enqueue(jsonResponseA3(challengeBodyA3(1024, "sealed-$it"))) }
        server.enqueue(jsonResponseA3(challengeBodyA3(limitBytesA3 + 1)))
        repeat(4) { server.enqueue(jsonResponseA3(challengeBodyA3(1024, "renewed-$it"))) }

        repeat(4) { assertEquals("sealed-$it", api.createChallenge().challenge) }
        captureA3<IOException> { api.createChallenge() }
        repeat(4) { assertEquals("renewed-$it", api.createChallenge().challenge) }
    }

    @Test
    fun cancelledUndeliveredResponseIsUnreadAndClosedOnce() = runTest {
        val source = CountingSourceA3(Buffer().writeUtf8(challengeBodyA3(1024)))
        val body = TrackingResponseBodyA3(source.buffer(), 1024L)
        val factory = DeferredCallFactoryA3(body)
        val request = launch { apiWithFactoryA3(factory).createChallenge() }
        runCurrent()

        request.cancel()
        runCurrent()
        factory.call.respond()
        runCurrent()

        assertEquals(1, factory.call.cancelCount.get())
        assertEquals(0, source.readCount.get())
        assertEquals(1, body.closeCount.get())
    }
}

private fun apiWithFactoryA3(factory: Call.Factory) = PomodoroughApi(
    baseUrl = "https://example.invalid/api/v1/",
    client = OkHttpClient(),
    json = Json { ignoreUnknownKeys = true },
    callFactory = factory,
)

private fun jsonResponseA3(body: String, code: Int = 200) = MockResponse()
    .setResponseCode(code)
    .setHeader("Content-Type", "application/json; charset=utf-8")
    .setBody(body)

private fun rawJsonResponseA3(body: Buffer, code: Int = 200) = MockResponse()
    .setResponseCode(code)
    .setHeader("Content-Type", "application/json; charset=utf-8")
    .setBody(body)

private fun gzipResponseA3(body: String): MockResponse {
    val compressed = Buffer()
    GzipSink(compressed).buffer().use { it.writeUtf8(body) }
    return MockResponse()
        .setHeader("Content-Type", "application/json; charset=utf-8")
        .setHeader("Content-Encoding", "gzip")
        .setBody(compressed)
}

private fun challengeBodyA3(bytes: Int, challenge: String = "sealed") = paddedJsonA3(
    challengeJsonA3(challenge),
    bytes,
)

private fun challengeJsonA3(challenge: String) =
    """{"challenge":"$challenge","nonce":"nonce","expiresAt":"2026-01-01T00:05:00Z"}"""

private fun trackedBodyA3(content: String): TrackingResponseBodyA3 {
    val buffer = Buffer().writeUtf8(content)
    return TrackingResponseBodyA3(buffer, buffer.size)
}

private fun paddedJsonA3(json: String, bytes: Int): String {
    val encodedSize = json.toByteArray(Charsets.UTF_8).size
    require(encodedSize <= bytes)
    return json + " ".repeat(bytes - encodedSize)
}

private suspend inline fun <reified T : Throwable> captureA3(
    crossinline block: suspend () -> Unit,
): T = try {
    block()
    throw AssertionError("Expected ${T::class.java.simpleName}")
} catch (error: Throwable) {
    assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
    error as T
}

private inline fun <reified T : Throwable> captureSynchronousA3(block: () -> Unit): T = try {
    block()
    throw AssertionError("Expected ${T::class.java.simpleName}")
} catch (error: Throwable) {
    assertTrue("Expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
    error as T
}

private class RespondingCallFactoryA3(
    private val body: ResponseBody,
    private val code: Int,
) : Call.Factory {
    override fun newCall(request: Request): Call = TestCallA3(request, body, code, true)
}

private class DeferredCallFactoryA3(
    private val body: ResponseBody,
) : Call.Factory {
    lateinit var call: TestCallA3

    override fun newCall(request: Request): Call =
        TestCallA3(request, body, 200, false).also { call = it }
}

private class TestCallA3(
    private val request: Request,
    private val body: ResponseBody,
    private val code: Int,
    private val respondOnEnqueue: Boolean,
) : Call {
    private lateinit var callback: Callback
    private val executed = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    val cancelCount = AtomicInteger(0)

    override fun request(): Request = request
    override fun execute(): Response = error("Only asynchronous execution is supported")

    override fun enqueue(responseCallback: Callback) {
        check(executed.compareAndSet(false, true))
        callback = responseCallback
        if (respondOnEnqueue) respond()
    }

    override fun cancel() {
        cancelCount.incrementAndGet()
        cancelled.set(true)
    }

    override fun isExecuted(): Boolean = executed.get()
    override fun isCanceled(): Boolean = cancelled.get()
    override fun timeout(): Timeout = Timeout.NONE
    override fun clone(): Call = TestCallA3(request, body, code, respondOnEnqueue)

    fun respond() {
        callback.onResponse(
            this,
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("test")
                .body(body)
                .build(),
        )
    }
}

private class TrackingResponseBodyA3(
    private val upstream: BufferedSource,
    private val reportedLength: Long,
    private val mediaType: MediaType = "application/json; charset=utf-8".toMediaType(),
) : ResponseBody() {
    val closeCount = AtomicInteger(0)

    override fun contentType(): MediaType = mediaType
    override fun contentLength(): Long = reportedLength
    override fun source(): BufferedSource = upstream

    override fun close() {
        closeCount.incrementAndGet()
        super.close()
    }
}

private class DestinationReadProbeA3 {
    var maxDestinationBytes = 0L
    var maxRequestedBytes = 0L
}

private fun destinationProbeBufferedSourceA3(
    upstream: Buffer,
    probe: DestinationReadProbeA3,
): BufferedSource = bufferedSourceProxyA3(upstream) { sink, byteCount ->
    probe.maxDestinationBytes = maxOf(probe.maxDestinationBytes, sink.size)
    probe.maxRequestedBytes = maxOf(probe.maxRequestedBytes, byteCount)
    upstream.read(sink, byteCount)
}

private fun malformedReadBufferedSourceA3(
    readResult: (Long) -> Long,
): BufferedSource = malformedReadBufferedSourceA3 { sink, byteCount ->
    readResult(byteCount).also { read ->
        if (read > 0L) sink.write(ByteArray(read.toInt()))
    }
}

private fun malformedReadBufferedSourceA3(
    readBlock: (Buffer, Long) -> Long,
): BufferedSource = bufferedSourceProxyA3(Buffer(), readBlock)

private fun bufferedSourceProxyA3(
    delegate: BufferedSource,
    readBlock: (Buffer, Long) -> Long,
): BufferedSource {
    val sourceRead = BufferedSource::class.java.getMethod(
        "read",
        Buffer::class.java,
        java.lang.Long.TYPE,
    )
    return Proxy.newProxyInstance(
        BufferedSource::class.java.classLoader,
        arrayOf(BufferedSource::class.java),
    ) { _, method, arguments ->
        if (method == sourceRead) {
            readBlock(arguments!![0] as Buffer, arguments[1] as Long)
        } else {
            method.invoke(delegate, *(arguments ?: emptyArray()))
        }
    } as BufferedSource
}

private class GrowingSourceA3(
    private val initial: Buffer,
) : Source {
    private val lateByte = Buffer().writeByte(' '.code)

    override fun read(sink: Buffer, byteCount: Long): Long = when {
        !initial.exhausted() -> initial.read(sink, byteCount)
        else -> lateByte.read(sink, byteCount)
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private class FailingSourceA3(
    private val initial: Buffer,
) : Source {
    private var delivered = false

    override fun read(sink: Buffer, byteCount: Long): Long {
        if (delivered) throw IOException("synthetic read failure")
        delivered = true
        return initial.read(sink, minOf(byteCount, 32L))
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private class EndlessSourceA3 : Source {
    private val bytes = ByteArray(responseReadBytesA3) { ' '.code.toByte() }
    var readBytes = 0L
        private set

    override fun read(sink: Buffer, byteCount: Long): Long {
        val count = minOf(byteCount, bytes.size.toLong()).toInt()
        sink.write(bytes, 0, count)
        readBytes += count
        return count.toLong()
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private class CountingSourceA3(
    private val upstream: Buffer,
) : Source {
    val readCount = AtomicInteger(0)

    override fun read(sink: Buffer, byteCount: Long): Long {
        readCount.incrementAndGet()
        return upstream.read(sink, byteCount)
    }

    override fun timeout(): Timeout = Timeout.NONE
    override fun close() = Unit
}

private val limitBytesA3 = API_RESPONSE_BODY_LIMIT_BYTES.toInt()
private const val responseReadBytesA3 = 8 * 1024
