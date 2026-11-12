package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class R54L16EncodedEmailTest {
    @Test
    fun literalAddressesContainingEncodedLookingLocalPartsStayFullyPrivate() {
        for (address in listOf("alice%40tag@example.com", "alice%2540tag@example.com", "a%252540b@example.org")) {
            val text = "user $address failed"
            val expected = "user ${SentryScrubber.REDACTED_EMAIL} failed"
            val event = SentryEvent().apply { message = Message().apply { formatted = text }; setExtra("detail", text) }
            val crumb = Breadcrumb().apply { message = text; setData("detail", text) }
            SentryScrubber.scrubEvent(event)
            SentryScrubber.scrubBreadcrumb(crumb)
            assertEquals(expected, event.message?.formatted)
            assertEquals(expected, event.extras?.get("detail"))
            assertEquals(expected, crumb.message)
            assertEquals(expected, crumb.data["detail"])
        }
    }

    @Test
    fun encodedAddressesAreRedactedAcrossFreeTextHooks() {
        for (depth in 0..8) {
            val address = "alice%" + "25".repeat(depth) + "40example.com"
            for (text in listOf("user $address failed", "user=$address", "($address)", "a@$address")) {
                val expected = text.replace(address, SentryScrubber.REDACTED_EMAIL)
                val event = SentryEvent().apply {
                    message = Message().apply { formatted = text }
                    setExtra("detail", text)
                }
                val crumb = Breadcrumb().apply { message = text; setData("detail", text) }
                SentryScrubber.scrubEvent(event)
                SentryScrubber.scrubBreadcrumb(crumb)
                assertEquals(text, expected, event.message?.formatted)
                assertEquals(text, expected, event.extras?.get("detail"))
                assertEquals(text, expected, crumb.message)
                assertEquals(text, expected, crumb.data["detail"])
            }
        }
    }

    @Test
    fun encodedNonAddressesStayUnchanged() {
        for (text in listOf("alice%40localhost", "alice%2540example.c", "retry%2541example.com", "bare%25")) {
            assertEquals(text, text, SentryScrubber.scrubText(text))
        }
    }
}
