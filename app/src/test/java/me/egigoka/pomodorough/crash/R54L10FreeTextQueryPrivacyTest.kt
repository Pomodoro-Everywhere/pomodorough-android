package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

// R54-L10: the R54-L02 query key rule inside free text, after `?`, `&`, `#`
// or `;`. Synthetic canaries only. Breadcrumbs pass the production
// beforeBreadcrumb hook, events pass beforeSend, and the SDK JSON serializer
// renders the result, without SDK initialization or network. Expected texts
// match desktop `_scrub_string`, with `[REDACTED]` for `[Filtered]`, unless
// a row says otherwise.

private const val CANARY = "QueryCanaryWren"
private const val REDACTED = SentryScrubber.REDACTED
private const val TOKEN = SentryScrubber.REDACTED_TOKEN

/** One free text; [secret] is the text that must never reach the final event, null for a control. */
private data class FreeText(val text: String, val expected: String, val secret: String?)

private val freeTexts: Map<String, FreeText> = linkedMapOf(
    "url_tab_key" to FreeText(
        "https://example.invalid/cb?$CANARY\tx=1&next=1", "https://example.invalid/cb?$REDACTED&next=1", CANARY,
    ),
    "hash_tab_key" to FreeText("pull failed #$CANARY\tx=1&next=1", "pull failed #$REDACTED&next=1", CANARY),
    "semicolon_tab_key" to FreeText("mode=1;$CANARY\tx=1", "mode=1;$REDACTED", CANARY),
    "ampersand_tab_key" to FreeText("cb?a=1&$CANARY\tx=1", "cb?a=1&$REDACTED", CANARY),
    "nested_question_key" to FreeText("cb?next=/home?$CANARY\tx=1", "cb?next=/home?$REDACTED", CANARY),
    "quote_key" to FreeText("cb?$CANARY\"x=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "bracket_key" to FreeText("cb?$CANARY[x]=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "non_ascii_key" to FreeText("cb?${CANARY}ж=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "space_key" to FreeText("cb?$CANARY x=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "plus_key" to FreeText("cb?$CANARY+x=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "empty_value" to FreeText("cb?$CANARY\tx=&next=1", "cb?$REDACTED&next=1", CANARY),
    // Free text never decodes, so any escape in a key redacts the parameter.
    "encoded_key" to FreeText("cb?$CANARY%09x=1&next=1", "cb?$REDACTED&next=1", CANARY),
    "encoded_secret_key" to FreeText("cb?%74oken=$CANARY&next=1", "cb?$REDACTED&next=1", CANARY),
    // Same as desktop: a spaced pair after `;` reads as a key with a space.
    "spaced_pair" to FreeText("sync failed; status=500", "sync failed;$REDACTED", null),
    // Android only: a key that a later rule rewrites redacts its whole
    // parameter, so both hooks agree. Desktop keeps `[Filtered]=1` for the
    // address and keeps this short invite-like key.
    "address_key" to FreeText("cb?10.0.0.1=1&next=1", "cb?$REDACTED&next=1", "10.0.0.1"),
    "invite_key" to FreeText("cb?pomodorough1abc=1&next=1", "cb?$REDACTED&next=1", "pomodorough1abc"),
    // Android only: the redacted parameter of a key that names no secret
    // ends at `#`, like any value. Desktop gives `cb?[Filtered]=1#keep&next=1`.
    "address_key_fragment" to FreeText("cb?10.0.0.1=1#keep&next=1", "cb?$REDACTED#keep&next=1", "10.0.0.1"),
    "secret_value" to FreeText("cb?token=$CANARY&next=1", "cb?token=$TOKEN&next=1", CANARY),
    // A secret value ends where the value rule ends it, so a parameter
    // inside it stays hidden whichever key or value rule runs first.
    "fragment_space_after_secret" to FreeText(
        "cb?token=QueryCanaryOne#x y=$CANARY", "cb?token=$TOKEN", "QueryCanary",
    ),
    // Android only: a JSON value runs to its closing quote, so a separator
    // inside it never starts a query key. Desktop ends the value at the
    // separator and drops the rest, and keeps the cookie value.
    "json_password_ampersand" to FreeText("{\"password\":\"p$CANARY&a b=1\"}", "{\"password\":\"$TOKEN\"}", CANARY),
    "json_cookie_semicolon" to FreeText("{\"cookie\":\"sid=$CANARY; theme=dark\"}", "{\"cookie\":\"$TOKEN\"}", CANARY),
    "json_token_later_equals" to FreeText(
        "{\"token\":\"$CANARY&x\",\"n\":\"a=1\"}", "{\"token\":\"$TOKEN\",\"n\":\"a=1\"}", CANARY,
    ),
    "json_single_password" to FreeText("{'password': 'p$CANARY&a b=1'}", "{'password': '$TOKEN'}", CANARY),
    "json_session_question" to FreeText("{\"session\":\"$CANARY?a b=1\"}", "{\"session\":\"$TOKEN\"}", CANARY),
    // A declined JSON key is retried one character later, so a key that
    // follows a value with no separator is still read.
    "json_value_then_key" to FreeText("{\"a\":\"token\":\"$CANARY\"}", "{\"a\":\"token\":\"$TOKEN\"}", CANARY),
    // Android only: a canonical secret key that a later rule rewrites
    // redacts its parameter and the value the value rule would take, even
    // past `#`. Desktop keeps the key: `cb?sync_worker_session_sid=[Filtered]`.
    "long_secret_key_fragment" to FreeText("cb?sync_worker_session_sid=one#$CANARY", "cb?$REDACTED", CANARY),
    "long_secret_key_bearer" to FreeText(
        "cb?sync_worker_session_sid=one#Bearer $CANARY", "cb?$REDACTED", CANARY,
    ),
    // Android only: a key outside the canonical charset redacts only up to
    // the next separator, and later rules read the rest. Desktop redacts to
    // the end: `cb?[Filtered]`.
    "spaced_secret_key_phone" to FreeText(
        "cb?x token=a#123 4567 890", "cb?$REDACTED#${SentryScrubber.REDACTED_PHONE}", "4567 890",
    ),
    // Controls keep the text unchanged.
    "plain_pairs" to FreeText("cb?mode=fast&next=1", "cb?mode=fast&next=1", null),
    "canonical_charset_key" to FreeText("cb?sort.order-by_x~y=asc", "cb?sort.order-by_x~y=asc", null),
    "valueless_key" to FreeText("cb?$CANARY\tx&next=1", "cb?$CANARY\tx&next=1", null),
    "empty_key" to FreeText("cb?=v&next=1", "cb?=v&next=1", null),
    "prose_question" to FreeText("sync failed? retry later", "sync failed? retry later", null),
    "issue_number" to FreeText("see #42 for details", "see #42 for details", null),
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
class R54L10FreeTextQueryPrivacyTest(private val rowName: String) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun rows(): List<Array<Any>> = freeTexts.keys.map { arrayOf<Any>(it) }
    }

    private val row = freeTexts.getValue(rowName)

    // The breadcrumb passes beforeBreadcrumb, then beforeSend again inside
    // the event, so the second pass must leave the first result alone.
    @Test
    fun breadcrumbMessageAndDataFollowTheKeyRule() {
        val options = hookedOptions()
        val crumb = Breadcrumb().apply {
            message = row.text
            setData("url", row.text)
            setData("detail", row.text)
        }
        val hooked = checkNotNull(checkNotNull(options.beforeBreadcrumb).execute(crumb, Hint()))
        val expectedData = mapOf("url" to row.expected, "detail" to row.expected)
        assertEquals(row.expected, hooked.message)
        assertEquals(expectedData, hooked.data.toMap())
        val sent = sentEvent(options, SentryEvent().apply { addBreadcrumb(hooked) })
        val sentCrumb = sent.breadcrumbs!!.single()
        assertEquals(row.expected, sentCrumb.message)
        assertEquals(expectedData, sentCrumb.data.toMap())
        assertNever(row.secret, render(sent))
    }

    @Test
    fun eventMessageAndExtrasFollowTheKeyRule() {
        val event = SentryEvent().apply {
            message = Message().apply {
                message = row.text
                formatted = row.text
            }
            extras = mapOf("detail" to row.text)
        }
        val sent = sentEvent(hookedOptions(), event)
        assertEquals(row.expected, sent.message!!.message)
        assertEquals(row.expected, sent.message!!.formatted)
        assertEquals(mapOf("detail" to row.expected), sent.extras)
        assertNever(row.secret, render(sent))
    }
}
