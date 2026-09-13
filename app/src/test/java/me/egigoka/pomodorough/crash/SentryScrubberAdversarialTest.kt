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
    fun verifierStateQueryKeepsKeyButLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/cb?verifier=verifier-secret" +
                    "&code_verifier=code-secret&state=state-secret&other=1",
            ),
        )
        assertTrue(scrubbed.contains("verifier="))
        assertTrue(scrubbed.contains("code_verifier="))
        assertTrue(scrubbed.contains("state="))
        assertFalse(scrubbed.contains("verifier-secret"))
        assertFalse(scrubbed.contains("code-secret"))
        assertFalse(scrubbed.contains("state-secret"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun verifierStateJsonLosesValue() {
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"verifier\": \"verifier-secret\", " +
                    "\"code_verifier\": \"code-secret\", " +
                    "\"state\": \"state-secret\", \"ok\": true}",
            ),
        )
        assertFalse(doubleScrubbed.contains("verifier-secret"))
        assertFalse(doubleScrubbed.contains("code-secret"))
        assertFalse(doubleScrubbed.contains("state-secret"))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'verifier': 'verifier-secret', 'code_verifier': 'code-secret', " +
                    "'state': 'state-secret', 'ok': true}",
            ),
        )
        assertFalse(singleScrubbed.contains("verifier-secret"))
        assertFalse(singleScrubbed.contains("code-secret"))
        assertFalse(singleScrubbed.contains("state-secret"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun verifierStateExtrasStayOpaqueWhileLookalikesSurvive() {
        val event = SentryEvent()
        event.setExtra("verifier", "verifier-secret")
        event.setExtra("code_verifier", "code-secret")
        event.setExtra("state", "state-secret")
        event.setExtra("statement", "quarterly statement draft")
        event.setExtra("stateFlow", "stateFlow idle")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "auth"
        crumb.setData("verifier", "verifier-secret")
        crumb.setData("state", "state-secret")
        crumb.setData("statement", "quarterly statement draft")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("verifier"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("code_verifier"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("state"))
        assertEquals("quarterly statement draft", event.getExtra("statement"))
        assertEquals("stateFlow idle", event.getExtra("stateFlow"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("verifier"))
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("state"))
        assertEquals("quarterly statement draft", scrubbedCrumb.getData("statement"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
        val text = checkNotNull(
            SentryScrubber.scrubText("statement draft stateFlow idle ok"),
        )
        assertTrue(text.contains("statement"))
        assertTrue(text.contains("stateFlow"))
    }

    @Test
    fun compoundSecretQueryKeepsKeyButLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/cb?codeVerifier=cv-aa&code_verifier=cv-ab" +
                    "&codeChallenge=cc-aa&code_challenge=cc-ab" +
                    "&clientSecret=cs-aa&client_secret=cs-ab" +
                    "&sessionId=si-aa&session_id=si-ab" +
                    "&authToken=at-aa&auth_token=at-ab" +
                    "&roomId=ri-aa&room_id=ri-ab" +
                    "&roomSecret=rs-aa&room_secret=rs-ab" +
                    "&endpointTicket=et-aa&endpoint_ticket=et-ab&other=1",
            ),
        )
        listOf(
            "codeVerifier=", "code_verifier=", "codeChallenge=",
            "code_challenge=", "clientSecret=", "client_secret=",
            "sessionId=", "session_id=", "authToken=", "auth_token=",
            "roomId=", "room_id=", "roomSecret=", "room_secret=",
            "endpointTicket=", "endpoint_ticket=",
        ).forEach { assertTrue(scrubbed.contains(it)) }
        listOf(
            "cv-aa", "cv-ab", "cc-aa", "cc-ab", "cs-aa", "cs-ab",
            "si-aa", "si-ab", "at-aa", "at-ab", "ri-aa", "ri-ab",
            "rs-aa", "rs-ab", "et-aa", "et-ab",
        ).forEach { assertFalse(scrubbed.contains(it)) }
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun compoundSecretDoubleQuotedJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"codeVerifier\": \"cv-aa\", \"code_verifier\": \"cv-ab\", " +
                    "\"codeChallenge\": \"cc-aa\", \"code_challenge\": \"cc-ab\", " +
                    "\"clientSecret\": \"cs-aa\", \"client_secret\": \"cs-ab\", " +
                    "\"sessionId\": \"si-aa\", \"session_id\": \"si-ab\", " +
                    "\"authToken\": \"at-aa\", \"auth_token\": \"at-ab\", " +
                    "\"roomId\": \"ri-aa\", \"room_id\": \"ri-ab\", " +
                    "\"roomSecret\": \"rs-aa\", \"room_secret\": \"rs-ab\", " +
                    "\"endpointTicket\": \"et-aa\", " +
                    "\"endpoint_ticket\": \"et-ab\", \"ok\": true}",
            ),
        )
        listOf(
            "cv-aa", "cv-ab", "cc-aa", "cc-ab", "cs-aa", "cs-ab",
            "si-aa", "si-ab", "at-aa", "at-ab", "ri-aa", "ri-ab",
            "rs-aa", "rs-ab", "et-aa", "et-ab",
        ).forEach { assertFalse(scrubbed.contains(it)) }
        assertTrue(scrubbed.contains("\"codeVerifier\""))
        assertTrue(scrubbed.contains("\"session_id\""))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("\"ok\""))
    }

    @Test
    fun compoundSecretSingleQuotedJsonLosesValue() {
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'codeVerifier': 'cv-aa', 'code_challenge': 'cc-ab', " +
                    "'clientSecret': 'cs-aa', 'sessionId': 'si-aa', " +
                    "'authToken': 'at-aa', 'roomId': 'ri-aa', " +
                    "'roomSecret': 'rs-aa', 'endpointTicket': 'et-aa', " +
                    "'ok': true}",
            ),
        )
        listOf(
            "cv-aa", "cc-ab", "cs-aa", "si-aa",
            "at-aa", "ri-aa", "rs-aa", "et-aa",
        ).forEach { assertFalse(scrubbed.contains(it)) }
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun compoundSecretExtrasStayOpaqueWhileCodeStaysQueryJsonOnly() {
        val event = SentryEvent()
        event.setExtra("codeVerifier", "cv-aa")
        event.setExtra("codeChallenge", "cc-aa")
        event.setExtra("clientSecret", "cs-aa")
        event.setExtra("sessionId", "si-aa")
        event.setExtra("authToken", "at-aa")
        event.setExtra("roomId", "ri-aa")
        event.setExtra("roomSecret", "rs-aa")
        event.setExtra("endpointTicket", "et-aa")
        event.setExtra("code", 7)
        event.setExtra("retryCount", 3)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("codeVerifier"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("codeChallenge"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("clientSecret"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("sessionId"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("authToken"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("roomId"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("roomSecret"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("endpointTicket"))
        assertEquals(7, event.getExtra("code"))
        assertEquals(3, event.getExtra("retryCount"))
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

    @Test
    fun endpointAndPeerIdQueryKeepsKeyButLosesValue() {
        // A50: bare `endpoint`/`peer` never matched `endpointId`/`peerId`
        // in free text; snake + camel variants must scrub in all three
        // query/JSON free-text regexes.
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?endpointId=ep-aa&endpoint_id=ep-ab" +
                    "&peerId=peer-aa&peer_id=peer-ab&other=1",
            ),
        )
        listOf("endpointId=", "endpoint_id=", "peerId=", "peer_id=").forEach {
            assertTrue(scrubbed.contains(it))
        }
        listOf("ep-aa", "ep-ab", "peer-aa", "peer-ab").forEach {
            assertFalse(scrubbed.contains(it))
        }
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
    }

    @Test
    fun endpointAndPeerIdJsonLosesValue() {
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"endpointId\": \"ep-aa\", \"endpoint_id\": \"ep-ab\", " +
                    "\"peerId\": \"peer-aa\", \"peer_id\": \"peer-ab\", " +
                    "\"ok\": true}",
            ),
        )
        listOf("ep-aa", "ep-ab", "peer-aa", "peer-ab").forEach {
            assertFalse(doubleScrubbed.contains(it))
        }
        assertTrue(doubleScrubbed.contains("\"endpointId\""))
        assertTrue(doubleScrubbed.contains("\"peer_id\""))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'endpointId': 'ep-aa', 'endpoint_id': 'ep-ab', " +
                    "'peerId': 'peer-aa', 'peer_id': 'peer-ab', 'ok': true}",
            ),
        )
        listOf("ep-aa", "ep-ab", "peer-aa", "peer-ab").forEach {
            assertFalse(singleScrubbed.contains(it))
        }
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun sidSsidQueryKeepsKeyButLosesValue() {
        // A59: bare `sid`/`ssid` never matched `session`/`session_?id` in
        // free text; mirror desktop D51 query/fragment coverage.
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?sid=sid-aa&ssid=ssid-aa&other=1",
            ),
        )
        assertTrue(scrubbed.contains("sid="))
        assertTrue(scrubbed.contains("ssid="))
        assertFalse(scrubbed.contains("sid-aa"))
        assertFalse(scrubbed.contains("ssid-aa"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
        val fragment = checkNotNull(
            SentryScrubber.scrubText("https://host/cb#ssid=frag-secret&other=1"),
        )
        assertTrue(fragment.contains("ssid="))
        assertFalse(fragment.contains("frag-secret"))
        assertTrue(fragment.contains("other=1"))
    }

    @Test
    fun sidSsidJsonLosesValue() {
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"sid\": \"sid-aa\", \"ssid\": \"ssid-aa\", \"ok\": true}",
            ),
        )
        assertFalse(doubleScrubbed.contains("sid-aa"))
        assertFalse(doubleScrubbed.contains("ssid-aa"))
        assertTrue(doubleScrubbed.contains("\"sid\""))
        assertTrue(doubleScrubbed.contains("\"ssid\""))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText("{'sid': 'sid-ab', 'ssid': 'ssid-ab', 'ok': true}"),
        )
        assertFalse(singleScrubbed.contains("sid-ab"))
        assertFalse(singleScrubbed.contains("ssid-ab"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
    }

    @Test
    fun sidSsidExtrasStayOpaque() {
        val event = SentryEvent()
        event.setExtra("sid", "sid-secret")
        event.setExtra("ssid", "ssid-secret")
        event.setExtra("clientSid", "client-sid-secret")
        event.setExtra("retryCount", 3)
        val crumb = Breadcrumb()
        crumb.message = "sync"
        crumb.setData("ssid", "ssid-secret")
        crumb.setData("attempt", 1)
        event.breadcrumbs = listOf(crumb)
        SentryScrubber.scrubEvent(event)
        assertEquals(SentryScrubber.REDACTED, event.getExtra("sid"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("ssid"))
        assertEquals(SentryScrubber.REDACTED, event.getExtra("clientSid"))
        assertEquals(3, event.getExtra("retryCount"))
        val scrubbedCrumb = event.breadcrumbs!!.single()
        assertEquals(SentryScrubber.REDACTED, scrubbedCrumb.getData("ssid"))
        assertEquals(1, scrubbedCrumb.getData("attempt"))
    }

    @Test
    fun compoundSidSsidQueryKeepsKeyButLosesValue() {
        // A64: suffix-wildcard covers compound keys (clientSid, deviceSsid)
        // in free text; mid-word lookalikes (reside, consider, aside,
        // inside) must survive.
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?clientSid=cs-aa&deviceSsid=ds-aa&other=1",
            ),
        )
        assertTrue(scrubbed.contains("clientSid="))
        assertTrue(scrubbed.contains("deviceSsid="))
        assertFalse(scrubbed.contains("cs-aa"))
        assertFalse(scrubbed.contains("ds-aa"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?reside=keepme&consider=keepme&aside=keepme&inside=keepme&other=1",
            ),
        )
        assertTrue(lookalike.contains("reside=keepme"))
        assertTrue(lookalike.contains("consider=keepme"))
        assertTrue(lookalike.contains("aside=keepme"))
        assertTrue(lookalike.contains("inside=keepme"))
        assertTrue(lookalike.contains("other=1"))
    }

    @Test
    fun compoundSidSsidJsonLosesValue() {
        // A64: same suffix-wildcard coverage for double- and single-quoted
        // JSON-string forms; lookalike keys stay readable.
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"clientSsid\": \"cs-aa\", \"clientSid\": \"cs-ab\", \"ok\": true}",
            ),
        )
        assertFalse(doubleScrubbed.contains("cs-aa"))
        assertFalse(doubleScrubbed.contains("cs-ab"))
        assertTrue(doubleScrubbed.contains("\"clientSsid\""))
        assertTrue(doubleScrubbed.contains("\"clientSid\""))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'deviceSsid': 'ds-aa', 'deviceSid': 'ds-ab', 'ok': true}",
            ),
        )
        assertFalse(singleScrubbed.contains("ds-aa"))
        assertFalse(singleScrubbed.contains("ds-ab"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText("{\"reside\": \"keepme\", \"aside\": \"keepme\", \"ok\": true}"),
        )
        assertTrue(lookalike.contains("keepme"))
        assertTrue(lookalike.contains("\"ok\""))
        val insideLookalike = checkNotNull(
            SentryScrubber.scrubText("{\"inside\": \"keepme\", \"consider\": \"keepme\", \"ok\": true}"),
        )
        assertTrue(insideLookalike.contains("keepme"))
        assertTrue(insideLookalike.contains("\"ok\""))
    }

    @Test
    fun hyphenatedSidSsidQueryKeepsKeyButLosesValue() {
        // A66: hyphenated compounds (`client-sid`, `device-ssid`) missed
        // free-text while structured paths covered them; hyphen-adjacent
        // lookalikes (`client-side`) must survive.
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?client-sid=cs-aa&device-ssid=ds-aa&other=1",
            ),
        )
        assertTrue(scrubbed.contains("client-sid="))
        assertTrue(scrubbed.contains("device-ssid="))
        assertFalse(scrubbed.contains("cs-aa"))
        assertFalse(scrubbed.contains("ds-aa"))
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?client-side=keepme&re-side=keepme&other=1",
            ),
        )
        assertTrue(lookalike.contains("client-side=keepme"))
        assertTrue(lookalike.contains("re-side=keepme"))
        assertTrue(lookalike.contains("other=1"))
    }

    @Test
    fun hyphenatedSidSsidJsonLosesValue() {
        // A66: same hyphen coverage for double- and single-quoted
        // JSON-string forms; hyphen lookalike keys stay readable.
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"client-sid\": \"cs-aa\", \"device-ssid\": \"ds-aa\", \"ok\": true}",
            ),
        )
        assertFalse(doubleScrubbed.contains("cs-aa"))
        assertFalse(doubleScrubbed.contains("ds-aa"))
        assertTrue(doubleScrubbed.contains("\"client-sid\""))
        assertTrue(doubleScrubbed.contains("\"device-ssid\""))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'client-ssid': 'cs-ab', 'device-sid': 'ds-ab', 'ok': true}",
            ),
        )
        assertFalse(singleScrubbed.contains("cs-ab"))
        assertFalse(singleScrubbed.contains("ds-ab"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText("{\"client-side\": \"keepme\", \"ok\": true}"),
        )
        assertTrue(lookalike.contains("keepme"))
        assertTrue(lookalike.contains("\"ok\""))
    }

    @Test
    fun kebabCompoundQueryKeepsKeyButLosesValue() {
        // A74 (A66 mirror): `_?` missed kebab-case compounds; `[-_]?`
        // closes the gap. All 15 compounds pinned in query form; the
        // JSON tests pin 5 representatives cheaply. Hyphen-adjacent
        // lookalikes must survive.
        val scrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?id-token=it-ff&access-token=ac-gg&refresh-token=rf-hh&csrf-token=cf-ii&code-challenge=cch-jj&code-verifier=cv-bb&client-secret=cs-aa&session-id=si-kk&auth-token=at-cc&device-id=di-dd&room-id=ri-ee&room-secret=rs-ll&endpoint-ticket=et-mm&endpoint-id=ei-nn&peer-id=pi-oo&other=1",
            ),
        )
        listOf(
            "id-token=", "access-token=", "refresh-token=", "csrf-token=",
            "code-challenge=", "code-verifier=", "client-secret=",
            "session-id=", "auth-token=", "device-id=", "room-id=",
            "room-secret=", "endpoint-ticket=", "endpoint-id=", "peer-id=",
        ).forEach { assertTrue(scrubbed.contains(it)) }
        listOf(
            "it-ff", "ac-gg", "rf-hh", "cf-ii", "cch-jj", "cv-bb",
            "cs-aa", "si-kk", "at-cc", "di-dd", "ri-ee", "rs-ll",
            "et-mm", "ei-nn", "pi-oo",
        ).forEach { assertFalse(scrubbed.contains(it)) }
        assertTrue(scrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(scrubbed.contains("other=1"))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText(
                "https://host/sync?client-side=keepme&re-side=keepme&other=1",
            ),
        )
        assertTrue(lookalike.contains("client-side=keepme"))
        assertTrue(lookalike.contains("re-side=keepme"))
        assertTrue(lookalike.contains("other=1"))
    }

    @Test
    fun kebabCompoundJsonLosesValue() {
        // A74 (A66 mirror): same kebab coverage for double- and
        // single-quoted JSON-string forms; lookalike keys stay readable.
        // Snake/camel forms keep working through the same `[-_]?` class.
        val doubleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{\"client-secret\": \"cs-aa\", \"code-verifier\": \"cv-bb\", \"auth-token\": \"at-cc\", \"device-id\": \"di-dd\", \"room-id\": \"ri-ee\", \"ok\": true}",
            ),
        )
        assertFalse(doubleScrubbed.contains("cs-aa"))
        assertFalse(doubleScrubbed.contains("cv-bb"))
        assertFalse(doubleScrubbed.contains("at-cc"))
        assertFalse(doubleScrubbed.contains("di-dd"))
        assertFalse(doubleScrubbed.contains("ri-ee"))
        assertTrue(doubleScrubbed.contains("\"client-secret\""))
        assertTrue(doubleScrubbed.contains("\"code-verifier\""))
        assertTrue(doubleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        assertTrue(doubleScrubbed.contains("\"ok\""))
        val singleScrubbed = checkNotNull(
            SentryScrubber.scrubText(
                "{'client-secret': 'cs-ab', 'code-verifier': 'cv-bc', 'auth-token': 'at-cd', 'device-id': 'di-de', 'room-id': 'ri-ef', 'ok': true}",
            ),
        )
        assertFalse(singleScrubbed.contains("cs-ab"))
        assertFalse(singleScrubbed.contains("cv-bc"))
        assertFalse(singleScrubbed.contains("at-cd"))
        assertFalse(singleScrubbed.contains("di-de"))
        assertFalse(singleScrubbed.contains("ri-ef"))
        assertTrue(singleScrubbed.contains(SentryScrubber.REDACTED_TOKEN))
        val snakeSurvives = checkNotNull(
            SentryScrubber.scrubText(
                "{\"client_secret\": \"cs-ac\", \"code_verifier\": \"cv-bd\", \"ok\": true}",
            ),
        )
        assertFalse(snakeSurvives.contains("cs-ac"))
        assertFalse(snakeSurvives.contains("cv-bd"))
        val camelSurvives = checkNotNull(
            SentryScrubber.scrubText(
                "{\"clientSecret\": \"cs-ad\", \"codeVerifier\": \"cv-be\", \"ok\": true}",
            ),
        )
        assertFalse(camelSurvives.contains("cs-ad"))
        assertFalse(camelSurvives.contains("cv-be"))
        val lookalike = checkNotNull(
            SentryScrubber.scrubText("{\"client-side\": \"keepme\", \"ok\": true}"),
        )
        assertTrue(lookalike.contains("keepme"))
        assertTrue(lookalike.contains("\"ok\""))
    }
}
