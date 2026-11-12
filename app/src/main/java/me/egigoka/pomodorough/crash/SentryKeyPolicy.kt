package me.egigoka.pomodorough.crash

// R54-L03: one key rule for every map the scrubber keeps, shared with the
// desktop `_diagnostic_key_name`. Values were already scrubbed, but raw keys
// reached Sentry verbatim: emails, invites, tabs, percent escapes, and the
// text of non-string keys. A key keeps its exact text only when it reads as
// a static diagnostic name. Any other key becomes a numbered placeholder
// whose whole value is redacted, so two raw keys never merge into one name
// and never overwrite each other's value. Placeholders are bracketed, which
// no diagnostic name can be, so a raw key cannot claim one either.
internal object SentryKeyPolicy {
    private const val KEY_LENGTH_LIMIT = 64
    private const val OPAQUE_KEY_DIGIT_LIMIT = 6

    // `[0-9]*` keeps empty and index-like names such as list positions and
    // status counters ("404"). Six or more digits read as ids, timestamps, or
    // one-time codes and are filtered by the digit limit below.
    private val diagnosticName = Regex("[A-Za-z_][A-Za-z0-9_.-]*|[0-9]*")

    // A long alphanumeric run with two or more digits reads as an opaque
    // identifier (hex ids, base64url payloads), not as a static name.
    private val opaqueRun = Regex("[A-Za-z0-9]{16,}")
    private val privateText = Regex("(?i)pomodorough1|[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")

    fun isDiagnosticName(key: String): Boolean {
        if (key.length > KEY_LENGTH_LIMIT || !diagnosticName.matches(key)) return false
        return !isOpaque(key) && !privateText.containsMatchIn(key)
    }

    // Same text as desktop `_FILTERED_KEY`, numbered from 1 within each map.
    fun redactedKey(position: Int): String = "[Filtered key $position]"

    // Keys are typed `Any?` on purpose: nested maps carry any key type, and
    // calling `toString()` on one could spell private text or throw.
    fun <V> scrubKeys(
        entries: Map<*, V>,
        redactedValue: V,
        scrubItem: (String, V) -> V,
    ): Map<String, V> {
        val cleaned = LinkedHashMap<String, V>()
        var redactedKeys = 0
        entries.forEach { (key, value) ->
            if (key is String && isDiagnosticName(key)) {
                cleaned[key] = scrubItem(key, value)
            } else {
                redactedKeys += 1
                cleaned[redactedKey(redactedKeys)] = redactedValue
            }
        }
        return cleaned
    }

    private fun isOpaque(key: String): Boolean {
        if (key.count(::isAsciiDigit) >= OPAQUE_KEY_DIGIT_LIMIT) return true
        return opaqueRun.findAll(key).any { run -> run.value.count(::isAsciiDigit) >= 2 }
    }

    private fun isAsciiDigit(char: Char): Boolean = char in '0'..'9'
}
