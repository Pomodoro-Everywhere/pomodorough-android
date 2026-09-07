package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message

object SentryScrubber {
    const val REDACTED = "[REDACTED]"
    const val REDACTED_EMAIL = "[REDACTED_EMAIL]"
    const val REDACTED_TOKEN = "[REDACTED_TOKEN]"
    const val REDACTED_INVITE = "[REDACTED_INVITE]"
    const val REDACTED_AUTHORIZATION = "[REDACTED_AUTHORIZATION]"
    const val MAX_ID_VISIBLE_PREFIX = 8
    const val MAX_ID_VISIBLE_SUFFIX = 4
    const val MIN_ID_LENGTH_TO_TRUNCATE = 20

    private val email = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    private val authScheme = Regex("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9\\-._~+/=]+")
    private val tokenQuery = Regex("(?i)([?&](token|id_token|access_token|refresh_token)=)[^&\\s]+")
    private val tokenJson = Regex(
        "(?i)(\"(token|id_token|access_token|refresh_token|authorization)\"\\s*:\\s*\")[^\"]+\"",
    )
    private val invite = Regex("pomodorough1[A-Za-z0-9]+")
    private val longId = Regex("(?<!\\[)\\b[A-Za-z0-9_-]{20,}\\b(?!\\])")

    fun scrubText(value: String?): String? {
        if (value == null) return null
        var scrubbed = value
        scrubbed = email.replace(scrubbed, REDACTED_EMAIL)
        scrubbed = authScheme.replace(scrubbed, REDACTED_AUTHORIZATION)
        scrubbed = tokenQuery.replace(scrubbed, "$1$REDACTED_TOKEN")
        scrubbed = tokenJson.replace(scrubbed, "$1$REDACTED_TOKEN\"")
        scrubbed = invite.replace(scrubbed, REDACTED_INVITE)
        return truncateLongIds(scrubbed)
    }

    fun scrubBreadcrumb(crumb: Breadcrumb): Breadcrumb {
        crumb.message = scrubText(crumb.message)
        val data = crumb.data.toMap()
        data.forEach { (key, value) ->
            if (value is String) crumb.setData(key, scrubDataValue(key, value))
        }
        return crumb
    }

    fun scrubEvent(event: SentryEvent): SentryEvent {
        scrubEventMessage(event)
        scrubEventTags(event)
        scrubEventExtras(event)
        scrubEventRequest(event)
        scrubEventUser(event)
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

    private fun isSensitiveKey(key: String): Boolean {
        val normalized = key.lowercase()
        return normalized.contains("token") ||
            normalized.contains("authorization") ||
            normalized.contains("invite") ||
            normalized.contains("cookie") ||
            normalized.contains("secret") ||
            normalized == "email"
    }

    private fun scrubEventMessage(event: SentryEvent) {
        val message: Message? = event.message
        if (message != null) {
            message.formatted = scrubText(message.formatted)
        }
    }

    private fun scrubEventTags(event: SentryEvent) {
        event.tags?.toMap()?.forEach { (key, value) ->
            event.setTag(key, scrubDataValue(key, value) ?: REDACTED)
        }
    }

    private fun scrubEventExtras(event: SentryEvent) {
        event.extras?.toMap()?.forEach { (key, value) ->
            if (value is String) event.setExtra(key, scrubDataValue(key, value) ?: REDACTED)
        }
    }

    private fun scrubEventRequest(event: SentryEvent) {
        val request = event.request ?: return
        request.url = scrubText(request.url)
        request.queryString = scrubText(request.queryString)
        request.cookies = scrubText(request.cookies)
        request.headers?.toMap()?.forEach { (key, value) ->
            request.headers = request.headers?.toMutableMap()?.also {
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
    }
}
