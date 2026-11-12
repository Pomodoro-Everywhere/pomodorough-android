package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.SpanContext
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Geo
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.Request
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// R54-L03: final-event diagnostic key privacy, mirroring desktop
// tests/test_r54_l03_key_privacy.py. Synthetic canaries only. Events pass the
// production hooks from CrashReportingInit and are rendered by the SDK JSON
// serializer, without SDK initialization or network.

private const val CANARY = "KeyCanaryHazel"
private const val BS = "\\"
private const val REDACTED = SentryScrubber.REDACTED
private const val PLACEHOLDER_ENTRY = "\"[Filtered key 1]\":\"[REDACTED]\""

private val textKeys: Map<String, String> = linkedMapOf(
    "email" to "$CANARY@example.test",
    "percent_email" to "$CANARY%40example.test",
    "percent_name" to "%4B$CANARY",
    "double_percent" to "$CANARY%2540x",
    "tab_inside" to "$CANARY\tnote",
    "tab_leading" to "\t$CANARY",
    "tab_trailing" to "$CANARY\t",
    "newline" to "$CANARY\nnote",
    "carriage_return" to "$CANARY\rnote",
    "nul" to "$CANARY${Char(0)}note",
    "escape_control" to "$CANARY${Char(0x1b)}note",
    "delete_control" to "$CANARY${Char(0x7f)}",
    "space_title" to "Call $CANARY tomorrow",
    "trailing_space" to "$CANARY ",
    "zero_width" to "$CANARY${Char(0x200b)}",
    "fullwidth_at" to "$CANARY${Char(0xff20)}example.test",
    "accented" to "caf${Char(0xe9)}$CANARY",
    "json_escape" to "$CANARY${BS}u0040example.test",
    "c_escape" to "$CANARY${BS}tnote",
    "posix_path" to "/Users/$CANARY/notes",
    "windows_path" to "C:${BS}Users$BS$CANARY",
    "url" to "https://example.invalid/?$CANARY=1",
    "colon" to "$CANARY:secret",
    "quoted" to "\"$CANARY\"",
    "bracketed" to "[$CANARY]",
    "placeholder_lookalike" to "[Filtered key 1]$CANARY",
    "leading_digit" to "1$CANARY",
    "leading_dash" to "-$CANARY",
    "leading_dot" to ".$CANARY",
    "invite" to "pomodorough1.$CANARY",
    "invite_upper" to "POMODOROUGH1$CANARY",
    "dotted_quad" to "peer-10.0.0.1-$CANARY",
    "short_dotted_quad" to "${CANARY}_1.2.3.4",
    "six_digits" to "${CANARY}_123456",
    "uuid" to "$CANARY-123e4567-e89b-12d3-a456-426614174000",
    "opaque_run" to "${CANARY}0a1b",
    "length_65" to CANARY + "x".repeat(65 - CANARY.length),
    "very_long" to CANARY.repeat(1_000),
)

/** A non-String key whose text form names a person. */
private class DisplayedKey {
    override fun toString(): String = "$CANARY@example.test"
}

/** An enum whose display text differs from its constant name. */
private enum class DisplayedEnum {
    PLAIN;

    override fun toString(): String = "$CANARY@example.test"
}

/** An ordinary object the SDK would write through its reflection serializer. */
private class DiagnosticHolder(val fields: Map<String, String>)

private val objectKeys: Map<String, Any> = linkedMapOf(
    "bytes" to "$CANARY@example.test".toByteArray(),
    "pair" to Pair(CANARY, "note"),
    "set" to setOf(CANARY),
    "list" to listOf(CANARY),
    "object" to DisplayedKey(),
    "enum" to DisplayedEnum.PLAIN,
    // Safe text in a type that is not exactly String.
    "char_sequence" to StringBuilder("plain"),
)

private val unsafeKeys: Map<String, Any> = textKeys + objectKeys

private class KeyBoundary(
    val acceptsObjectKeys: Boolean,
    val neighbor: String,
    val build: (key: Any, value: String) -> SentryEvent,
)

@Suppress("UNCHECKED_CAST")
private fun objectEntries(key: Any, value: Any?, neighbor: String, neighborValue: Any): Map<String, Any?> {
    val entries = LinkedHashMap<Any, Any?>()
    entries[key] = value
    entries[neighbor] = neighborValue
    return entries as Map<String, Any?>
}

@Suppress("UNCHECKED_CAST")
private fun textEntries(key: Any, value: String, neighbor: String, neighborValue: String): Map<String, String> =
    objectEntries(key, value, neighbor, neighborValue) as Map<String, String>

@Suppress("UNCHECKED_CAST")
private fun putData(crumb: Breadcrumb, key: Any, value: Any) {
    (crumb.data as MutableMap<Any, Any>)[key] = value
}

private fun eventWithFrame(
    configure: SentryStackFrame.() -> Unit,
    trace: SentryStackTrace.() -> Unit = {},
): SentryEvent =
    SentryEvent().apply {
        exceptions = listOf(
            SentryException().apply {
                type = "IOException"
                stacktrace = SentryStackTrace(listOf(SentryStackFrame().apply(configure))).apply(trace)
            },
        )
    }

private val eventMapBoundaries: Map<String, KeyBoundary> = linkedMapOf(
    "extras" to KeyBoundary(true, "\"detail\":\"disk unavailable\"") { key, value ->
        SentryEvent().apply { extras = objectEntries(key, value, "detail", "disk unavailable") }
    },
    "tags" to KeyBoundary(true, "\"component\":\"sync\"") { key, value ->
        SentryEvent().apply { tags = textEntries(key, value, "component", "sync") }
    },
    "context_name" to KeyBoundary(false, "\"sync\":{\"phase\":\"pull\"}") { key, value ->
        SentryEvent().apply {
            contexts.put(key as String, mapOf("phase" to value))
            contexts.put("sync", mapOf("phase" to "pull"))
        }
    },
    "context_field" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply { contexts.put("sync", objectEntries(key, value, "phase", "pull")) }
    },
    "nested_map" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply {
            extras = mapOf("outer" to mapOf("middle" to objectEntries(key, value, "phase", "pull")))
        }
    },
    "listed_map" to KeyBoundary(true, "{\"phase\":\"pull\"}") { key, value ->
        SentryEvent().apply {
            extras = mapOf("attempts" to listOf(mapOf("phase" to "pull"), objectEntries(key, value, "attempt", 2)))
        }
    },
    "array_map" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply { extras = mapOf("attempts" to arrayOf<Any>(objectEntries(key, value, "phase", "pull"))) }
    },
)

private val exceptionAndRequestBoundaries: Map<String, KeyBoundary> = linkedMapOf(
    "frame_vars" to KeyBoundary(true, "\"attempt\":2") { key, value ->
        eventWithFrame({ function = "pull"; vars = objectEntries(key, value, "attempt", 2) })
    },
    "registers" to KeyBoundary(true, "\"pc\":\"0x1f\"") { key, value ->
        eventWithFrame({ function = "pull" }) { registers = textEntries(key, value, "pc", "0x1f") }
    },
    "mechanism_data" to KeyBoundary(true, "\"attempt\":2") { key, value ->
        eventWithFrame({ function = "pull" }).apply {
            exceptions!!.single().mechanism = Mechanism().apply {
                type = "generic"
                data = objectEntries(key, value, "attempt", 2)
            }
        }
    },
    "request_headers" to KeyBoundary(true, "\"Accept\":\"application/json\"") { key, value ->
        SentryEvent().apply {
            request = Request().apply { headers = textEntries(key, value, "Accept", "application/json") }
        }
    },
    "request_envs" to KeyBoundary(true, "\"LANG\":\"C\"") { key, value ->
        SentryEvent().apply { request = Request().apply { envs = textEntries(key, value, "LANG", "C") } }
    },
    "request_others" to KeyBoundary(true, "\"mode\":\"fast\"") { key, value ->
        SentryEvent().apply { request = Request().apply { others = textEntries(key, value, "mode", "fast") } }
    },
)

private val identityAndBreadcrumbBoundaries: Map<String, KeyBoundary> = linkedMapOf(
    "user_data" to KeyBoundary(true, "\"plan\":\"free\"") { key, value ->
        SentryEvent().apply { user = User().apply { data = textEntries(key, value, "plan", "free") } }
    },
    "user_unknown" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply { user = User().apply { unknown = objectEntries(key, value, "phase", "pull") } }
    },
    "geo_unknown" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply {
            user = User().apply { geo = Geo().apply { unknown = objectEntries(key, value, "phase", "pull") } }
        }
    },
    "device_unknown" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply {
            contexts.setDevice(Device().apply { unknown = objectEntries(key, value, "phase", "pull") })
        }
    },
    "app_unknown" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply { contexts.setApp(App().apply { unknown = objectEntries(key, value, "phase", "pull") }) }
    },
    "os_unknown" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        SentryEvent().apply {
            val os = OperatingSystem().apply { unknown = objectEntries(key, value, "phase", "pull") }
            contexts.setOperatingSystem(os)
        }
    },
    "event_breadcrumb" to KeyBoundary(true, "\"phase\":\"pull\"") { key, value ->
        val crumb = Breadcrumb().apply { category = "sync"; setData("phase", "pull") }
        putData(crumb, key, value)
        SentryEvent().apply { addBreadcrumb(crumb) }
    },
)

private val keyBoundaries: Map<String, KeyBoundary> =
    eventMapBoundaries + exceptionAndRequestBoundaries + identityAndBreadcrumbBoundaries

private fun hookedOptions(): SentryOptions =
    SentryOptions().also { CrashReportingInit.configure(it, "https://key@sentry.invalid/1") }

private fun sentEvent(event: SentryEvent): SentryEvent =
    checkNotNull(checkNotNull(hookedOptions().beforeSend).execute(event, Hint()))

private fun render(event: SentryEvent): String {
    val writer = StringWriter()
    JsonSerializer(SentryOptions()).serialize(event, writer)
    return writer.toString()
}

private fun renderUser(user: User): String {
    val writer = StringWriter()
    JsonSerializer(SentryOptions()).serialize(user, writer)
    return writer.toString()
}

private fun assertNoCanary(rendering: String) {
    assertFalse(rendering.take(600), rendering.lowercase().contains(CANARY.lowercase()))
}

// Same text as desktop `_FILTERED_KEY`; only the redacted value differs.
private fun placeholder(position: Int): String = "[Filtered key $position]"

private fun redactedEntry(position: Int): Pair<String, String> = placeholder(position) to REDACTED

/** Sends [entries] nested under `extras.sync` and returns them in order. */
private fun scrubbedNested(entries: Map<*, *>): List<Pair<Any?, Any?>> {
    val sent = sentEvent(SentryEvent().apply { extras = mapOf("sync" to entries) })
    assertNoCanary(render(sent))
    return (sent.extras!!["sync"] as Map<*, *>).toList()
}

/** The list and array forms of one query, with the [key] pair first. */
private fun queryPairForms(key: Any?, value: Any?): List<Any> = listOf(
    listOf(listOf(key, value), listOf("next", "1")),
    arrayOf(arrayOf(key, value), arrayOf<Any?>("next", "1")),
)

private val filteredFirstPair: List<Any> = listOf(listOf(placeholder(1), REDACTED), listOf("next", "1"))

/** Sends [query] as breadcrumb `http.query` through both hooks; returns the hooked and sent values. */
private fun hookedQueryPairs(query: Any): Pair<Any?, Any?> {
    val options = hookedOptions()
    val crumb = Breadcrumb().apply { category = "http"; setData("http.query", query) }
    val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
    val hookedQuery = hooked.data["http.query"]
    val event = SentryEvent().apply { addBreadcrumb(hooked) }
    val sent = checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))
    assertNoCanary(render(sent))
    return hookedQuery to sent.breadcrumbs!!.single().data["http.query"]
}

private fun chain(depth: Int): Any = if (depth == 0) "pull" else mapOf("next" to chain(depth - 1))

private tailrec fun chainLeaf(value: Any?): Any? = if (value is Map<*, *>) chainLeaf(value["next"]) else value

@RunWith(Parameterized::class)
class R54L03KeyPrivacyMatrixTest(private val boundaryName: String, private val keyName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}/{1}")
        fun cases(): List<Array<Any>> = keyBoundaries.flatMap { (boundaryName, boundary) ->
            unsafeKeys
                .filter { (_, key) -> boundary.acceptsObjectKeys || key is String }
                .map { (keyName, _) -> arrayOf<Any>(boundaryName, keyName) }
        }
    }

    @Test
    fun finalEventReplacesPrivateKeyName() {
        val boundary = keyBoundaries.getValue(boundaryName)
        val rendered = render(sentEvent(boundary.build(unsafeKeys.getValue(keyName), "value $CANARY")))
        assertNoCanary(rendered)
        assertTrue(rendered.take(600), rendered.contains(PLACEHOLDER_ENTRY))
        assertTrue(rendered.take(600), rendered.contains(boundary.neighbor))
    }
}

@RunWith(Parameterized::class)
class R54L03BreadcrumbHookKeyTest(private val keyName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun keys(): List<Array<Any>> = unsafeKeys.keys.map { arrayOf<Any>(it) }
    }

    @Test
    fun breadcrumbHookThenEventHookReplacePrivateDataKey() {
        val options = hookedOptions()
        val crumb = Breadcrumb().apply {
            category = "sync"
            message = "pull failed"
            setData("phase", "pull")
            setData("attempt", 2)
        }
        putData(crumb, unsafeKeys.getValue(keyName), "value $CANARY")
        val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
        val hookedData = hooked.data.toMap()
        assertEquals(mapOf("phase" to "pull", redactedEntry(1), "attempt" to 2), hookedData)
        val event = SentryEvent().apply { addBreadcrumb(hooked) }
        val sent = checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))
        assertEquals(hookedData, sent.breadcrumbs!!.single().data.toMap())
        assertNoCanary(render(sent))
    }
}

@RunWith(Parameterized::class)
class R54L03QueryPairKeyTest(private val keyName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun keys(): List<Array<Any>> = unsafeKeys.keys.map { arrayOf<Any>(it) }
    }

    // Shared with desktop `test_query_forms_replace_private_parameter_names`.
    @Test
    fun queryPairFormsReplacePrivateParameterName() {
        queryPairForms(unsafeKeys.getValue(keyName), "value $CANARY").forEach { query ->
            val (hooked, sent) = hookedQueryPairs(query)
            assertEquals(query.javaClass.simpleName, filteredFirstPair, hooked)
            assertEquals(query.javaClass.simpleName, filteredFirstPair, sent)
        }
    }
}

class R54L03KeyPrivacyTest {
    private val levelImitations = listOf(
        "level\t", " level", "level ", "le\tvel", "level${Char(0)}", "lev%65l", "level:",
        "level${Char(0x200b)}", "${Char(0x142)}evel",
    )

    private val keptStaticNames = listOf(
        "level", "logger", "platform", "release", "environment", "event_id", "timestamp",
        "transaction", "fingerprint", "message", "formatted", "params", "exception", "values",
        "type", "module", "mechanism", "handled", "meta", "number", "name", "stacktrace", "frames",
        "filename", "abs_path", "function", "lineno", "colno", "in_app", "vars", "threads",
        "current", "crashed", "main", "contexts", "os", "build", "kernel_version", "version",
        "raw_description", "runtime", "trace", "trace_id", "span_id", "parent_span_id", "op",
        "description", "status", "origin", "app", "app_start_time", "breadcrumbs", "category",
        "data", "url", "http.method", "http.query", "reason", "tags", "extra", "sdk", "packages",
        "integrations", "request", "method", "headers", "Accept", "Content-Type", "User-Agent",
        "X-Request-Id", "screen", "action", "view.id", "view.class", "view.tag", "status_code",
        "network_type", "download_bandwidth", "vpn_active", "charging", "battery", "arm64-v8a",
        "x86_64", "utf-8", "ipv6", "http2", "phase", "attempt", "component", "detail",
        "retryCount", "_private", "__class__", "UPPER_CASE", "camelCaseName", "a", "x".repeat(64),
        "abcdefghijklmnopq1", "v12345", "a1b2c3d4e5", "android", "kotlin.coroutines",
    )

    // Exact names whose values the existing key filter still redacts.
    private val filteredStaticNames = listOf(
        "token", "password", "authorization", "cookie", "dsn", "session_id", "device", "room",
        "state", "isSideLoaded", "privateKey", "peerId", "endpointTicket", "oauth",
    )

    // Shared with desktop `test_diagnostic_key_boundaries`: true keeps the name.
    private val keyShapeTable = listOf(
        "a".repeat(64) to true, "a".repeat(65) to false, "v12345" to true, "v123456" to false,
        "v1_2_3_4_5_6" to false, "abcdefghijklmn12" to false, "abcdefghijklm12" to true,
        "abcdefghijklmno1" to true, "abc-defghijklmn12" to true, "a_b" to true, "_a" to true,
        "A" to true, "" to true, "1a" to false, "0" to true, "12345" to true, "123456" to false,
        "1.2" to false, "-1" to false, "pomodorough1" to false, "Pomodorough1x" to false,
        "pomodorough2" to true, "v1.2.3.4" to false, "v1.2.3" to true, "a@b" to false,
        "a%b" to false, "a~b" to false, "a+b" to false, "a/b" to false, "a:b" to false,
    )

    @Test
    fun collidingKeysKeepSeparatePlaceholdersInOrder() {
        val scrubbed = scrubbedNested(
            linkedMapOf(
                "note\ta" to "first $CANARY",
                "note a" to "second $CANARY",
                "phase" to "pull",
                "note\na" to "third $CANARY",
                "note%09a" to "fourth $CANARY",
            ),
        )
        val expected = listOf(
            redactedEntry(1), redactedEntry(2), "phase" to "pull", redactedEntry(3), redactedEntry(4),
        )
        assertEquals(expected, scrubbed)
    }

    @Test
    fun collidingTopLevelKeysNeverOverwriteEachOther() {
        val colliding = listOf("note\ta", "note a", "note\na", "note%09a")
        val event = SentryEvent().apply {
            extras = colliding.associateWith { "extra $CANARY" } + ("phase" to "pull")
            tags = colliding.associateWith { "tag $CANARY" } + ("component" to "sync")
        }
        val sent = sentEvent(event)
        assertNoCanary(render(sent))
        val placeholders = (1..colliding.size).associate { redactedEntry(it) }
        assertEquals(placeholders + ("phase" to "pull"), sent.extras)
        assertEquals(placeholders + ("component" to "sync"), sent.tags)
    }

    @Test
    fun placeholderLookalikesAreRenumbered() {
        val scrubbed = scrubbedNested(
            linkedMapOf(
                placeholder(2) to "claimed $CANARY",
                "phase" to "pull",
                placeholder(1) to "claimed $CANARY",
            ),
        )
        assertEquals(listOf(redactedEntry(1), "phase" to "pull", redactedEntry(2)), scrubbed)
    }

    @Test
    fun nonStringKeysNeverMergeWithStringNames() {
        val entries = LinkedHashMap<Any?, Any?>()
        entries[1] = "number $CANARY"
        entries["1"] = "string one"
        entries[null] = "null $CANARY"
        entries[2.5] = "double $CANARY"
        entries[CANARY.toByteArray()] = "bytes $CANARY"
        entries["count"] = 3
        val expected = listOf(
            redactedEntry(1), "1" to "string one", redactedEntry(2), redactedEntry(3),
            redactedEntry(4), "count" to 3,
        )
        assertEquals(expected, scrubbedNested(entries))
    }

    @Test
    fun placeholderNumbersRestartInEveryMap() {
        val event = SentryEvent().apply {
            extras = mapOf(
                "first" to linkedMapOf("a\tb" to "x $CANARY"),
                "second" to linkedMapOf("c\td" to "y $CANARY", "e\tf" to "z $CANARY"),
            )
        }
        val sent = sentEvent(event)
        assertNoCanary(render(sent))
        assertEquals(listOf(redactedEntry(1)), (sent.extras!!["first"] as Map<*, *>).toList())
        assertEquals(listOf(redactedEntry(1), redactedEntry(2)), (sent.extras!!["second"] as Map<*, *>).toList())
    }

    @Test
    fun staticNameImitationsBecomePlaceholders() {
        val crumb = Breadcrumb().apply {
            setData("level", "info")
            levelImitations.forEach { setData(it, "imitation $CANARY") }
        }
        val event = SentryEvent().apply {
            tags = levelImitations.associateWith { "imitation $CANARY" } + ("level" to "error")
            levelImitations.forEach { contexts.put(it, mapOf("phase" to "imitation $CANARY")) }
            contexts.put("level", mapOf("phase" to "pull"))
            addBreadcrumb(crumb)
        }
        val sent = sentEvent(event)
        assertNoCanary(render(sent))
        val names = (1..levelImitations.size).map(::placeholder).toSet() + "level"
        assertEquals(names, sent.tags!!.keys)
        assertEquals("error", sent.tags!!["level"])
        assertEquals(names, sent.breadcrumbs!!.single().data.keys)
        assertEquals("info", sent.breadcrumbs!!.single().data["level"])
        assertEquals(names, sent.contexts.keys().toList().toSet())
        assertEquals(mapOf("phase" to "pull"), sent.contexts["level"])
    }

    @Test
    fun replacedKeysRedactEveryValueShape() {
        val entries = linkedMapOf<Any?, Any?>(
            "a\tb" to "text $CANARY",
            "c\td" to mapOf("phase" to "pull $CANARY"),
            "e\tf" to listOf("pull $CANARY"),
            "g\th" to 7,
            "i\tj" to null,
            "to\tken" to "secret $CANARY",
        )
        assertEquals((1..6).map { redactedEntry(it) }, scrubbedNested(entries))
    }

    @Test
    fun staticDiagnosticNamesKeepExactNamesAndValues() {
        val entries = keptStaticNames.associateWith { "pull" }
        assertEquals(entries.toList(), scrubbedNested(entries))
    }

    @Test
    fun sensitiveStaticNamesKeepNamesButRedactValues() {
        val entries = filteredStaticNames.associateWith { "pull $CANARY" }
        assertEquals(filteredStaticNames.map { it to REDACTED }, scrubbedNested(entries))
    }

    @Test
    fun keyShapeBoundariesMatchDesktopPolicy() {
        val mismatches = keyShapeTable.filter { (key, kept) ->
            val expected = if (kept) listOf(key to "pull") else listOf(redactedEntry(1))
            scrubbedNested(mapOf(key to "pull")) != expected
        }
        assertEquals(emptyList<Pair<String, Boolean>>(), mismatches)
    }

    @Test
    fun digitKeysFollowTheDesktopDigitRule() {
        // Shared with desktop `test_digit_key_policy_on_the_final_event`.
        // Limits: a short all-digit key, such as a PIN used as a key, is kept.
        val kept = listOf("a12", "", "0", "404", "12345")
        val filtered = listOf("123456", "0123456789", "12a", "1.2", "1-2", "1_2", "12 3", "+12")
        val entries = (kept + filtered).associateWith { "pull" }
        val placeholders = filtered.indices.map { redactedEntry(it + 1) }
        val crumb = Breadcrumb().apply { category = "sync"; entries.forEach { (key, value) -> setData(key, value) } }
        val options = hookedOptions()
        val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
        val event = SentryEvent().apply {
            extras = entries
            tags = entries
            addBreadcrumb(hooked)
        }
        val sent = checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))
        // Event and breadcrumb maps are hash-ordered, so every placeholder
        // carries the same redacted value and the maps compare by content.
        val expected = kept.associateWith { "pull" } + placeholders
        assertEquals(expected, hooked.data)
        assertEquals(expected, sent.extras)
        assertEquals(expected, sent.tags)
        assertEquals(expected, sent.breadcrumbs!!.single().data)
        val rendered = render(sent)
        assertFalse(rendered, rendered.contains("\"123456\"") || rendered.contains("\"0123456789\""))
    }

    @Test
    fun queryMapKeyCanariesNeverReachTheFinalEvent() {
        // Shared with desktop `test_query_key_canaries_never_reach_the_final_event`.
        // Android queries are String-typed in the SDK; a map form is app-written.
        listOf("Canary\tx" to "canary", "person%40example.test" to "person").forEach { (key, word) ->
            val query = linkedMapOf(key to "1", "next" to "1")
            val options = hookedOptions()
            val crumb = Breadcrumb().apply { category = "httplib"; setData("http.query", query) }
            val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
            val event = SentryEvent().apply {
                extras = mapOf("http.query" to query)
                addBreadcrumb(hooked)
            }
            val sent = checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))
            val expected = mapOf(redactedEntry(1), "next" to "1")
            assertEquals(expected, sent.extras!!["http.query"])
            assertEquals(expected, sent.breadcrumbs!!.single().data["http.query"])
            val rendered = render(sent)
            assertFalse(rendered, rendered.lowercase().contains(word))
        }
    }

    @Test
    fun queryPairCollisionsNumberPerList() {
        // Shared with desktop `test_query_pair_collisions_number_per_list`.
        val query = listOf(
            listOf("a\tb", "1"), listOf("a b", "2"), listOf("next", "1"), listOf("a%09b", "3"), listOf("odd"),
        )
        val parts = listOf("http.query", "http.fragment", "query_string")
        val crumb = Breadcrumb().apply { category = "http"; parts.forEach { setData(it, query) } }
        val sent = sentEvent(SentryEvent().apply { extras = parts.associateWith { query }; addBreadcrumb(crumb) })
        val expected = listOf(
            listOf(placeholder(1), REDACTED), listOf(placeholder(2), REDACTED), listOf("next", "1"),
            listOf(placeholder(3), REDACTED), REDACTED,
        )
        assertEquals(parts.associateWith { expected }, sent.extras)
        assertEquals(parts.associateWith { expected }, sent.breadcrumbs!!.single().data.toMap())
    }

    @Test
    fun queryPairCanariesNeverReachTheFinalEvent() {
        // Shared with desktop `test_query_key_canaries_never_reach_the_final_event`.
        listOf("Canary\tx" to "canary", "person%40example.test" to "person").forEach { (key, word) ->
            queryPairForms(key, "1").forEach { query ->
                val crumb = Breadcrumb().apply { category = "http"; setData("http.query", query) }
                val sent = sentEvent(
                    SentryEvent().apply { extras = mapOf("http.query" to query); addBreadcrumb(crumb) },
                )
                assertEquals(filteredFirstPair, sent.extras!!["http.query"])
                assertEquals(filteredFirstPair, sent.breadcrumbs!!.single().data["http.query"])
                val rendered = render(sent)
                assertFalse(rendered, rendered.lowercase().contains(word))
            }
        }
    }

    @Test
    fun queryEntryShapesFailClosed() {
        val query = listOf(
            "token", "secret $CANARY", listOf("a", "b", "c $CANARY"), listOf("only $CANARY"),
            mapOf("token" to "map $CANARY"), Pair("next", "pair $CANARY"),
            listOf(1, "number $CANARY"), listOf(null, "null $CANARY"), listOf("token", "secret $CANARY"),
        )
        val sent = sentEvent(SentryEvent().apply { extras = mapOf("http.query" to query) })
        assertNoCanary(render(sent))
        val expected = listOf(
            REDACTED, REDACTED, REDACTED, REDACTED, REDACTED, REDACTED,
            listOf(placeholder(1), REDACTED), listOf(placeholder(2), REDACTED), listOf("token", REDACTED),
        )
        assertEquals(expected, sent.extras!!["http.query"])
    }

    @Test
    fun safeQueryPairsKeepNamesAndValues() {
        val query = listOf(listOf("page", "2"), arrayOf<Any?>("next", 1), listOf("sort", null))
        val crumb = Breadcrumb().apply { category = "http"; setData("http.query", query) }
        val sent = sentEvent(SentryEvent().apply { addBreadcrumb(crumb) })
        assertFalse(render(sent).contains("[Filtered"))
        val expected = listOf(listOf("page", "2"), listOf("next", 1), listOf("sort", null))
        assertEquals(expected, sent.breadcrumbs!!.single().data["http.query"])
    }

    @Test
    fun mixedMapKeepsNeighborsInOrder() {
        val scrubbed = scrubbedNested(
            linkedMapOf(
                "phase" to "pull",
                "$CANARY@example.test" to "value $CANARY",
                "attempt" to 2,
                "$CANARY\tnote" to "value $CANARY",
                "detail" to "disk unavailable",
            ),
        )
        val expected = listOf(
            "phase" to "pull", redactedEntry(1), "attempt" to 2, redactedEntry(2), "detail" to "disk unavailable",
        )
        assertEquals(expected, scrubbed)
    }

    @Test
    fun exactStaticEventKeepsEveryNameAndValue() {
        val sent = sentEvent(staticControlEvent())
        assertFalse(render(sent).contains("[REDACTED"))
        assertFalse(render(sent).contains("[Filtered"))
        assertEquals(mapOf("component" to "sync", "release.channel" to "beta"), sent.tags)
        val attempts = listOf(mapOf("phase" to "pull", "attempt" to 2))
        assertEquals(mapOf("retryCount" to 3, "attempts" to attempts), sent.extras)
        assertEquals(mapOf("phase" to "pull"), sent.contexts["sync"])
        assertEquals("ui.load", sent.contexts.trace!!.operation)
        assertEquals("Pixel 8", sent.contexts.device!!.model)
        assertEquals(mapOf("battery_temperature" to 31), sent.contexts.device!!.unknown)
        assertEquals(mapOf("start_type" to "cold"), sent.contexts.app!!.unknown)
        assertEquals(mapOf("rooted" to false), sent.contexts.operatingSystem!!.unknown)
        val crumbData = mapOf(
            "view.id" to "start_button", "view.class" to "android.widget.Button",
            "http.method" to "GET", "status_code" to 200,
        )
        assertEquals(crumbData, sent.breadcrumbs!!.single().data)
        val trace = sent.exceptions!!.single().stacktrace!!
        assertEquals(mapOf("attempt" to 2), trace.frames!!.single().vars)
        assertEquals(mapOf("pc" to "0x1f"), trace.registers)
        assertEquals(mapOf("code" to 7), sent.exceptions!!.single().mechanism!!.data)
        assertEquals(mapOf("Accept" to "application/json", "User-Agent" to "Pomodorough/0.54"), sent.request!!.headers)
        assertEquals(mapOf("LANG" to "C"), sent.request!!.envs)
        assertEquals(mapOf("mode" to "fast"), sent.request!!.others)
    }

    private fun staticControlEvent(): SentryEvent = eventWithFrame(
        { function = "pull"; vars = mapOf("attempt" to 2) },
        { registers = mapOf("pc" to "0x1f") },
    ).apply {
        exceptions!!.single().mechanism = Mechanism().apply { type = "generic"; data = mapOf("code" to 7) }
        tags = mapOf("component" to "sync", "release.channel" to "beta")
        extras = mapOf("retryCount" to 3, "attempts" to listOf(mapOf("phase" to "pull", "attempt" to 2)))
        contexts.put("sync", mapOf("phase" to "pull"))
        contexts.setTrace(SpanContext("ui.load"))
        contexts.setDevice(Device().apply { model = "Pixel 8"; unknown = mapOf("battery_temperature" to 31) })
        contexts.setApp(App().apply { appName = "Pomodorough"; unknown = mapOf("start_type" to "cold") })
        contexts.setOperatingSystem(OperatingSystem().apply { name = "Android"; unknown = mapOf("rooted" to false) })
        addBreadcrumb(
            Breadcrumb().apply {
                category = "ui.click"
                setData("view.id", "start_button")
                setData("view.class", "android.widget.Button")
                setData("http.method", "GET")
                setData("status_code", 200)
            },
        )
        request = Request().apply {
            headers = mapOf("Accept" to "application/json", "User-Agent" to "Pomodorough/0.54")
            envs = mapOf("LANG" to "C")
            others = mapOf("mode" to "fast")
        }
    }

    @Test
    fun depthLimitKeepsTwentyLevelsAndRedactsDeeper() {
        val chains = mapOf("kept" to chain(20), "cut" to chain(21), "deep" to chain(500))
        val sent = sentEvent(SentryEvent().apply { extras = chains })
        render(sent)
        assertEquals("pull", chainLeaf(sent.extras!!["kept"]))
        assertEquals(REDACTED, chainLeaf(sent.extras!!["cut"]))
        assertEquals(REDACTED, chainLeaf(sent.extras!!["deep"]))
    }

    @Test
    fun cyclicValuesAreRedactedWhereTheyClose() {
        val cyclic = LinkedHashMap<String, Any>()
        cyclic["phase"] = "pull"
        cyclic["self"] = cyclic
        val loop = ArrayList<Any>()
        loop.add("pull")
        loop.add(loop)
        val sent = sentEvent(SentryEvent().apply { extras = mapOf("cyclic" to cyclic, "loop" to loop) })
        render(sent)
        assertEquals(listOf("phase" to "pull", "self" to REDACTED), (sent.extras!!["cyclic"] as Map<*, *>).toList())
        assertEquals(listOf("pull", REDACTED), sent.extras!!["loop"])
    }

    @Test
    fun opaqueObjectValuesAreRedactedBeforeReflection() {
        val holder = DiagnosticHolder(mapOf("$CANARY@example.test" to "value"))
        val sent = sentEvent(SentryEvent().apply { extras = mapOf("holder" to holder, "retryCount" to 3) })
        assertNoCanary(render(sent))
        assertEquals(mapOf("holder" to REDACTED, "retryCount" to 3), sent.extras)
    }

    @Test
    fun enumValuesUseTheirConstantName() {
        val sent = sentEvent(SentryEvent().apply { extras = mapOf("mode" to DisplayedEnum.PLAIN) })
        assertNoCanary(render(sent))
        assertEquals(mapOf("mode" to "PLAIN"), sent.extras)
    }

    @Test
    fun sdkFailureMessageKeyIsReplaced() {
        val crumb = Breadcrumb().apply {
            category = "sync"
            setData("sentry:message", "hook failed for $CANARY")
            setData("phase", "pull")
        }
        val sent = sentEvent(SentryEvent().apply { addBreadcrumb(crumb) })
        assertNoCanary(render(sent))
        assertEquals(mapOf(redactedEntry(1), "phase" to "pull"), sent.breadcrumbs!!.single().data)
    }

    // The key policy rewrites `User.data` entries but never its shape: the SDK
    // writes "data" only when the map is non-null, so null and empty differ.
    @Test
    fun absentUserDataStaysAbsent() {
        val sent = sentEvent(SentryEvent().apply { user = User().apply { id = "user-7" } })
        val rendering = renderUser(sent.user!!)
        assertNull(sent.user!!.data)
        assertFalse(rendering, rendering.contains("\"data\""))
    }

    @Test
    fun emptyUserDataStaysEmpty() {
        val sent = sentEvent(SentryEvent().apply { user = User().apply { data = emptyMap() } })
        val rendering = renderUser(sent.user!!)
        assertEquals(emptyMap<String, String>(), sent.user!!.data)
        assertTrue(rendering, rendering.contains("\"data\":{}"))
    }
}
