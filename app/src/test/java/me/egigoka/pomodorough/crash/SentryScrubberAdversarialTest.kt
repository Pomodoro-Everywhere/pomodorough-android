package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberAdversarialTest {
    @Test
    fun inviteAndCodeQueryParametersKeepKeyButLoseValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/join?invite=pomodorough1SECRET99&code=room-code-secret&other=1",
            ),
        )
        assertTrue(scrubbed.contains("invite="))
        assertTrue(scrubbed.contains("code="))
        assertFalse(scrubbed.contains("pomodorough1"))
        assertFalse(scrubbed.contains("room-code-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun inviteQueryAloneLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("https://host/sync&invite=abc123"),
        )
        assertTrue(scrubbed.contains("invite="))
        assertFalse(scrubbed.contains("abc123"))
    }

    @Test
    fun passwordAndCredentialJsonValuesAreStripped() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                """{"password": "hunter2", "credential": "cred-secret", "ok": true}""",
            ),
        )
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains("cred-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("\"ok\""))
    }

    @Test
    fun passwordAndCredentialKeysStayOpaque() {
        val event = SentryEvent()
        event.setExtra("password", "hunter2")
        event.setExtra("authCredential", "cred-secret")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "sign-in"
        crumb.setData("userPassword", "hunter2")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("password"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("authCredential"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("userPassword"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
    }

    @Test
    fun tightenedTruncationExposesOnlyFourPlusTwo() {
        val long = "550e8400-e29b-41d4-a716-446655440000"
        assertEquals("550e…00", SentryScrubber.scrubText(long))
        assertFalse(checkNotNull(SentryScrubber.scrubText(long)).contains("550e8400"))
        assertFalse(checkNotNull(SentryScrubber.scrubText(long)).contains("440000"))
    }

    @Test
    fun combinedInviteCodePasswordPayloadLosesEverySecret() {
        val payload = "user ada@example.com invite pomodorough1SECRET99 " +
            "https://host/join?invite=abc123&code=room-secret " +
            "{\"password\": \"hunter2\"} Bearer tokengoeshere1234567890"
        val scrubbed = checkNotNull(SentryScrubber.scrubText(payload))
        assertFalse(scrubbed.contains("ada@example.com"))
        assertFalse(scrubbed.contains("pomodorough1"))
        assertFalse(scrubbed.contains("abc123"))
        assertFalse(scrubbed.contains("room-secret"))
        assertFalse(scrubbed.contains("hunter2"))
        assertFalse(scrubbed.contains("tokengoeshere"))
    }
}
