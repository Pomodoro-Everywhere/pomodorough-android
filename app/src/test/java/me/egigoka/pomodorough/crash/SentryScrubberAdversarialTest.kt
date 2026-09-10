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

    @Test
    fun secretCookieSessionApiKeyQueryKeepsKeyButLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?secret=s3cr3t&cookie=c00kie&session=sess99" +
                    "&api_key=key123&apikey=key456&other=1",
            ),
        )
        assertTrue(scrubbed.contains("secret="))
        assertTrue(scrubbed.contains("cookie="))
        assertTrue(scrubbed.contains("session="))
        assertTrue(scrubbed.contains("api_key="))
        assertTrue(scrubbed.contains("apikey="))
        assertFalse(scrubbed.contains("s3cr3t"))
        assertFalse(scrubbed.contains("c00kie"))
        assertFalse(scrubbed.contains("sess99"))
        assertFalse(scrubbed.contains("key123"))
        assertFalse(scrubbed.contains("key456"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun fragmentTokenLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("https://host/cb#token=frag-secret&other=1"),
        )
        assertTrue(scrubbed.contains("token="))
        assertFalse(scrubbed.contains("frag-secret"))
    }

    @Test
    fun secretCookieInviteSessionApiKeyJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"secret\": \"s3cr3t\", \"cookie\": \"c00kie\", " +
                    "\"invite\": \"pomodorough1SECRET99\", \"session\": \"sess99\", " +
                    "\"api_key\": \"key123\", \"apikey\": \"key456\", \"ok\": true}",
            ),
        )
        assertFalse(scrubbed.contains("s3cr3t"))
        assertFalse(scrubbed.contains("c00kie"))
        assertFalse(scrubbed.contains("pomodorough1"))
        assertFalse(scrubbed.contains("sess99"))
        assertFalse(scrubbed.contains("key123"))
        assertFalse(scrubbed.contains("key456"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("\"ok\""))
    }

    @Test
    fun sessionAndApiKeyExtrasStayOpaque() {
        val event = SentryEvent()
        event.setExtra("sessionToken", "sess-secret")
        event.setExtra("apiKey", "key-secret")
        event.setExtra("userSession", "sess-secret")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "sync"
        crumb.setData("sessionId", "sess-secret")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("sessionToken"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("apiKey"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("userSession"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("sessionId"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
    }

    @Test
    fun phoneNumbersLoseDigitsButDatesAndVersionsSurvive() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("call +1 415-555-1234 or (415) 555-1234 on 2026-09-08 v1.2.3"),
        )
        assertFalse(scrubbed.contains("415-555-1234"))
        assertFalse(scrubbed.contains("(415)"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_PHONE))
        assertTrue(scrubbed.contains("2026-09-08"))
        assertTrue(scrubbed.contains("1.2.3"))
    }

    @Test
    fun ipv4AndIpv6PeersAreStripped() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText("peer 192.168.1.10 via 2001:db8::1 mapped ::ffff:192.168.1.10 ok"),
        )
        assertFalse(scrubbed.contains("192.168.1.10"))
        assertFalse(scrubbed.contains("2001:db8::1"))
        assertFalse(scrubbed.contains("::ffff"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_IP))
        assertTrue(scrubbed.contains("peer"))
    }

    @Test
    fun combinedSecretPhoneIpPayloadLosesEverySecret() {
        val payload = "user ada@example.com ?session=sess99 {\"api_key\": \"key123\"} " +
            "call +1 415-555-1234 peer 10.0.0.8 pomodorough1SECRET99"
        val scrubbed = checkNotNull(SentryScrubber.scrubText(payload))
        assertFalse(scrubbed.contains("ada@example.com"))
        assertFalse(scrubbed.contains("sess99"))
        assertFalse(scrubbed.contains("key123"))
        assertFalse(scrubbed.contains("415-555-1234"))
        assertFalse(scrubbed.contains("10.0.0.8"))
        assertFalse(scrubbed.contains("pomodorough1"))
    }

    @Test
    fun bareAuthAndPrivateKeyKeysStayOpaque() {
        val event = SentryEvent()
        event.setExtra("auth", "auth-secret")
        event.setExtra("AuthToken", "token-secret")
        event.setExtra("private_key", "key-secret")
        event.setExtra("privateKey", "key-secret")
        event.setExtra("bearer", "bearer-secret")
        event.setExtra("endpointTicket", "ticket-secret")
        event.setExtra("sentryDsn", "https://x@y/1")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "sign-in"
        crumb.setData("clientAuth", "auth-secret")
        crumb.setData("roomSecret", "room-secret")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("auth"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("AuthToken"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("private_key"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("privateKey"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("bearer"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("endpointTicket"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("sentryDsn"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("clientAuth"))
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("roomSecret"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
    }

    @Test
    fun semicolonQueryParametersKeepKeyButLoseValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?token=abc123;auth=auth-secret;other=1",
            ),
        )
        assertTrue(scrubbed.contains("token="))
        assertTrue(scrubbed.contains("auth="))
        assertFalse(scrubbed.contains("abc123"))
        assertFalse(scrubbed.contains("auth-secret"))
        assertTrue(scrubbed.contains("other=1"))
        val leading = checkNotNull(
            SentryScrubber.scrubText("https://host/sync;session=sess99;other=1"),
        )
        assertTrue(leading.contains("session="))
        assertFalse(leading.contains("sess99"))
        assertTrue(leading.contains("other=1"))
    }

    @Test
    fun singleAndDoubleQuotedJsonValuesAreStripped() {
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText("""{"auth": "auth-secret", "ok": true}"""),
        )
        assertFalse(doubleScrubbed.contains("auth-secret"))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText("{'token': 'abc123', 'auth': 'auth-secret', 'ok': true}"),
        )
        assertFalse(singleScrubbed.contains("abc123"))
        assertFalse(singleScrubbed.contains("auth-secret"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        val privateScrubbed = checkNotNull(
            SentryScrubber.scrubText("{'private_key': 'key-secret', 'ok': true}"),
        )
        assertFalse(privateScrubbed.contains("key-secret"))
    }

    @Test
    fun dottedInviteWithBase64UrlTailIsFullyRedacted() {
        val dotted = "pomodorough1.eyJ2IjoxLCJyb29tSWQiOiJhYmMifQ-_8"
        val scrubbed = checkNotNull(SentryScrubber.scrubText("join $dotted now"))
        assertFalse(scrubbed.contains("pomodorough1"))
        assertFalse(scrubbed.contains("eyJ2Ijox"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_INVITE))
        val legacy = checkNotNull(
            SentryScrubber.scrubText("join pomodorough1SECRET99 now"),
        )
        assertFalse(legacy.contains("pomodorough1"))
        val query = checkNotNull(
            SentryScrubber.scrubText("https://host/join?invite=$dotted&other=1"),
        )
        assertTrue(query.contains("invite="))
        assertFalse(query.contains("eyJ2Ijox"))
        assertTrue(query.contains("other=1"))
    }

    @Test
    fun oidcCamelAndNonceJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"idToken\": \"id-secret\", \"challenge\": \"chall-secret\", " +
                    "\"nonce\": \"nonce-secret\", \"ok\": true}",
            ),
        )
        assertFalse(scrubbed.contains("id-secret"))
        assertFalse(scrubbed.contains("chall-secret"))
        assertFalse(scrubbed.contains("nonce-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("\"ok\""))
    }

    @Test
    fun tokenFamilyCamelJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"accessToken\": \"access-secret\", " +
                    "\"refreshToken\": \"refresh-secret\", " +
                    "\"csrfToken\": \"csrf-secret\", " +
                    "\"deviceId\": \"device-secret\", \"ok\": true}",
            ),
        )
        assertFalse(scrubbed.contains("access-secret"))
        assertFalse(scrubbed.contains("refresh-secret"))
        assertFalse(scrubbed.contains("csrf-secret"))
        assertFalse(scrubbed.contains("device-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("\"ok\""))
    }

    @Test
    fun oidcSingleQuotedJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'idToken': 'id-secret', 'challenge': 'chall-secret', " +
                    "'nonce': 'nonce-secret', 'ok': true}",
            ),
        )
        assertFalse(scrubbed.contains("id-secret"))
        assertFalse(scrubbed.contains("chall-secret"))
        assertFalse(scrubbed.contains("nonce-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun oidcQueryKeepsKeyButLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/cb?idToken=id-secret&challenge=chall-secret" +
                    "&nonce=nonce-secret&other=1",
            ),
        )
        assertTrue(scrubbed.contains("idToken="))
        assertTrue(scrubbed.contains("challenge="))
        assertTrue(scrubbed.contains("nonce="))
        assertFalse(scrubbed.contains("id-secret"))
        assertFalse(scrubbed.contains("chall-secret"))
        assertFalse(scrubbed.contains("nonce-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun challengeAndNonceExtrasStayOpaque() {
        val event = SentryEvent()
        event.setExtra("challenge", "chall-secret")
        event.setExtra("nonce", "nonce-secret")
        event.setExtra("idToken", "id-secret")
        event.setExtra("csrfToken", "csrf-secret")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "auth"
        crumb.setData("challenge", "chall-secret")
        crumb.setData("nonce", "nonce-secret")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("challenge"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("nonce"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("idToken"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("csrfToken"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("challenge"))
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("nonce"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
    }
}
