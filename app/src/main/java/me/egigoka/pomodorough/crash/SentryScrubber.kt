package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Geo
import io.sentry.protocol.Mechanism
import io.sentry.protocol.Message
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.User

object SentryScrubber {
    // Residual coverage (A23): stacktrace frames/registers, User name/data/geo,
    // and typed Device/App/Os contexts are scrubbed even though no app path
    // writes secrets there. A27 extends token query/JSON keys (secret,
    // cookie, session, api_key) plus `#` fragments. A28 redacts phone/IP in
    // free text and drops geo city/region outright. A29 scrubs identifying
    // device hardware strings beyond truncation. A30 hardens key filtering
    // for bare `auth`/`private_key` plus neighboring bearer/ticket/dsn and
    // Iroh device/peer/endpoint/room keys. A31 hardens free text for
    // `;`-separated params, single-quoted JSON, and dot/base64url invites.
    // The app never calls Sentry.setUser and never puts invites, tokens, or
    // emails into contexts; Device/App/Os values come from the Sentry
    // Android SDK (hardware model, OS version, app build). The scrub below
    // is defense in depth, pinned by SentryScrubberResidualsTest
    // and SentryNoUserOrContextAuditTest.
    const val REDACTED = "[REDACTED]"
    const val REDACTED_EMAIL = "[REDACTED_EMAIL]"
    const val REDACTED_TOKEN = "[REDACTED_TOKEN]"
    const val REDACTED_INVITE = "[REDACTED_INVITE]"
    const val REDACTED_AUTHORIZATION = "[REDACTED_AUTHORIZATION]"
    const val REDACTED_PHONE = "[REDACTED_PHONE]"
    const val REDACTED_IP = "[REDACTED_IP]"
    const val MAX_ID_VISIBLE_PREFIX = 4
    const val MAX_ID_VISIBLE_SUFFIX = 2
    const val MIN_ID_LENGTH_TO_TRUNCATE = 20

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val authScheme = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/=]+")
    // A31: `;` joins matrix-style params (`;token=…;other=1`); the value
    // class already stops at `;`, so only the prefix needs it. Key list
    // mirrors isSensitiveKey (plus `code`, which stays query/JSON-only so
    // numeric `code: 7` diagnostics survive key filtering).
    // A40: `nonce`/`challenge` are OIDC exchange secrets (NativeChallenge,
    // NativeExchangeRequest); camelCase `idToken`/`accessToken`/
    // `refreshToken`/`csrfToken`/`deviceId` appear in raw JSON/query text
    // where isSensitiveKey substring matching cannot see them. `id_?token`
    // style covers snake + camel via (?i); bare `csrf` covers the prefix.
    // A43: `verifier`/`code_verifier` are PKCE exchange secrets and `state`
    // is the OIDC CSRF token; all three are exact query/JSON keys so
    // `statement`/`stateFlow` diagnostics survive key filtering.
    // A46: compound keys miss exact matching (`codeVerifier` !=
    // `code_verifier`, `clientSecret` != `secret`, `sessionId` !=
    // `session`, `authToken` != `auth`/`token`, `roomId`/`roomSecret`
    // != `room`/`secret`, `endpointTicket` != `endpoint`/`ticket`,
    // `codeChallenge` != `challenge`). `x_?y` covers snake + camel via
    // (?i) like `refresh_?token`. `state` stays exact-only and `code`
    // stays query/JSON-only per A43.
    // A50: `endpointId`/`peerId` (snake + camel via `x_?y`) are Iroh peer
    // identities; bare `endpoint`/`peer` never match them in free text
    // because the value class requires `=` right after the key.
    private val tokenQuery = Regex("(?i)([?&#;](token|id_?token|access_?token|refresh_?token|csrf_?token|csrf|nonce|challenge|code_?challenge|verifier|code_?verifier|client_?secret|session_?id|auth_?token|room_?id|room_?secret|endpoint_?ticket|endpoint_?id|peer_?id|state|device_?id|invite|code|secret|cookie|session|api_key|apikey|auth|authorization|password|passwd|credential|private_key|privatekey|bearer|ticket|dsn|device|peer|endpoint|room)=)[^&\\s\"';]+")
    private val tokenJson = Regex(
        "(?i)(\"(token|id_?token|access_?token|refresh_?token|csrf_?token|csrf|nonce|challenge|code_?challenge|verifier|code_?verifier|client_?secret|session_?id|auth_?token|room_?id|room_?secret|endpoint_?ticket|endpoint_?id|peer_?id|state|device_?id|authorization|password|passwd|credential|secret|cookie|invite|session|api_key|apikey|auth|private_key|privatekey|bearer|ticket|dsn|device|peer|endpoint|room|code)\"\\s*:\\s*\")[^\"]+\"",
    )
    // A31: single-quoted JSON (`{'token': 'abc'}`) from loose loggers;
    // same key list as tokenJson, quote-agnostic on both key and value.
    private val tokenJsonSingle = Regex(
        "(?i)('(token|id_?token|access_?token|refresh_?token|csrf_?token|csrf|nonce|challenge|code_?challenge|verifier|code_?verifier|client_?secret|session_?id|auth_?token|room_?id|room_?secret|endpoint_?ticket|endpoint_?id|peer_?id|state|device_?id|authorization|password|passwd|credential|secret|cookie|invite|session|api_key|apikey|auth|private_key|privatekey|bearer|ticket|dsn|device|peer|endpoint|room|code)'\\s*:\\s*')[^']+'",
    )
    // A31: invites are `pomodorough1.` + base64url (`A-Za-z0-9_-`); the dot
    // is optional so pre-dot payloads still match. Strictly broader than
    // the old `[A-Za-z0-9]+` tail.
    private val invite = Regex("pomodorough1\\.?[A-Za-z0-9_-]+")
    // A28: direct peers learn IPs and phone numbers can sit in free text
    // where key filtering cannot see them. IPv6 first so mapped
    // `::ffff:1.2.3.4` drops as one unit.
    private val ipv4 = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    private val ipv6 = Regex(
        "(?i)(?<![0-9a-z:.])(?:[0-9a-f]{0,4}:){2,7}(?:[0-9a-f]{0,4}|(?:\\d{1,3}\\.){3}\\d{1,3})(?![0-9a-z:.])",
    )
    private val phoneCandidate = Regex("\\+?[0-9][0-9\\s\\-.()]{7,}[0-9]")
    private val dateLike = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val longId = Regex("(?<!\\[)\\b[A-Za-z0-9_-]{20,}\\b(?!\\])")

    fun scrubText(value: String?): String? {
        if (value == null) return null
        var scrubbed = value
        scrubbed = email.replace(scrubbed, REDACTED_EMAIL)
        scrubbed = authScheme.replace(scrubbed, REDACTED_AUTHORIZATION)
        scrubbed = tokenQuery.replace(scrubbed, "$1$REDACTED_TOKEN")
        scrubbed = tokenJson.replace(scrubbed, "$1$REDACTED_TOKEN\"")
        scrubbed = tokenJsonSingle.replace(scrubbed, "$1$REDACTED_TOKEN'")
        scrubbed = invite.replace(scrubbed, REDACTED_INVITE)
        // Truncation before phone/IP: UUID tails are long digit runs that
        // would otherwise match the phone candidate pattern.
        scrubbed = truncateLongIds(scrubbed)
        scrubbed = scrubPhones(scrubbed)
        scrubbed = ipv6.replace(scrubbed, REDACTED_IP)
        scrubbed = ipv4.replace(scrubbed, REDACTED_IP)
        return scrubbed
    }

    private fun scrubPhones(value: String): String = phoneCandidate.replace(value) { match ->
        val candidate = match.value
        if (isPhoneLike(candidate)) REDACTED_PHONE else candidate
    }

    private fun isPhoneLike(candidate: String): Boolean {
        if (candidate.count(Char::isDigit) < 7) return false
        if (dateLike.matches(candidate)) return false
        return true
    }

    fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
        crumb.message = scrubText(crumb.message)
        val data = crumb.data.toMap()
        data.forEach { (key, value) ->
            if (value == null) return@forEach
            crumb.setData(key, scrubAny(key, value))
        }
        return crumb
    }

    fun scrubEvent(event: SentryEvent): SentryEvent {
        scrubEventMessage(event)
        scrubEventTags(event)
        scrubEventExtras(event)
        scrubEventRequest(event)
        scrubEventUser(event)
        scrubEventExceptions(event)
        scrubEventContexts(event)
        scrubEventThreads(event)
        scrubEventTransaction(event)
        event.breadcrumbs?.forEach(::scrubBreadcrumb)
        return event
    }

    private fun truncateLongIds(value: String): String = longId.replace(value) { match ->
        val id = match.value
        if (id.startsWith("[REDACTED")) id else shortenId(id)
    }

    private fun shortenId(id: String): String {
        if (id.length < MIN_ID_LENGTH_TO_TRUNCATE) return id
        return id.take(MAX_ID_VISIBLE_PREFIX) + "…" + id.takeLast(MAX_ID_VISIBLE_SUFFIX)
    }

    private fun scrubDataValue(key: String, value: String): String? {
        val normalized = key.lowercase()
        if (normalized == "email") return REDACTED_EMAIL
        if (isSensitiveKey(key)) return REDACTED
        return scrubText(value)
    }

    private fun scrubAny(parentKey: String, value: Any?): Any? {
        if (value == null) return null
        // A31: typed contexts survive key filtering; `device` is sensitive
        // as a data key but the typed `device`/`app`/`os` objects must reach
        // their dedicated scrubbers below, not become REDACTED here.
        if (value is Device || value is App || value is OperatingSystem) return value
        if (isSensitiveKey(parentKey)) return REDACTED
        return when (value) {
            is String -> scrubDataValue(parentKey, value) ?: REDACTED
            is Map<*, *> -> value.entries.associate { (childKey, childValue) ->
                val key = childKey.toString()
                key to scrubAny(key, childValue)
            }
            is Iterable<*> -> value.map { scrubAny(parentKey, it) }
            is Array<*> -> value.map { scrubAny(parentKey, it) }
            else -> value
        }
    }

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        // A30: bare `auth` is a substring hit on purpose (covers `auth`,
        // `authToken`, `clientAuth`; also matches `oauth`/`author`
        // fail-closed: hiding a display name beats leaking a token).
        // `private_key`/`privatekey` mirror the `api_key`/`apikey` pair
        // (`privateKey` lowercases to `privatekey`). `bearer`/`ticket`/`dsn`
        // close neighboring gaps (auth headers, Iroh endpoint tickets,
        // Sentry DSNs). `code` stays query/JSON-only: mechanism `code: 7`
        // diagnostics must survive, and substring `code` would also hide
        // `encode`/`codec`/`errorcode`.
        // A31: `device`/`peer`/`endpoint`/`room` are substring hits on
        // purpose. IrohHello carries deviceId/endpointTicket/roomId
        // (identifying or room-access-granting); over-filtering a display
        // string beats leaking a route. `room` covers roomId/roomName.
        // A40: `nonce`/`challenge` are OIDC exchange secrets; `csrf`
        // covers bare csrf plus csrfToken (which also contains `token`).
        // A43: `verifier` is a PKCE exchange secret (substring covers
        // `code_verifier`/`codeVerifier`); `state` is exact-only so
        // `statement`/`stateFlow` diagnostics survive key filtering.
        return normalized.contains("token") ||
            normalized.contains("nonce") ||
            normalized.contains("challenge") ||
            normalized.contains("verifier") ||
            normalized.contains("csrf") ||
            normalized.contains("authorization") ||
            normalized.contains("auth") ||
            normalized.contains("bearer") ||
            normalized.contains("invite") ||
            normalized.contains("ticket") ||
            normalized.contains("dsn") ||
            normalized.contains("cookie") ||
            normalized.contains("secret") ||
            normalized.contains("private_key") ||
            normalized.contains("privatekey") ||
            normalized.contains("session") ||
            normalized.contains("api_key") ||
            normalized.contains("apikey") ||
            normalized.contains("password") ||
            normalized.contains("passwd") ||
            normalized.contains("credential") ||
            normalized.contains("device") ||
            normalized.contains("peer") ||
            normalized.contains("endpoint") ||
            normalized.contains("room") ||
            normalized == "state" ||
            normalized == "email"
    }

    private fun scrubEventMessage(event: SentryEvent) {
        val message: Message? = event.message
        if (message != null) {
            message.message = scrubText(message.message)
            message.formatted = scrubText(message.formatted)
            message.params?.let { params ->
                message.params = params.map { scrubText(it) ?: REDACTED }
            }
        }
    }

    private fun scrubEventTags(event: SentryEvent) {
        // A31: this is the only app `setTag` write; values pass through
        // scrubDataValue so sensitive keys stay opaque. No production file
        // outside `crash/` calls `setTag`/`configureScope` (pinned by
        // SentryNoUserOrContextAuditTest); the SDK never adds tags itself.
        event.tags?.toMap()?.forEach { (key, value) ->
            event.setTag(key, scrubDataValue(key, value) ?: REDACTED)
        }
    }

    private fun scrubEventExtras(event: SentryEvent) {
        event.extras?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            event.setExtra(key, scrubAny(key, value))
        }
    }

    private fun scrubEventExceptions(event: SentryEvent) {
        event.exceptions?.forEach { exception ->
            exception.value = scrubText(exception.value)
            exception.module = scrubText(exception.module)
            exception.stacktrace?.let(::scrubStackTrace)
            exception.mechanism?.let(::scrubMechanism)
        }
    }

    private fun scrubStackTrace(trace: SentryStackTrace) {
        trace.frames?.forEach(::scrubFrame)
        trace.registers?.toMap()?.forEach { (key, value) ->
            trace.registers = trace.registers?.toMutableMap()?.also {
                it[key] = scrubText(value) ?: REDACTED
            }
        }
    }

    private fun scrubFrame(frame: SentryStackFrame) {
        frame.filename = scrubText(frame.filename)
        frame.absPath = scrubText(frame.absPath)
        frame.contextLine = scrubText(frame.contextLine)
        frame.function = scrubText(frame.function)
        frame.module = scrubText(frame.module)
        frame.`package` = scrubText(frame.`package`)
        frame.preContext = frame.preContext?.map { scrubText(it) ?: REDACTED }
        frame.postContext = frame.postContext?.map { scrubText(it) ?: REDACTED }
        frame.vars?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            frame.vars = frame.vars?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubMechanism(mechanism: Mechanism) {
        mechanism.description = scrubText(mechanism.description)
        mechanism.helpLink = scrubText(mechanism.helpLink)
        mechanism.data?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            mechanism.data = mechanism.data?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubEventContexts(event: SentryEvent) {
        val contexts = event.contexts ?: return
        contexts.entrySet().toList().forEach { entry ->
            val key = entry.key
            val value = entry.value ?: return@forEach
            val scrubbed = scrubAny(key, value)
            if (scrubbed !== value) contexts.put(key, scrubbed)
        }
        contexts.device?.let(::scrubDevice)
        contexts.app?.let(::scrubApp)
        contexts.operatingSystem?.let(::scrubOperatingSystem)
    }

    private fun scrubDevice(device: Device) {
        device.name = scrubText(device.name)
        device.id = scrubText(device.id)
        device.locale = scrubText(device.locale)
        // A29: identifying hardware strings are user-visible in crash
        // reports; truncation alone leaks prefix/suffix, so scrub them.
        // A31: model strings can embed invites/tokens/IPs/phones from
        // pairing logs; scrubText keeps clean models (`Pixel 8`) while
        // redacting embedded secrets. `device`/`model` keys stay opaque
        // via isSensitiveKey when they appear as data keys.
        device.manufacturer = scrubText(device.manufacturer)
        device.brand = scrubText(device.brand)
        device.family = scrubText(device.family)
        device.model = scrubText(device.model)
        device.modelId = scrubText(device.modelId)
        device.archs = device.archs?.map { scrubText(it) ?: REDACTED }?.toTypedArray()
        device.unknown?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            device.unknown = device.unknown?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubApp(app: App) {
        app.viewNames = app.viewNames?.map { scrubText(it) ?: REDACTED }
        app.unknown?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            app.unknown = app.unknown?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubOperatingSystem(os: OperatingSystem) {
        os.name = scrubText(os.name)
        os.version = scrubText(os.version)
        os.build = scrubText(os.build)
        os.kernelVersion = scrubText(os.kernelVersion)
        os.rawDescription = scrubText(os.rawDescription)
        os.unknown?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            os.unknown = os.unknown?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubEventThreads(event: SentryEvent) {
        event.threads?.forEach { thread ->
            thread.name = scrubText(thread.name)
            thread.stacktrace?.let(::scrubStackTrace)
        }
    }

    private fun scrubEventTransaction(event: SentryEvent) {
        event.transaction = scrubText(event.transaction)
    }

    private fun scrubEventRequest(event: SentryEvent) {
        val request = event.request ?: return
        request.url = scrubText(request.url)
        request.queryString = scrubText(request.queryString)
        request.cookies = scrubText(request.cookies)
        request.fragment = request.fragment?.let { scrubText("?$it")?.removePrefix("?") }
        request.data = scrubAny("data", request.data)
        request.headers?.toMap()?.forEach { (key, value) ->
            request.headers = request.headers?.toMutableMap()?.also {
                it[key] = scrubDataValue(key, value) ?: REDACTED
            }
        }
        request.envs?.toMap()?.forEach { (key, value) ->
            request.envs = request.envs?.toMutableMap()?.also {
                it[key] = scrubDataValue(key, value) ?: REDACTED
            }
        }
        request.others?.toMap()?.forEach { (key, value) ->
            request.others = request.others?.toMutableMap()?.also {
                it[key] = scrubDataValue(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubEventUser(event: SentryEvent) {
        val user = event.user ?: return
        user.email = REDACTED_EMAIL
        user.username = user.username?.let { scrubText(it) }
        user.id = user.id?.let { scrubText(it) }
        user.ipAddress = REDACTED
        user.name = user.name?.let { scrubText(it) }
        user.geo?.let(::scrubGeo)
        user.data?.toMap()?.forEach { (key, value) ->
            user.data = user.data?.toMutableMap()?.also {
                it[key] = scrubDataValue(key, value) ?: REDACTED
            }
        }
        user.unknown?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            user.unknown = user.unknown?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }

    private fun scrubGeo(geo: Geo) {
        // A28: city/region are precise location even when clean; drop them
        // instead of passing them through free-text scrubbing.
        if (geo.city != null) geo.city = REDACTED
        if (geo.region != null) geo.region = REDACTED
        geo.countryCode = scrubText(geo.countryCode)
        geo.unknown?.toMap()?.forEach { (key, value) ->
            if (value == null) return@forEach
            geo.unknown = geo.unknown?.toMutableMap()?.also {
                it[key] = scrubAny(key, value) ?: REDACTED
            }
        }
    }
}
