package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// R54-L11: the desktop `_sensitive_key` rule for structured map keys, request
// queries, and free-text query and JSON keys. Synthetic canaries only.
// Breadcrumbs pass the production beforeBreadcrumb hook, events pass
// beforeSend, and the SDK JSON serializer renders the result, without SDK
// initialization or network. Expected results match desktop, with
// `[REDACTED]` for `[Filtered]`, unless a row says otherwise.

private const val CANARY = "KeyCanaryLark"
private const val REDACTED = SentryScrubber.REDACTED
private const val TOKEN = SentryScrubber.REDACTED_TOKEN

/** Where a key name hides its value: in maps and queries, in queries only, or nowhere. */
private enum class KeyRule(val mapFiltered: Boolean, val queryFiltered: Boolean) {
    FILTERED(true, true),
    QUERY_ONLY(false, true),
    KEPT(false, false),
}

private val keyRules: Map<String, KeyRule> = linkedMapOf(
    "verificationCode" to KeyRule.FILTERED,
    "exit_code" to KeyRule.FILTERED,
    "clientKey" to KeyRule.FILTERED,
    "signingKey" to KeyRule.FILTERED,
    // Desktop also filters `publicKey`, fail-closed.
    "publicKey" to KeyRule.FILTERED,
    "key" to KeyRule.FILTERED,
    "keys" to KeyRule.FILTERED,
    "X-Api-Key" to KeyRule.FILTERED,
    "Signature" to KeyRule.FILTERED,
    "X-Amz-Signature" to KeyRule.FILTERED,
    "sig" to KeyRule.FILTERED,
    "value" to KeyRule.FILTERED,
    "accessKeys" to KeyRule.FILTERED,
    "uploadSignatures" to KeyRule.FILTERED,
    "X-Goog-Signatures" to KeyRule.FILTERED,
    "pairing-code" to KeyRule.FILTERED,
    // Android only: map `code` keeps mechanism `code: 7` diagnostics (A30).
    // Desktop filters it everywhere.
    "code" to KeyRule.QUERY_ONLY,
    // Upper-case `Code` follows the same rule.
    "Code" to KeyRule.QUERY_ONLY,
    "errorCode" to KeyRule.KEPT,
    "statusCode" to KeyRule.KEPT,
    "exitCode" to KeyRule.KEPT,
    "encode" to KeyRule.KEPT,
    "base64decode" to KeyRule.KEPT,
    "decoder" to KeyRule.KEPT,
    "codec" to KeyRule.KEPT,
    "monkey" to KeyRule.KEPT,
    "hockeys" to KeyRule.KEPT,
    "keyboard" to KeyRule.KEPT,
    "signal" to KeyRule.KEPT,
    "design" to KeyRule.KEPT,
    "signer" to KeyRule.KEPT,
    "values" to KeyRule.KEPT,
    // Android only: R54L03KeyPrivacyTest keeps the SDK HTTP `status_code`,
    // and A43 keeps `statement`/`stateFlow`. Desktop filters all three.
    "status_code" to KeyRule.KEPT,
    "statement" to KeyRule.KEPT,
    "stateFlow" to KeyRule.KEPT,
)

/** One free text; [secret] is the text that must never reach the final event, null for a control. */
private data class KeyRuleText(val text: String, val expected: String, val secret: String?)

private val freeTexts: Map<String, KeyRuleText> = linkedMapOf(
    "query_client_token" to KeyRuleText("cb?clientToken=$CANARY&next=1", "cb?clientToken=$TOKEN&next=1", CANARY),
    "fragment_credential" to KeyRuleText("cb#myCredential=$CANARY&next=1", "cb#myCredential=$TOKEN&next=1", CANARY),
    // Android only: the value ends at `;`, as for every query value. Desktop
    // also redacts `;mode=1`.
    "query_room_secret" to KeyRuleText("cb?roomSecret=$CANARY;mode=1", "cb?roomSecret=$TOKEN;mode=1", CANARY),
    // Android only: free text takes the map key rule, which names `email`.
    // Desktop keeps the value.
    "query_email_key" to KeyRuleText("cb?email=$CANARY&next=1", "cb?email=$TOKEN&next=1", CANARY),
    "query_sig_after_version" to KeyRuleText("cb?sv=2024&sig=$CANARY&next=1", "cb?sv=2024&sig=$TOKEN&next=1", CANARY),
    "query_exit_code_kebab" to KeyRuleText("cb?exit-code=$CANARY&next=1", "cb?exit-code=$TOKEN&next=1", CANARY),
    "single_room_secret" to KeyRuleText("{'roomSecret': '$CANARY'}", "{'roomSecret': '$TOKEN'}", CANARY),
    "json_back_to_back" to KeyRuleText(
        "{\"token\":\"a\"password\":\"$CANARY\"}", "{\"token\":\"$TOKEN\"password\":\"$TOKEN\"}", CANARY,
    ),
    "json_signing_key" to KeyRuleText("{\"signingKey\":\"$CANARY\"}", "{\"signingKey\":\"$TOKEN\"}", CANARY),
    // Android only: desktop JSON text keeps the values of compound keys and
    // of exact `code`.
    "json_client_token" to KeyRuleText(
        "{\"clientToken\":\"$CANARY\",\"ok\":true}", "{\"clientToken\":\"$TOKEN\",\"ok\":true}", CANARY,
    ),
    "json_spaced_credential" to KeyRuleText(
        "{\"myCredential\" : \"$CANARY\"}", "{\"myCredential\" : \"$TOKEN\"}", CANARY,
    ),
    "json_code" to KeyRuleText("{\"code\":\"$CANARY\"}", "{\"code\":\"$TOKEN\"}", CANARY),
    // Android only: desktop JSON text also keeps plural `*keys` and
    // upper-case `CODE`.
    "json_signing_keys" to KeyRuleText("{\"signingKeys\":\"$CANARY\"}", "{\"signingKeys\":\"$TOKEN\"}", CANARY),
    "json_upper_code" to KeyRuleText("{\"CODE\":\"$CANARY\"}", "{\"CODE\":\"$TOKEN\"}", CANARY),
    "query_dotted_token" to KeyRuleText("cb?client.token=$CANARY&next=1", "cb?client.token=$TOKEN&next=1", CANARY),
    "query_tilde_token" to KeyRuleText("cb?client~token=$CANARY&next=1", "cb?client~token=$TOKEN&next=1", CANARY),
    // A declined JSON key is retried one character later, so a compound key
    // that follows a value with no separator is still read.
    "json_value_then_signing_key" to KeyRuleText(
        "{\"a\":\"signingKey\":\"$CANARY\"}", "{\"a\":\"signingKey\":\"$TOKEN\"}", CANARY,
    ),
    // Android only: a key of a listed name hides its value first, and a
    // compound key then hides the rest. Desktop keeps `token`.
    "json_compound_key_holds_pair" to KeyRuleText(
        "{\"clientToken\":\"token\":\"$CANARY\"}", "{\"clientToken\":\"$TOKEN\":\"$TOKEN\"}", CANARY,
    ),
    // Both quote styles in one text. A value runs to the closing quote of
    // its own style, and a pair of one style inside a key of the other style
    // is read as its own pair. Android only: desktop ends a value at a quote
    // of either style and keeps the rest, such as `[Filtered]"key": "x'`.
    "mixed_single_value_holds_json_pair" to KeyRuleText(
        "{'token': '$CANARY\"key\": \"x', 'n': \"1\"}", "{'token': '$TOKEN', 'n': \"1\"}", CANARY,
    ),
    "mixed_double_key_holds_single_pair" to KeyRuleText(
        "\"'sid':'$CANARY\":\"'\"", "\"'sid':'$TOKEN'\"", CANARY,
    ),
    "mixed_double_value_holds_single_pair" to KeyRuleText(
        "{\"token\": \"$CANARY'key': 'x\", \"n\": '1'}", "{\"token\": \"$TOKEN\", \"n\": '1'}", CANARY,
    ),
    "mixed_single_key_holds_double_pair" to KeyRuleText(
        "'\"sid\":\"$CANARY':'\"'", "'\"sid\":\"$TOKEN\"'", CANARY,
    ),
    "mixed_double_value_holds_single_secret_pair" to KeyRuleText(
        "{\"token\": \"$CANARY'sid': 'x\", \"n\": '1'}", "{\"token\": \"$TOKEN\", \"n\": '1'}", CANARY,
    ),
    "mixed_single_compound_value_holds_json_pair" to KeyRuleText(
        "{'signingKey': '$CANARY\"key\": \"x', 'n': \"1\"}", "{'signingKey': '$TOKEN', 'n': \"1\"}", CANARY,
    ),
    "mixed_double_key_holds_single_compound_pair" to KeyRuleText(
        "\"'signingKey':'$CANARY\":\"'\"", "\"'signingKey':'$TOKEN'\"", CANARY,
    ),
    "mixed_double_compound_value_holds_single_pair" to KeyRuleText(
        "{\"signingKey\": \"$CANARY'key': 'x\", \"n\": '1'}", "{\"signingKey\": \"$TOKEN\", \"n\": '1'}", CANARY,
    ),
    "mixed_single_key_holds_double_compound_pair" to KeyRuleText(
        "'\"signingKey\":\"$CANARY':'\"'", "'\"signingKey\":\"$TOKEN\"'", CANARY,
    ),
    // Android only: desktop also hides the `v` after the outer key.
    "mixed_double_key_wraps_single_pair" to KeyRuleText(
        "\"'signingKey':'$CANARY', 'x':'token\":\"v\"", "\"'signingKey':'$TOKEN', 'x':'token\":\"v\"", CANARY,
    ),
    "mixed_single_key_wraps_double_pair" to KeyRuleText(
        "'\"signingKey\":\"$CANARY\", \"x\":\"token':'v'", "'\"signingKey\":\"$TOKEN\", \"x\":\"token':'v'", CANARY,
    ),
    // A compound-key value that holds a listed pair of the other style
    // keeps that pair's redaction. Android only: desktop shows the canary.
    "mixed_compound_double_value_meets_single_pair" to KeyRuleText(
        "\"signingKey\": \"see 'token': '\" $CANARY'", "\"signingKey\": \"see 'token': '$TOKEN'", CANARY,
    ),
    "mixed_compound_single_value_meets_double_pair" to KeyRuleText(
        "{'signingKey': 'see\"cookie\": \"x'$CANARY\"}", "{'signingKey': 'see\"cookie\": \"$TOKEN\"}", CANARY,
    ),
    // The JSON rules run before the email rule, so a listed key that holds
    // an address still hides its value. Android only: desktop keeps the canary.
    "json_key_holds_email" to KeyRuleText(
        "{\"token@ex.io\": \"$CANARY\"}", "{\"${SentryScrubber.REDACTED_EMAIL}\": \"$TOKEN\"}", CANARY,
    ),
    // A redacted value can hold the first key of the other quote style. That
    // key is searched again after the redaction, so a later pair of that
    // style still hides its value. Android only: desktop keeps the first canary.
    "mixed_value_holds_other_style_key_start" to KeyRuleText(
        "{\"uploadToken\": \"$CANARY 'x':'y'\", 'signingKey': '$CANARY'}",
        "{\"uploadToken\": \"$TOKEN\", 'signingKey': '$TOKEN'}",
        CANARY,
    ),
    // A quoted key holds no quote of either style, also after its first
    // character, so a pair of the other style inside it is read as its own
    // pair. Android only: desktop keeps the canary.
    "mixed_double_key_holds_single_pair_after_first_char" to KeyRuleText(
        "\"x'clientToken':'$CANARY\":\"'\"", "\"x'clientToken':'$TOKEN'\"", CANARY,
    ),
    "mixed_single_key_holds_double_pair_after_first_char" to KeyRuleText(
        "'x\"clientToken\":\"$CANARY':'\"'", "'x\"clientToken\":\"$TOKEN\"'", CANARY,
    ),
    // Controls keep the text unchanged.
    "codec_names" to KeyRuleText("cb?encode=utf8&decoder=x&codec=h264", "cb?encode=utf8&decoder=x&codec=h264", null),
    "key_lookalikes" to KeyRuleText("cb?monkey=1&hockeys=2&keyboard=3", "cb?monkey=1&hockeys=2&keyboard=3", null),
    "sig_lookalikes" to KeyRuleText("cb?signal=4&design=5&signer=6", "cb?signal=4&design=5&signer=6", null),
    "error_code" to KeyRuleText("cb?errorCode=E42&next=1", "cb?errorCode=E42&next=1", null),
    "decode_lookalike" to KeyRuleText(
        "cb?base64decode=ok&next=1 {\"base64decode\":\"ok\"}",
        "cb?base64decode=ok&next=1 {\"base64decode\":\"ok\"}",
        null,
    ),
    "room_prose" to KeyRuleText("room=5 joined", "room=5 joined", null),
    "json_status_code" to KeyRuleText("{\"status_code\":\"200\"}", "{\"status_code\":\"200\"}", null),
    "json_statement" to KeyRuleText(
        "{\"statement\":\"ok\",\"stateFlow\":\"on\",\"errorcode\":\"5\"}",
        "{\"statement\":\"ok\",\"stateFlow\":\"on\",\"errorcode\":\"5\"}",
        null,
    ),
    // Android only: desktop filters `status_code`, `statement`, and `stateFlow`.
    "query_status_code" to KeyRuleText("cb?status_code=200&next=1", "cb?status_code=200&next=1", null),
    "query_statement" to KeyRuleText("cb?statement=ok&stateFlow=on", "cb?statement=ok&stateFlow=on", null),
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

private fun sentEvent(options: SentryOptions, event: SentryEvent): SentryEvent =
    checkNotNull(checkNotNull(options.beforeSend).execute(event, Hint()))

@RunWith(Parameterized::class)
class R54L11StructuredKeyRuleTest(private val key: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun keys(): List<Array<Any>> = keyRules.keys.map { arrayOf<Any>(it) }
    }

    private val rule = keyRules.getValue(key)
    private val mapValue = if (rule.mapFiltered) REDACTED else CANARY
    private val query = "$key=$CANARY&next=1"
    private val scrubbedQuery = if (rule.queryFiltered) "$key=$TOKEN&next=1" else query
    private val secret = CANARY.takeIf { rule == KeyRule.FILTERED }

    // The breadcrumb passes beforeBreadcrumb, then beforeSend again inside
    // the event, so the second pass must leave the first result alone.
    @Test
    fun breadcrumbDataQueryAndMessageFollowTheKeyRule() {
        val options = hookedOptions()
        val crumb = Breadcrumb().apply {
            message = "cb?$query"
            setData(key, CANARY)
            setData("http.query", query)
        }
        val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
        val expectedData = mapOf(key to mapValue, "http.query" to scrubbedQuery)
        assertEquals("cb?$scrubbedQuery", hooked.message)
        assertEquals(expectedData, hooked.data.toMap())
        val sent = sentEvent(options, SentryEvent().apply { addBreadcrumb(hooked) })
        val sentCrumb = sent.breadcrumbs!!.single()
        assertEquals("cb?$scrubbedQuery", sentCrumb.message)
        assertEquals(expectedData, sentCrumb.data.toMap())
        assertNever(secret, render(sent))
    }

    @Test
    fun eventMapsRequestAndMessageFollowTheKeyRule() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = "cb?$query" }
            extras = mapOf(key to CANARY, "sync" to mapOf(key to CANARY))
            tags = mapOf(key to CANARY)
            request = Request().apply {
                queryString = query
                headers = mapOf(key to CANARY, "Accept" to "application/json")
            }
        }
        val sent = sentEvent(hookedOptions(), event)
        assertEquals("cb?$scrubbedQuery", sent.message!!.formatted)
        assertEquals(mapOf(key to mapValue, "sync" to mapOf(key to mapValue)), sent.extras)
        assertEquals(mapOf(key to mapValue), sent.tags)
        assertEquals(scrubbedQuery, sent.request!!.queryString)
        assertEquals(mapOf(key to mapValue, "Accept" to "application/json"), sent.request!!.headers)
        assertNever(secret, render(sent))
    }
}

@RunWith(Parameterized::class)
class R54L11FreeTextKeyRuleTest(private val rowName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = freeTexts.keys.map { arrayOf<Any>(it) }
    }

    private val row = freeTexts.getValue(rowName)

    @Test
    fun breadcrumbMessageAndDataFollowTheKeyRule() {
        val options = hookedOptions()
        val crumb = Breadcrumb().apply {
            message = row.text
            setData("detail", row.text)
        }
        val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
        assertEquals(row.expected, hooked.message)
        assertEquals(mapOf("detail" to row.expected), hooked.data.toMap())
        val sent = sentEvent(options, SentryEvent().apply { addBreadcrumb(hooked) })
        val sentCrumb = sent.breadcrumbs!!.single()
        assertEquals(row.expected, sentCrumb.message)
        assertEquals(mapOf("detail" to row.expected), sentCrumb.data.toMap())
        assertNever(row.secret, render(sent))
    }

    @Test
    fun eventMessageAndExtrasFollowTheKeyRule() {
        val event = SentryEvent().apply {
            message = Message().apply { formatted = row.text }
            extras = mapOf("detail" to row.text)
        }
        val sent = sentEvent(hookedOptions(), event)
        assertEquals(row.expected, sent.message!!.formatted)
        assertEquals(mapOf("detail" to row.expected), sent.extras)
        assertNever(row.secret, render(sent))
    }
}
