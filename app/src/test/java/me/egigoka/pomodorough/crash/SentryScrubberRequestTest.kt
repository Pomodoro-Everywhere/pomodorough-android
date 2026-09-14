package me.egigoka.pomodorough.crash

import io.sentry.JsonSerializer
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import java.io.StringWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberRequestTest {
    @Test
    fun firstSubsequentAndEncodedCredentialParametersLoseEntireValues() {
        val keys = listOf("token", "code", "state", "credential", "%74oken", "access%5Ftoken", "CLIENT%2DSECRET")
        for (key in keys) {
            for (prefix in listOf("", "?", "mode=fast&", "mode=fast;", "mode=fast#")) {
                val event = SentryEvent().apply {
                    request = Request().apply { queryString = "${prefix}${key}=abc%26def%3Dghi&count=2" }
                }
                SentryScrubber.scrubEvent(event)
                assertEquals("${prefix}${key}=${SentryScrubber.REDACTED_TOKEN}&count=2", event.request!!.queryString)
            }
        }
    }

    @Test
    fun questionMarksAndEqualsInsideCredentialValuesDoNotLeaveSuffixes() {
        val event = SentryEvent().apply {
            request = Request().apply { queryString = "token=abc?tail=xyz&mode=fast" }
        }
        SentryScrubber.scrubEvent(event)
        assertEquals("token=[REDACTED_TOKEN]&mode=fast", event.request!!.queryString)
    }

    @Test
    fun repeatedEmptyAndMalformedParametersAreSafeWithoutLosingCleanValues() {
        val event = SentryEvent().apply {
            request = Request().apply {
                queryString = "token=abc&token=&%ZZtoken=def&mode=fast+sync&statement=ok&email=ada%40example.com"
            }
        }
        SentryScrubber.scrubEvent(event)
        assertEquals(
            "token=[REDACTED_TOKEN]&token=[REDACTED_TOKEN]&[REDACTED]&mode=fast+sync&statement=ok&email=[REDACTED_TOKEN]",
            event.request!!.queryString,
        )
    }

    @Test
    fun encodedCredentialsInFragmentOnlyUrlAreScrubbed() {
        val event = SentryEvent().apply {
            request = Request().apply { url = "https://host/sync#%74oken=abc&mode=fast" }
        }
        SentryScrubber.scrubEvent(event)
        assertEquals("https://host/sync#%74oken=[REDACTED_TOKEN]&mode=fast", event.request!!.url)
    }

    @Test
    fun serializedRequestContainsNeitherCredentialsNorCookiePayloads() {
        val event = SentryEvent().apply {
            request = Request().apply {
                url = "https://host/sync?%74oken=url-secret&mode=fast"
                queryString = "token=first-secret&mode=fast&code=last-secret"
                fragment = "%73tate=fragment-secret"
                cookies = "session=cookie-secret; harmless=also-private"
                headers = mapOf("cOoKiE" to "opaque=header-secret", "SET-cookie" to "id=response-secret", "Accept" to "json")
            }
        }
        SentryScrubber.scrubEvent(event)
        assertNull(event.request!!.cookies)
        assertEquals(mapOf("Accept" to "json"), event.request!!.headers)
        val writer = StringWriter()
        JsonSerializer(SentryOptions()).serialize(event, writer)
        val serialized = writer.toString()
        listOf("url-secret", "first-secret", "last-secret", "fragment-secret", "cookie-secret", "also-private", "header-secret", "response-secret").forEach {
            assertFalse("Leaked $it in serialized request", serialized.contains(it))
        }
        assertFalse(serialized.contains("\"cookies\""))
        assertTrue(serialized.contains("mode=fast"))
        assertTrue(serialized.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun invalidUtf8SequencesRedactCompleteParameter() {
        val sequences = listOf("%FF", "%FE", "%80", "%C3%28", "%E2%82%28", "%C0%AF", "%ED%A0%80")
        for (sequence in sequences) {
            val valueCase = SentryEvent().apply {
                request = Request().apply { queryString = "mode=$sequence&count=2" }
            }
            SentryScrubber.scrubEvent(valueCase)
            assertEquals("[REDACTED]&count=2", valueCase.request!!.queryString)
            assertFalse(valueCase.request!!.queryString!!.contains(sequence))
            val keyCase = SentryEvent().apply {
                request = Request().apply { queryString = "$sequence=abc&mode=fast" }
            }
            SentryScrubber.scrubEvent(keyCase)
            assertEquals("[REDACTED]&mode=fast", keyCase.request!!.queryString)
            assertFalse(keyCase.request!!.queryString!!.contains(sequence))
        }
    }

    @Test
    fun encodedDelimitersInKeysRedactWholeParameter() {
        val keys = listOf("token%3Dsecret", "token%3dsecret", "token%26mode", "token%23frag", "token%3Bmode", "token%3Fmode", "mode%3Dfast", "token%2Bmode")
        for (key in keys) {
            for (query in listOf("$key&mode=fast", "$key=abc&mode=fast")) {
                val event = SentryEvent().apply {
                    request = Request().apply { queryString = query }
                }
                SentryScrubber.scrubEvent(event)
                assertEquals("[REDACTED]&mode=fast", event.request!!.queryString)
                assertFalse(event.request!!.queryString!!.contains(key))
            }
        }
    }

    @Test
    fun repeatedEncodingRedactsWholeParameter() {
        for (query in listOf("%2574oken=secret&mode=fast", "token%253Dsecret&mode=fast")) {
            val event = SentryEvent().apply {
                request = Request().apply { queryString = query }
            }
            SentryScrubber.scrubEvent(event)
            assertEquals("[REDACTED]&mode=fast", event.request!!.queryString)
            assertFalse(event.request!!.queryString!!.contains("%25"))
        }
        val valueCase = SentryEvent().apply {
            request = Request().apply { queryString = "mode=abc%2526token%253Dsecret&count=2" }
        }
        SentryScrubber.scrubEvent(valueCase)
        val scrubbed = valueCase.request!!.queryString!!
        assertFalse("Leaked double-encoded secret in $scrubbed", scrubbed.contains("secret"))
        assertFalse(scrubbed.contains("%25"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("count=2"))
    }

    @Test
    fun malformedPercentSyntaxRedactsParameterWithoutLosingNeighbors() {
        for (query in listOf("%&count=2", "%2&count=2", "%ZZ&count=2", "mode=%&count=2", "mode=%2&count=2", "mode=%ZZ&count=2")) {
            val event = SentryEvent().apply {
                request = Request().apply { queryString = query }
            }
            SentryScrubber.scrubEvent(event)
            assertEquals("[REDACTED]&count=2", event.request!!.queryString)
        }
        val sensitiveMalformedValue = SentryEvent().apply {
            request = Request().apply { queryString = "token=%&mode=fast" }
        }
        SentryScrubber.scrubEvent(sensitiveMalformedValue)
        assertEquals("token=${SentryScrubber.REDACTED_TOKEN}&mode=fast", sensitiveMalformedValue.request!!.queryString)
    }

    @Test
    fun ordinaryCredentialKeysLoseValuesInQuery() {
        val event = SentryEvent().apply {
            request = Request().apply {
                queryString = "password=hunter2&secret=s3cr3t&api_key=k1&auth=a1&bearer=b1&session=s1&code=c1&state=s2&mode=fast"
            }
        }
        SentryScrubber.scrubEvent(event)
        assertEquals(
            "password=${SentryScrubber.REDACTED_TOKEN}&secret=${SentryScrubber.REDACTED_TOKEN}" +
                "&api_key=${SentryScrubber.REDACTED_TOKEN}&auth=${SentryScrubber.REDACTED_TOKEN}" +
                "&bearer=${SentryScrubber.REDACTED_TOKEN}&session=${SentryScrubber.REDACTED_TOKEN}" +
                "&code=${SentryScrubber.REDACTED_TOKEN}&state=${SentryScrubber.REDACTED_TOKEN}&mode=fast",
            event.request!!.queryString,
        )
        val lookalike = SentryEvent().apply {
            request = Request().apply { queryString = "statement=ok&encode=keep&mode=fast" }
        }
        SentryScrubber.scrubEvent(lookalike)
        assertEquals("statement=ok&encode=keep&mode=fast", lookalike.request!!.queryString)
    }

    @Test
    fun serializedEventWithStrictDecodingLeaksNothing() {
        val event = SentryEvent().apply {
            request = Request().apply {
                url = "https://host/sync?token=url-secret&mode=fast"
                queryString = "token=first-secret&token%3Dsecret&mode=%FF&%2574oken=double-secret&count=2"
                fragment = "state=fragment-secret"
                cookies = "session=cookie-secret; id=also-private"
                headers = mapOf("Cookie" to "opaque=header-secret", "Accept" to "json")
            }
        }
        SentryScrubber.scrubEvent(event)
        assertNull(event.request!!.cookies)
        assertEquals(mapOf("Accept" to "json"), event.request!!.headers)
        val writer = StringWriter()
        JsonSerializer(SentryOptions()).serialize(event, writer)
        val serialized = writer.toString()
        listOf("url-secret", "first-secret", "token%3Dsecret", "fragment-secret", "cookie-secret", "also-private", "header-secret", "double-secret", "%FF", "%2574oken").forEach {
            assertFalse("Leaked $it in serialized event", serialized.contains(it))
        }
        assertFalse(serialized.contains("\"cookies\""))
        assertTrue(serialized.contains("mode=fast"))
        assertTrue(serialized.contains("count=2"))
        assertTrue(serialized.contains(SentryScrubber.REDACTED))
        assertTrue(serialized.contains(SentryScrubber.REDACTED_TOKEN))
    }
}
