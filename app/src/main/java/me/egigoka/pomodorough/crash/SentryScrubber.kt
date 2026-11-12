package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.JsonSerializable
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

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
    // R54-L03: matches desktop `_SCRUB_DEPTH_LIMIT`. A cyclic or very deep
    // value would overflow the stack or multiply work, and the SDK drops an
    // event whose beforeSend throws, so such values are redacted instead.
    private const val SCRUB_DEPTH_LIMIT = 20
    // R54-L03: the query parts desktop `_URL_PART_SEPARATORS` names. A list
    // under one of these keys holds `[key, value]` pairs.
    private val queryPartKeys = setOf("http.query", "http.fragment", "query_string")
    // R54-L06: desktop `_CANONICAL_QUERY_KEY_RE`. Only a key in this charset
    // names its query value; any other key can be stripped or rejoined by a
    // parser (`to\tken=`) or decode to a secret name.
    private val canonicalQueryKey = Regex("[A-Za-z0-9_.~-]+")

    // R54-L14: the address part is optional, so each local-part run matches
    // once, whole, and scrubText keeps a run that has no address. The old form
    // retried every later start inside a run without `@`, which took seconds
    // on long text. The run is possessive, as in desktop `_EMAIL_RE`. Only a
    // match with the address part redacts, and the redacted addresses are the
    // same: `@` is outside the run class, so it can only follow a whole run.
    // R54-L16: stop the local run before an encoded @, including nested %25.
    // The lookahead scans beyond one character only at %, preserving linear work.
    private val literalEmail = Regex("[A-Za-z0-9._%+-]++(@[A-Za-z0-9.-]+\\.[A-Za-z]{2,})?")
    private val email = Regex(
        "(?:(?!%(?:25)*+40)[A-Za-z0-9._%+-])++((?:@|%(?:25)*+40)[A-Za-z0-9.-]+\\.[A-Za-z]{2,})?",
    )
    private val authScheme = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/=]+")
    // A27 through A74 kept fixed key lists for free-text `?key=` values and
    // `"key": "…"` or `'key': '…'` JSON strings, and every compound form
    // (`clientSecret`, `client-sid`, `auth_token`) needed its own entry.
    // R54-L11: free text now asks the key rule that maps use, through
    // namesSecretValue, so a compound key such as `clientToken` redacts as
    // its map key does and the two cannot drift apart. Each regex below
    // matches only a key position; a value is read only once the key names
    // a secret. A query value ends at `&`, a quote, `;` or whitespace, as
    // before, so it still covers a `?` or `#` inside the value.
    private val textQueryKey = Regex("[?&#;]([A-Za-z0-9_.~-]+)=")
    private val textQueryValue = Regex("[^&\\s\"';]+")
    // R54-L11 rework: a JSON key holds no quote of either style, as no key in
    // the A27 lists did. A pair of one style written inside a key of the
    // other style (`"'sid':'…":"'"`) is then read as its own pair, and the
    // outer key cannot take its value.
    private val textJsonKey = Regex("\"([^\"']+)\"\\s*:\\s*\"")
    private val textJsonValue = Regex("[^\"]+(?=\")")
    // A31: single-quoted JSON (`{'token': 'abc'}`) from loose loggers.
    private val textJsonSingleKey = Regex("'([^\"']+)'\\s*:\\s*'")
    private val textJsonSingleValue = Regex("[^']+(?=')")
    private val textJsonRules = listOf(
        KeyedValueRule(textJsonKey, textJsonValue),
        KeyedValueRule(textJsonSingleKey, textJsonSingleValue),
    )
    // R54-L11 rework: the L06 JSON rules stay unchanged and run before the
    // key rule. With both quote styles in one text, the key rule can read a
    // pair inside a value of the other style, or read a value as a key, and
    // then stop at a different quote than these rules do. Running these first
    // keeps every value L06 hid hidden, and the key rule only adds
    // redactions. Do not extend these lists; a new key goes to isSensitiveKey.
    private val tokenJson = Regex(
        "(?i)(\"(token|id[-_]?token|access[-_]?token|refresh[-_]?token|csrf[-_]?token|csrf|nonce|challenge|code[-_]?challenge|verifier|code[-_]?verifier|client[-_]?secret|session[-_]?id|[a-z0-9_-]*ssid|[a-z0-9_-]*sid|auth[-_]?token|room[-_]?id|room[-_]?secret|endpoint[-_]?ticket|endpoint[-_]?id|peer[-_]?id|state|device[-_]?id|authorization|password|passwd|credential|secret|cookie|invite|session|api_key|apikey|auth|private_key|privatekey|bearer|ticket|dsn|device|peer|endpoint|room|code)\"\\s*:\\s*\")[^\"]+\"",
    )
    // A31: single-quoted JSON (`{'token': 'abc'}`) from loose loggers;
    // same key list as tokenJson, quote-agnostic on both key and value.
    private val tokenJsonSingle = Regex(
        "(?i)('(token|id[-_]?token|access[-_]?token|refresh[-_]?token|csrf[-_]?token|csrf|nonce|challenge|code[-_]?challenge|verifier|code[-_]?verifier|client[-_]?secret|session[-_]?id|[a-z0-9_-]*ssid|[a-z0-9_-]*sid|auth[-_]?token|room[-_]?id|room[-_]?secret|endpoint[-_]?ticket|endpoint[-_]?id|peer[-_]?id|state|device[-_]?id|authorization|password|passwd|credential|secret|cookie|invite|session|api_key|apikey|auth|private_key|privatekey|bearer|ticket|dsn|device|peer|endpoint|room|code)'\\s*:\\s*')[^']+'",
    )
    private val textQueryRules = listOf(KeyedValueRule(textQueryKey, textQueryValue))
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
    private val requestParameter = Regex("(^[?#]?|[&#;])([^&#;]*)")
    // R54-L10: desktop `_scrub_urls` applies the query key rule after every
    // `?`, `&`, `#` or `;` in free text, up to the next separator.
    private val textQueryParameter = Regex("[?&#;]([^?&#;]*)")

    fun scrubText(value: String?): String? {
        if (value == null) return null
        // R54-L10: JSON strings go first. A quote ends a JSON value but not a
        // query parameter, so the key rule would otherwise take the closing
        // quote into a redacted parameter, and the JSON rule could no longer
        // find the value it hides.
        var scrubbed = tokenJson.replace(value, "$1$REDACTED_TOKEN\"")
        scrubbed = tokenJsonSingle.replace(scrubbed, "$1$REDACTED_TOKEN'")
        scrubbed = scrubKeyedValues(scrubbed, textJsonRules)
        // Literal addresses may contain %40 in their local part. Hide the whole
        // literal address first, before interpreting an encoded delimiter.
        scrubbed = literalEmail.replace(scrubbed) { if (it.groups[1] == null) it.value else REDACTED_EMAIL }
        scrubbed = email.replace(scrubbed) { if (it.groups[1] == null) it.value else REDACTED_EMAIL }
        scrubbed = authScheme.replace(scrubbed, REDACTED_AUTHORIZATION)
        // R54-L10: the key rule runs before the value rule, which would
        // otherwise take a later parameter into a value and hide its key.
        // Email and auth matches never cross a separator, and a key they
        // rewrite was already outside the canonical charset, so running them
        // first redacts the same parameters. Both rules then read a value as
        // the value rule did before L10.
        scrubbed = scrubTextQueryKeys(scrubbed)
        scrubbed = scrubKeyedValues(scrubbed, textQueryRules)
        scrubbed = invite.replace(scrubbed, REDACTED_INVITE)
        // Truncation before phone/IP: UUID tails are long digit runs that
        // would otherwise match the phone candidate pattern.
        scrubbed = truncateLongIds(scrubbed)
        scrubbed = scrubPhones(scrubbed)
        scrubbed = ipv6.replace(scrubbed, REDACTED_IP)
        scrubbed = ipv4.replace(scrubbed, REDACTED_IP)
        return scrubbed
    }

    // R54-L10: free text never decodes, so the raw key takes the request key
    // rule. An escape or `+` in a raw key is outside the canonical charset and
    // redacts the parameter, as desktop filters every escape in free text.
    // R54-L10 rework: the redaction also covers a secret value as far as the
    // value rule reads it, past `#` and `?`. The parameter alone stops there,
    // so its key would be gone while the value's tail stayed in the text.
    private fun scrubTextQueryKeys(value: String): String {
        val scrubbed = StringBuilder()
        var copied = 0
        var parameter = textQueryParameter.find(value)
        while (parameter != null) {
            val rawKey = parameter.groupValues[1].substringBefore('=')
            if (isPrivateRequestKey(parameter.groupValues[1], rawKey)) {
                scrubbed.append(value, copied, parameter.range.first + 1).append(REDACTED)
                copied = maxOf(parameter.range.last + 1, secretValueEnd(value, parameter, rawKey))
                parameter = textQueryParameter.find(value, copied)
            } else {
                parameter = parameter.next()
            }
        }
        return scrubbed.append(value, copied, value.length).toString()
    }

    // The end of the value that the value rule would redact after this
    // parameter's key, or 0 when it redacts none: the key is outside the
    // canonical charset, names no secret, or has an empty value.
    private fun secretValueEnd(text: String, parameter: MatchResult, rawKey: String): Int {
        if (!canonicalQueryKey.matches(rawKey) || !namesSecretValue(rawKey)) return 0
        val valueStart = parameter.range.first + rawKey.length + 2
        return textQueryValue.matchAt(text, valueStart)?.let { it.range.last + 1 } ?: 0
    }

    // R54-L11: a declined key resumes the search one character later, as a
    // key alternation would, so a key that starts inside it is still found.
    // The value is read only after the key names a secret: a query value can
    // run to the end of the text, so reading it for every declined key would
    // make the scan quadratic. A redacted value is consumed whole, as the
    // replaced regex consumed it.
    // R54-L11 rework: the rules share one left-to-right scan, and the
    // earliest key of any rule is read first. A value of one quote style
    // then cannot take the closing quote of an earlier value of the other
    // style, as it could when each style had its own pass.
    private fun scrubKeyedValues(text: String, rules: List<KeyedValueRule>): String {
        val scrubbed = StringBuilder()
        var copied = 0
        val keys = rules.mapTo(mutableListOf()) { it.key.find(text) }
        while (true) {
            val index = keys.indices.minByOrNull { keys[it]?.range?.first ?: Int.MAX_VALUE } ?: break
            val keyMatch = keys[index] ?: break
            val rule = rules[index]
            val secret = keyMatch.takeIf { namesSecretValue(it.groupValues[1]) }
                ?.let { rule.value.matchAt(text, it.range.last + 1) }
            if (secret == null) {
                keys[index] = rule.key.find(text, keyMatch.range.first + 1)
                continue
            }
            scrubbed.append(text, copied, secret.range.first).append(REDACTED_TOKEN)
            copied = secret.range.last + 1
            for (next in keys.indices) {
                val start = keys[next]?.range?.first ?: continue
                if (start < copied) keys[next] = rules[next].key.find(text, copied)
            }
        }
        return scrubbed.append(text, copied, text.length).toString()
    }

    /** A key position, and the value that follows it when the key names a secret. */
    private class KeyedValueRule(val key: Regex, val value: Regex)

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
        // R54-L03: `data` is the breadcrumb's live map, which holds no null
        // values. Clearing it drops every raw key, whatever its type, before
        // the scrubbed entries return under policy-safe names.
        val cleaned = scrubObjectMap(crumb.data.toMap())
        crumb.data.clear()
        cleaned.forEach { (key, value) -> if (value != null) crumb.setData(key, value) }
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
        // R54-L06: breadcrumb and extras query parts are recorded without
        // `?` or `#`, so they take the decoding request rule instead of free
        // text, which only sees a `[?&#;]token=` prefix.
        if (key in queryPartKeys) return scrubRequestParameters(value)
        return scrubText(value)
    }

    // R54-L03: every map the scrubber keeps applies the shared key policy,
    // so an unsafe name becomes a numbered placeholder with a redacted value.
    private fun scrubObjectMap(entries: Map<*, Any?>): Map<String, Any?> =
        SentryKeyPolicy.scrubKeys(entries, REDACTED) { key, value -> scrubAny(key, value) }

    private fun scrubTextMap(entries: Map<*, String?>): Map<String, String?> =
        SentryKeyPolicy.scrubKeys(entries, REDACTED) { key, value ->
            value?.let { scrubDataValue(key, it) ?: REDACTED }
        }

    // `User.data` declares its values @NotNull, so this variant never yields null.
    private fun scrubUserData(entries: Map<*, String>): Map<String, String> =
        SentryKeyPolicy.scrubKeys(entries, REDACTED) { key, value -> scrubDataValue(key, value) ?: REDACTED }

    // R54-L03: `ancestors` holds the containers above `value` by identity,
    // so a cycle is redacted where it closes instead of being walked again.
    private fun scrubAny(parentKey: String, value: Any?, ancestors: List<Any> = emptyList()): Any? {
        if (value == null) return null
        if (isSensitiveKey(parentKey)) return REDACTED
        if (ancestors.size > SCRUB_DEPTH_LIMIT || ancestors.any { it === value }) return REDACTED
        return scrubValueShape(parentKey, value, ancestors + value)
    }

    private fun scrubValueShape(parentKey: String, value: Any, path: List<Any>): Any? = when (value) {
        is String -> scrubDataValue(parentKey, value) ?: REDACTED
        is Number, is Boolean -> value
        is Enum<*> -> value.name
        is Map<*, *> -> scrubEntries(parentKey, value, path)
        is Iterable<*> -> scrubItems(parentKey, value.toList(), path)
        is Array<*> -> scrubItems(parentKey, value.asList(), path)
        // R54-L03: any other object would reach the SDK reflection
        // serializer, which writes its field names and map keys verbatim.
        // Typed contexts are kept only at the top level of `contexts`.
        else -> REDACTED
    }

    // R54-L06: a query map's values follow the pair rule, as desktop
    // `_scrub_mapping(value, depth, _scrub_query_parameter_value)` does.
    private fun scrubEntries(parentKey: String, entries: Map<*, *>, path: List<Any>): Map<String, Any?> {
        val queryPart = parentKey in queryPartKeys
        return SentryKeyPolicy.scrubKeys(entries, REDACTED) { key, child ->
            if (queryPart) scrubQueryParameterValue(key, child, path) else scrubAny(key, child, path)
        }
    }

    // R54-L06: desktop `_scrub_query_parameter_value`. The empty key passes
    // the key policy but names no parameter, so its value stays redacted.
    private fun scrubQueryParameterValue(key: String, value: Any?, path: List<Any>): Any? =
        if (canonicalQueryKey.matches(key)) scrubAny(key, value, path) else REDACTED

    // R54-L03: a query pair key follows the map key rule, with placeholders
    // numbered within its list (desktop `_scrub_query_entry`). Any other
    // entry can be one half of a flattened pair (`["token", secret]`), so it
    // stays redacted.
    private fun scrubItems(parentKey: String, items: List<*>, path: List<Any>): List<Any?> {
        if (parentKey !in queryPartKeys) return items.map { scrubAny(parentKey, it, path) }
        var redactedKeys = 0
        return items.map { entry ->
            val pair = ((entry as? List<*>) ?: (entry as? Array<*>)?.asList())?.takeIf { it.size == 2 }
            val key = pair?.first()
            when {
                pair == null -> REDACTED
                key is String && SentryKeyPolicy.isDiagnosticName(key) ->
                    listOf(key, scrubQueryParameterValue(key, pair[1], path.plusElement(pair)))
                else -> listOf(SentryKeyPolicy.redactedKey(++redactedKeys), REDACTED)
            }
        }
    }

    // The substring hits of isSensitiveKey; see the A30 through A43 notes
    // there. `session`/`ssid`/`sid` are in isSessionIdentifierKey.
    private val sensitiveKeyParts = listOf(
        "token", "nonce", "challenge", "verifier", "csrf", "authorization", "auth", "bearer", "invite",
        "ticket", "dsn", "cookie", "secret", "private_key", "privatekey", "api_key", "apikey", "password",
        "passwd", "credential", "device", "peer", "endpoint", "room",
    )

    // R54-L11: desktop `_CODE_SUFFIX_ALLOWLIST` and `_KEY_SUFFIX_ALLOWLIST`
    // (singular forms; plurals match too). `keyboard` never ends with `key`.
    // `status_code` is Android-only: the SDK HTTP breadcrumb field keeps its
    // value, as R54L03KeyPrivacyTest pins. Desktop filters it.
    private val codeSuffixAllowlist = setOf("errorcode", "statuscode", "exitcode", "status_code")
    private val keySuffixAllowlist = listOf("monkey", "donkey", "turkey", "jockey", "whiskey", "hockey")

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
        // R54-L11: desktop `_sensitive_key` also names `*code`, `*key`,
        // bare `value`, and signature keys.
        return sensitiveKeyParts.any(normalized::contains) ||
            isSessionIdentifierKey(normalized) ||
            isCodeSuffixKey(normalized) ||
            isKeySuffixKey(normalized) ||
            isSignatureKey(normalized) ||
            normalized == "state" ||
            normalized == "email" ||
            normalized == "value"
    }

    // R54-L11: desktop `_is_code_key` without its exact `code`, which stays
    // out of map keys (A30).
    private fun isCodeSuffixKey(normalized: String): Boolean {
        if (normalized == "code" || !normalized.endsWith("code")) return false
        if (normalized in codeSuffixAllowlist) return false
        // Codec operations share the suffix but carry no secret.
        return !normalized.endsWith("encode") && !normalized.endsWith("decode") && "codec" !in normalized
    }

    // R54-L11: desktop `_is_key_suffix` (D39). Exact or suffix `key` and
    // `keys` cover `clientKey`, `signingKey`, and `private_key` in one rule;
    // `monkey`-class words carry no secret. `publicKey` stays filtered
    // fail-closed, as on desktop.
    private fun isKeySuffixKey(normalized: String): Boolean {
        if (normalized == "key" || normalized == "keys") return true
        if (!normalized.endsWith("key") && !normalized.endsWith("keys")) return false
        return keySuffixAllowlist.none { normalized.endsWith(it) || normalized.endsWith(it + "s") }
    }

    // R54-L11: desktop `_is_signature_key`. Signed URLs carry their
    // credential in `sig` (Azure SAS), `Signature` (GCS, CloudFront), or
    // `X-Amz-Signature` (S3 SigV4). Exact `sig` keeps `signal` and `design`.
    private fun isSignatureKey(normalized: String): Boolean =
        normalized == "sig" || normalized.endsWith("signature") || normalized.endsWith("signatures")

    // R54-L11: free text and request queries name a secret by the map key
    // rule, or by exact `code`. `code` stays out of map keys so mechanism
    // `code: 7` diagnostics survive key filtering.
    private fun namesSecretValue(key: String): Boolean =
        isSensitiveKey(key) || key.equals("code", ignoreCase = true)

    // A59: `session`/`ssid`/`sid` are session identifiers (mirror desktop
    // D51). Substring on purpose (`ssid` contains `sid`); over-filtering a
    // display string (`reside`, `consider`) beats leaking a session, the
    // same tradeoff desktop accepts. R54-L11: free-text query and JSON keys
    // now take the same rule, as desktop free text does.
    private fun isSessionIdentifierKey(normalized: String): Boolean =
        normalized.contains("session") ||
            normalized.contains("ssid") ||
            normalized.contains("sid")

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
        event.tags?.let { tags -> event.tags = scrubTextMap(tags) }
    }

    private fun scrubEventExtras(event: SentryEvent) {
        event.extras?.let { extras -> event.extras = scrubObjectMap(extras) }
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
        trace.registers = trace.registers?.let { registers ->
            SentryKeyPolicy.scrubKeys(registers, REDACTED) { _, value -> scrubText(value) ?: REDACTED }
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
        frame.vars = frame.vars?.let(::scrubObjectMap)
    }

    private fun scrubMechanism(mechanism: Mechanism) {
        mechanism.description = scrubText(mechanism.description)
        mechanism.helpLink = scrubText(mechanism.helpLink)
        mechanism.data = mechanism.data?.let(::scrubObjectMap)
    }

    private fun scrubEventContexts(event: SentryEvent) {
        val contexts = event.contexts ?: return
        // R54-L03: contexts hold no null values, so every raw name is removed
        // before the scrubbed entries return under policy-safe names.
        val raw = contexts.entrySet().associate { it.key to it.value }
        val cleaned = SentryKeyPolicy.scrubKeys(raw, REDACTED, ::scrubContextValue)
        raw.keys.forEach { contexts.remove(it) }
        cleaned.forEach { (name, value) -> if (value != null) contexts.put(name, value) }
        contexts.device?.let(::scrubDevice)
        contexts.app?.let(::scrubApp)
        contexts.operatingSystem?.let(::scrubOperatingSystem)
    }

    // A31: typed contexts survive key filtering; `device` is sensitive as a
    // data key, but the typed `device`/`app`/`os` objects must reach their
    // dedicated scrubbers below, not become REDACTED here.
    // R54-L03: every typed SDK context (trace, runtime, gpu, device, app,
    // os) keeps its own serializer; untyped context values are scrubbed.
    private fun scrubContextValue(name: String, value: Any?): Any? =
        if (value is JsonSerializable) value else scrubAny(name, value)

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
        device.unknown = device.unknown?.let(::scrubObjectMap)
    }

    private fun scrubApp(app: App) {
        app.viewNames = app.viewNames?.map { scrubText(it) ?: REDACTED }
        app.unknown = app.unknown?.let(::scrubObjectMap)
    }

    private fun scrubOperatingSystem(os: OperatingSystem) {
        os.name = scrubText(os.name)
        os.version = scrubText(os.version)
        os.build = scrubText(os.build)
        os.kernelVersion = scrubText(os.kernelVersion)
        os.rawDescription = scrubText(os.rawDescription)
        os.unknown = os.unknown?.let(::scrubObjectMap)
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
        request.url = scrubRequestUrl(request.url)
        request.queryString = scrubRequestParameters(request.queryString)
        request.cookies = null
        request.fragment = scrubRequestParameters(request.fragment)
        request.data = scrubAny("data", request.data)
        // R54-L03: the key policy runs first, so the cookie filter below only
        // ever reads String names.
        request.headers = request.headers?.let(::scrubTextMap)?.filterKeys {
            !it.equals("Cookie", ignoreCase = true) && !it.equals("Set-Cookie", ignoreCase = true)
        }
        request.envs = request.envs?.let(::scrubTextMap)
        request.others = request.others?.let(::scrubTextMap)
    }

    private fun scrubRequestUrl(value: String?): String? {
        if (value == null) return null
        val queryStart = value.indexOfAny(charArrayOf('?', '#'))
        if (queryStart < 0) return scrubText(value)
        return scrubText(value.substring(0, queryStart)) +
            scrubRequestParameters(value.substring(queryStart))
    }

    private fun scrubRequestParameters(value: String?): String? = value?.let {
        requestParameter.replace(it) { parameter ->
            parameter.groupValues[1] + scrubRequestParameter(parameter.groupValues[2])
        }
    }

    private fun scrubRequestParameter(parameter: String): String {
        return try {
            val rawKey = parameter.substringBefore('=')
            val decodedKey = fullyDecodeRequestComponent(rawKey)
            if (isAmbiguousRequestKey(decodedKey)) return REDACTED
            if (isPrivateRequestKey(parameter, decodedKey)) return REDACTED
            if (namesSecretValue(decodedKey)) {
                if (rawKey.contains("%25", ignoreCase = true)) return REDACTED
                return "$rawKey=$REDACTED_TOKEN"
            }
            val decoded = fullyDecodeRequestComponent(parameter)
            val scrubbed = scrubText(decoded) ?: REDACTED
            if (scrubbed == decoded) parameter else scrubbed
        } catch (_: IllegalArgumentException) {
            // expected-silent: malformed query input is redacted, never reported
            // from inside the crash scrubber, which could leak or recurse.
            REDACTED
        }
    }

    // F6: a decoded key holding `=&#;?`, space, or `%` hides structure
    // (`token%3Dsecret` decodes to `token=secret`). Returning the raw key
    // beside redaction would preserve the smuggled assignment, so the whole
    // parameter is redacted instead. Single-encoding keeps F4/F6 key
    // preservation (`%74oken`); `%25` proves repeated encoding, also redacted.
    private fun isAmbiguousRequestKey(decodedKey: String): Boolean {
        return decodedKey.any { it == '=' || it == '&' || it == '#' || it == ';' || it == '?' } ||
            decodedKey.contains(' ') ||
            decodedKey.contains('%')
    }

    // R54-L06: desktop keeps a decoded key only in the canonical charset. A
    // tab, control, quote, bracket, `@`, or non-ASCII letter before `=` hides
    // the real name. A key that free text would rewrite (an invite, phone,
    // or long id) is a private name. Either way the whole keyed parameter is
    // redacted, which also keeps a second scrub pass from changing it again.
    // A valueless part (`#/settings`) and the empty key (`=v`) keep the text
    // rule, as on desktop.
    private fun isPrivateRequestKey(parameter: String, decodedKey: String): Boolean {
        if ('=' !in parameter || decodedKey.isEmpty()) return false
        return !canonicalQueryKey.matches(decodedKey) || scrubText(decodedKey) != decodedKey
    }

    private fun fullyDecodeRequestComponent(value: String): String {
        var current = value
        repeat(5) {
            val next = strictDecodeRequestComponent(current)
            if (next == current) return current
            current = next
        }
        throw IllegalArgumentException("repeated percent encoding")
    }

    // F6: URLDecoder replaces malformed UTF-8 with U+FFFD instead of throwing,
    // so `%FF` survived when decoded text matched scrubbed text. Strict bytes
    // plus REPORT fail-closed: malformed sequences throw and the caller
    // redacts the complete affected parameter.
    private fun strictDecodeRequestComponent(value: String): String {
        val out = StringBuilder(value.length)
        val pending = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            when (val c = value[i]) {
                '+' -> {
                    appendPendingRequestBytes(pending, out)
                    out.append(' ')
                    i++
                }
                '%' -> {
                    if (i + 2 >= value.length) throw IllegalArgumentException("incomplete percent")
                    val hi = Character.digit(value[i + 1], 16)
                    val lo = Character.digit(value[i + 2], 16)
                    if (hi < 0 || lo < 0) throw IllegalArgumentException("non-hex percent")
                    pending.write((hi shl 4) + lo)
                    i += 3
                }
                else -> {
                    appendPendingRequestBytes(pending, out)
                    out.append(c)
                    i++
                }
            }
        }
        appendPendingRequestBytes(pending, out)
        return out.toString()
    }

    private fun appendPendingRequestBytes(pending: ByteArrayOutputStream, out: StringBuilder) {
        if (pending.size() == 0) return
        val bytes = pending.toByteArray()
        pending.reset()
        try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            out.append(decoder.decode(ByteBuffer.wrap(bytes)).toString())
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("malformed UTF-8", error)
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
        user.data = user.data?.let(::scrubUserData)
        user.unknown = user.unknown?.let(::scrubObjectMap)
    }

    private fun scrubGeo(geo: Geo) {
        // A28: city/region are precise location even when clean; drop them
        // instead of passing them through free-text scrubbing.
        if (geo.city != null) geo.city = REDACTED
        if (geo.region != null) geo.region = REDACTED
        geo.countryCode = scrubText(geo.countryCode)
        geo.unknown = geo.unknown?.let(::scrubObjectMap)
    }
}
