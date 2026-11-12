package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// R54-L06: query text privacy, mirroring the desktop R54-L02 query rule.
// Synthetic canaries only. Breadcrumbs pass the production beforeBreadcrumb
// hook, events pass beforeSend, and the SDK JSON serializer renders the
// result, without SDK initialization or network.

private const val CANARY = "QueryCanaryWren"
private const val REDACTED = SentryScrubber.REDACTED
private const val TOKEN = SentryScrubber.REDACTED_TOKEN
private val queryParts = listOf("http.query", "http.fragment", "query_string")

/** One query text; [secret] is the text that must never reach the final event, null for a control. */
private data class QueryText(val query: String, val expected: String, val secret: String?)

private val queryTexts: Map<String, QueryText> = linkedMapOf(
    "tab_key" to QueryText("$CANARY\tx=1&next=1", "$REDACTED&next=1", CANARY),
    "encoded_tab_key" to QueryText("$CANARY%09x=1&next=1", "$REDACTED&next=1", CANARY),
    "encoded_newline_key" to QueryText("$CANARY%0Ax=1&next=1", "$REDACTED&next=1", CANARY),
    "control_key" to QueryText("$CANARY\u0007x=1&next=1", "$REDACTED&next=1", CANARY),
    "quote_key" to QueryText("$CANARY\"x=1&next=1", "$REDACTED&next=1", CANARY),
    "bracket_key" to QueryText("$CANARY[x]=1&next=1", "$REDACTED&next=1", CANARY),
    "non_ascii_key" to QueryText("${CANARY}ж=1&next=1", "$REDACTED&next=1", CANARY),
    "encoded_non_ascii_key" to QueryText("$CANARY%C3%A9=1&next=1", "$REDACTED&next=1", CANARY),
    "encoded_email_key" to QueryText("$CANARY%40example.test=1&next=1", "$REDACTED&next=1", CANARY),
    "encoded_email_value" to QueryText(
        "next=$CANARY%40example.test&mode=fast", "next=${SentryScrubber.REDACTED_EMAIL}&mode=fast", CANARY,
    ),
    "encoded_secret_key" to QueryText("%74oken=$CANARY&next=1", "%74oken=$TOKEN&next=1", CANARY),
    "leading_secret_key" to QueryText("token=$CANARY&next=1", "token=$TOKEN&next=1", CANARY),
    // The non-canonical rule wins over the secret-key rule, which keeps the raw key.
    "sensitive_noncanonical_key" to QueryText("token$CANARY\t=1&next=1", "$REDACTED&next=1", CANARY),
    // A key that free text rewrites is a private name; redacting the whole
    // parameter keeps the breadcrumb stable across both hooks.
    "encoded_invite_key" to QueryText("pomodorough%31x=1&next=1", "$REDACTED&next=1", "pomodorough"),
    // Controls keep the text unchanged.
    "plain_pairs" to QueryText("mode=fast&next=1", "mode=fast&next=1", null),
    "canonical_charset_key" to QueryText("sort.order-by_x~y=asc", "sort.order-by_x~y=asc", null),
    "digit_key" to QueryText("v123456=1&next=1", "v123456=1&next=1", null),
    "valueless_route" to QueryText("/settings", "/settings", null),
    // Desktop keeps the empty key in query text too.
    "empty_key" to QueryText("=v&next=1", "=v&next=1", null),
    // Android decodes before scrubbing, so a safe encoded value survives.
    "encoded_safe_value" to QueryText("next=%2Fhome&mode=fast", "next=%2Fhome&mode=fast", null),
)

private fun hookedOptions(): SentryOptions =
    SentryOptions().also { CrashReportingInit.configure(it, "https://key@sentry.invalid/1") }

private fun render(event: SentryEvent): String {
    val writer = StringWriter()
    JsonSerializer(SentryOptions()).serialize(event, writer)
    return writer.toString()
}

private fun assertNever(secret: String?, rendering: String) {
    if (secret == null) return
    assertFalse(rendering.take(600), rendering.lowercase().contains(secret.lowercase()))
}

/** Breadcrumb [data] after beforeBreadcrumb, and the event after beforeSend. */
private fun hookedBreadcrumb(options: SentryOptions, data: Map<String, Any>): Breadcrumb {
    val crumb = Breadcrumb().apply { category = "http"; data.forEach { (key, value) -> setData(key, value) } }
    return checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
}

private fun sentEvent(options: SentryOptions, event: SentryEvent): SentryEvent =
    checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))

@RunWith(Parameterized::class)
class R54L06QueryTextPrivacyTest(private val rowName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = queryTexts.keys.map { arrayOf<Any>(it) }
    }

    private val row = queryTexts.getValue(rowName)

    @Test
    fun breadcrumbAndExtrasQueryPartsFollowTheRequestRule() {
        val options = hookedOptions()
        val parts = queryParts.associateWith { row.query }
        val hooked = hookedBreadcrumb(options, parts)
        val expected = queryParts.associateWith { row.expected }
        assertEquals(expected, hooked.data.toMap())
        val sent = sentEvent(options, SentryEvent().apply { extras = parts; addBreadcrumb(hooked) })
        assertEquals(expected, sent.extras)
        assertEquals(expected, sent.breadcrumbs!!.single().data.toMap())
        assertNever(row.secret, render(sent))
    }

    @Test
    fun requestQueryFieldsFollowTheSameRule() {
        val request = Request().apply {
            url = "https://example.invalid/cb?${row.query}"
            queryString = row.query
            fragment = row.query
        }
        val sent = sentEvent(hookedOptions(), SentryEvent().apply { this.request = request })
        assertEquals("https://example.invalid/cb?${row.expected}", sent.request!!.url)
        assertEquals(row.expected, sent.request!!.queryString)
        assertEquals(row.expected, sent.request!!.fragment)
        assertNever(row.secret, render(sent))
    }
}

class R54L06QueryEntryPrivacyTest {
    // Shared with desktop `_scrub_query_parameter_value`: the empty key is a
    // diagnostic name, but only a canonical key names its query value.
    @Test
    fun emptyQueryKeyRedactsItsValueInEveryForm() {
        val forms: List<Pair<Any, Any>> = listOf(
            linkedMapOf("" to "$CANARY map", "next" to "1") to mapOf("" to REDACTED, "next" to "1"),
            listOf(listOf("", "$CANARY list"), listOf("next", "1")) to
                listOf(listOf("", REDACTED), listOf("next", "1")),
            arrayOf(arrayOf("", "$CANARY array"), arrayOf<Any?>("next", "1")) to
                listOf(listOf("", REDACTED), listOf("next", "1")),
            // A list, map or pair-list value is not text, but its key still decides.
            mapOf("" to listOf("$CANARY list")) to mapOf("" to REDACTED),
            mapOf("" to mapOf("x" to "$CANARY map")) to mapOf("" to REDACTED),
            listOf(listOf("", listOf("$CANARY pair")), listOf("next", "1")) to
                listOf(listOf("", REDACTED), listOf("next", "1")),
        )
        forms.forEach { (query, expected) ->
            val options = hookedOptions()
            val parts = queryParts.associateWith { query }
            val hooked = hookedBreadcrumb(options, parts)
            val sent = sentEvent(options, SentryEvent().apply { extras = parts; addBreadcrumb(hooked) })
            assertEquals(queryParts.associateWith { expected }, sent.extras)
            assertEquals(queryParts.associateWith { expected }, sent.breadcrumbs!!.single().data.toMap())
            assertNever(CANARY, render(sent))
        }
    }

    @Test
    fun otherKeysKeepTheTextAndMapRules() {
        val options = hookedOptions()
        val detail = "pull failed for next=1 now"
        val context = linkedMapOf("" to "pull", "next" to "1")
        val hooked = hookedBreadcrumb(options, mapOf("detail" to detail, "context" to context))
        val sent = sentEvent(
            options,
            SentryEvent().apply { extras = mapOf("detail" to detail, "context" to context); addBreadcrumb(hooked) },
        )
        val expected = mapOf("detail" to detail, "context" to context)
        assertEquals(expected, sent.extras)
        assertEquals(expected, sent.breadcrumbs!!.single().data.toMap())
    }
}
