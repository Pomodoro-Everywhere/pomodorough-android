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
    // device hardware strings beyond truncation. The app never calls
    // Sentry.setUser and never puts invites, tokens, or emails into
    // contexts; Device/App/Os values come from the Sentry Android SDK
    // (hardware model, OS version, app build). The scrub below is defense
    // in depth, pinned by SentryScrubberResidualsTest
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
    private val tokenQuery = Regex("(?i)([?&#](token|id_token|access_token|refresh_token|invite|code|secret|cookie|session|api_key|apikey)=)[^&\\s\"';]+")
    private val tokenJson = Regex(
        "(?i)(\"(token|id_token|access_token|refresh_token|authorization|password|passwd|credential|secret|cookie|invite|session|api_key|apikey)\"\\s*:\\s*\")[^\"]+\"",
    )
    private val invite = Regex("pomodorough1[A-Za-z0-9]+")
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
        return normalized.contains("token") ||
            normalized.contains("authorization") ||
            normalized.contains("invite") ||
            normalized.contains("cookie") ||
            normalized.contains("secret") ||
            normalized.contains("session") ||
            normalized.contains("api_key") ||
            normalized.contains("apikey") ||
            normalized.contains("password") ||
            normalized.contains("passwd") ||
            normalized.contains("credential") ||
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
