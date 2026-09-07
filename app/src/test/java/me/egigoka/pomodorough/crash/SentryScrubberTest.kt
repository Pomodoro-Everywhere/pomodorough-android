package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.Request
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberTest {
    @Test
    fun nullStaysNull() {
        assertNull(SentryScrubber.scrubText(null))
    }

    @Test
    fun plainTextPassesThrough() {
        assertEquals("timer finished", SentryScrubber.scrubText("timer finished"))
    }

    @Test
    fun emailAddressesAreStripped() {
        val scrubbed = checkNotNull(SentryScrubber.scrubText("sign-in failed for ada@example.com today"))
        assertFalse(scrubbed.contains("ada@example.com"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_EMAIL))
    }

    @Test
    fun multipleEmailsAreAllStripped() {
        val scrubbed = checkNotNull(SentryScrubber.scrubText("a@x.io and b@y.io met"))
        assertFalse(scrubbed.contains("@"))
        assertEquals(2, scrubbed.split(SentryScrubber.REDACTED_EMAIL).size - 1)
    }

    @Test
    fun bearerAndBasicCredentialsAreStripped() {
        val bearer = checkNotNull(SentryScrubber.scrubText("Authorization: Bearer abcDEF123._-+/="))
        assertFalse(bearer.contains("abcDEF123"))
        assertTrue(bearer.contains(SentryScrubber.REDACTED_AUTHORIZATION))
        val basic = checkNotNull(SentryScrubber.scrubText("basic dXNlcjpwYXNz"))
        assertFalse(basic.contains("dXNlcjpwYXNz"))
    }

    @Test
    fun tokenQueryParametersKeepKeyButLoseValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("https://host/sync?token=secret123&other=1"),
        )
        assertTrue(scrubbed.contains("token="))
        assertFalse(scrubbed.contains("secret123"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun tokenJsonValuesAreStripped() {
        val scrubbed = checkNotNull(SentryScrubber.scrubText("""{"token": "abc123", "ok": true}"""))
        assertFalse(scrubbed.contains("abc123"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun authorizationJsonValuesAreStripped() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("""{"authorization": "Bearer xyz", "ok": true}"""),
        )
        assertFalse(scrubbed.contains("xyz"))
    }

    @Test
    fun roomInvitesAreFullyRedacted() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("join pomodorough1abcdefABCDEF0123456789 now"),
        )
        assertFalse(scrubbed.contains("pomodorough1"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_INVITE))
    }

    @Test
    fun longIdsAreTruncatedButShortIdsSurvive() {
        val long = "550e8400-e29b-41d4-a716-446655440000"
        val scrubbed = checkNotNull(SentryScrubber.scrubText("room $long opened"))
        assertFalse(scrubbed.contains(long))
        assertTrue(scrubbed.contains("550e8400"))
        assertEquals("timer-1", SentryScrubber.scrubText("timer-1"))
    }

    @Test
    fun combinedAdversarialPayloadLosesEverySecret() {
        val payload = "user ada@example.com invite pomodorough1SECRET99 " +
            "Bearer tokengoeshere1234567890 ?token=qwerty {\"token\": \"zzz\"}"
        val scrubbed = checkNotNull(SentryScrubber.scrubText(payload))
        assertFalse(scrubbed.contains("ada@example.com"))
        assertFalse(scrubbed.contains("pomodorough1"))
        assertFalse(scrubbed.contains("tokengoeshere"))
        assertFalse(scrubbed.contains("qwerty"))
        assertFalse(scrubbed.contains("\"zzz\""))
    }

    @Test
    fun scrubbingIsIdempotent() {
        val once = checkNotNull(SentryScrubber.scrubText("ada@example.com pomodorough1ABCDEF123456"))
        assertEquals(once, SentryScrubber.scrubText(once))
    }

    @Test
    fun breadcrumbMessageAndDataAreScrubbed() {
        val crumb = Breadcrumb()
        crumb.message = "sync for ada@example.com"
        crumb.setData("invite", "pomodorough1SECRET99")
        crumb.setData("Authorization", "Bearer abcdefgh12345678")
        crumb.setData("attempt", 3)
        SentryScrubber.scrubBreadcrumb(crumb)
        assertFalse(checkNotNull(crumb.message).contains("ada@example.com"))
        assertEquals(SentryScrubber.REDACTED, crumb.getData("invite"))
        assertEquals(SentryScrubber.REDACTED, crumb.getData("Authorization"))
        assertEquals(3, crumb.getData("attempt"))
    }

    @Test
    fun eventRequestUserTagsAndExtrasAreScrubbed() {
        val event = SentryEvent()
        event.message = Message().apply { formatted = "failed for ada@example.com" }
        event.setTag("email", "ada@example.com")
        event.setTag("route", "cloud")
        event.setExtra("invite", "pomodorough1SECRET99")
        event.request = Request().apply {
            url = "https://host/sync?token=secret123"
            queryString = "token=secret123"
            cookies = "session=abc"
            headers = mapOf("Authorization" to "Bearer abcdefgh12345678", "Accept" to "json")
        }
        event.user = User().apply { email = "ada@example.com" }
        SentryScrubber.scrubEvent(event)
        assertFalse(checkNotNull(event.message?.formatted).contains("ada@example.com"))
        assertEquals(SentryScrubber.REDACTED_EMAIL, event.getTag("email"))
        assertEquals("cloud", event.getTag("route"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("invite"))
        assertFalse(checkNotNull(event.request?.url).contains("secret123"))
        assertEquals(SentryScrubber.REDACTED, event.request?.headers?.get("Authorization"))
        assertEquals("json", event.request?.headers?.get("Accept"))
        assertEquals(SentryScrubber.REDACTED_EMAIL, event.user?.email)
    }
}
