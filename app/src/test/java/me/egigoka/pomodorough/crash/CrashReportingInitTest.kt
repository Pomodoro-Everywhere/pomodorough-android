package me.egigoka.pomodorough.crash

import io.sentry.Breadcrumb
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions
import io.sentry.protocol.Message
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashReportingInitTest {
    @Test
    fun sentryInitPathRegistersScrubHooks() {
        val options = SentryOptions()
        CrashReportingInit.configure(options, "https://key@sentry.io/1")

        assertEquals("https://key@sentry.io/1", options.dsn)
        val beforeSend = checkNotNull(options.beforeSend)
        val beforeBreadcrumb = checkNotNull(options.beforeBreadcrumb)

        val event = SentryEvent()
        event.message = Message().apply { formatted = "failed for ada@example.com" }
        val scrubbedEvent = checkNotNull(beforeSend.execute(event, Hint()))
        assertFalse(checkNotNull(scrubbedEvent.message?.formatted).contains("ada@example.com"))

        val crumb = Breadcrumb()
        crumb.message = "sync for ada@example.com"
        val scrubbedCrumb = checkNotNull(beforeBreadcrumb.execute(crumb, Hint()))
        assertFalse(checkNotNull(scrubbedCrumb.message).contains("ada@example.com"))
    }

    @Test
    fun startCrashReportingConsultsConsentBeforeInit() {
        val application = productionFile("me/egigoka/pomodorough/PomodoroughApplication.kt")
            .readText()
        val gate = application.indexOf("CrashReportingConsent.shouldStart")
        val consentRead = application.indexOf("CrashReportingConsent.isEnabled")
        val init = application.indexOf("SentryAndroid.init")
        val configure = application.indexOf("CrashReportingInit.configure")

        assertTrue(gate >= 0)
        assertTrue(consentRead >= 0)
        assertTrue(init >= 0)
        assertTrue(configure >= 0)
        assertTrue(gate < init)
        assertTrue(init < configure)
    }

    private fun productionFile(relativePath: String) = File(productionRoot(), relativePath)

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertNotNull("production source root", root)
        return requireNotNull(root)
    }
}
