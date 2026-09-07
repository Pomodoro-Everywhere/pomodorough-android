package me.egigoka.pomodorough.crash

import io.sentry.SentryEvent
import io.sentry.protocol.App
import io.sentry.protocol.Device
import io.sentry.protocol.Geo
import io.sentry.protocol.Mechanism
import io.sentry.protocol.OperatingSystem
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryStackFrame
import io.sentry.protocol.SentryStackTrace
import io.sentry.protocol.SentryThread
import io.sentry.protocol.User
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryScrubberResidualsTest {
    @Test
    fun frameVarsAndRegistersLoseSecrets() {
        val event = SentryEvent()
        event.exceptions = listOf(
            SentryException().apply {
                value = "boom"
                stacktrace = SentryStackTrace(
                    listOf(
                        SentryStackFrame().apply {
                            function = "sync"
                            vars = mapOf(
                                "invite" to "pomodorough1SECRET99",
                                "email" to "ada@example.com",
                                "count" to 3,
                            )
                        },
                    ),
                ).apply {
                    registers = mapOf("token" to "Bearer abcdefgh12345678", "mode" to "fast")
                }
            },
        )
        SentryScrubber.scrubEvent(event)
        val frame = event.exceptions!!.single().stacktrace!!.frames!!.single()
        assertEquals(SentryScrubber.REDACTED, frame.vars!!["invite"])
        assertFalse((frame.vars!!["email"] as String).contains("ada@example.com"))
        assertEquals(3, frame.vars!!["count"])
        assertFalse(event.exceptions!!.single().stacktrace!!.registers!!["token"]!!.contains("abcdefgh"))
        assertEquals("fast", event.exceptions!!.single().stacktrace!!.registers!!["mode"])
    }

    @Test
    fun frameContextLinesAndPathsLoseSecrets() {
        val event = SentryEvent()
        event.exceptions = listOf(
            SentryException().apply {
                value = "boom"
                stacktrace = SentryStackTrace(
                    listOf(
                        SentryStackFrame().apply {
                            filename = "sync ada@example.com.kt"
                            absPath = "/data/join pomodorough1SECRET99.kt"
                            contextLine = "https://host/sync?token=secret123"
                            preContext = listOf("hi ada@example.com")
                            postContext = listOf("clean")
                        },
                    ),
                )
            },
        )
        SentryScrubber.scrubEvent(event)
        val frame = event.exceptions!!.single().stacktrace!!.frames!!.single()
        assertFalse(frame.filename!!.contains("ada@example.com"))
        assertFalse(frame.absPath!!.contains("pomodorough1"))
        assertFalse(frame.contextLine!!.contains("secret123"))
        assertFalse(frame.preContext!!.single().contains("ada@example.com"))
        assertEquals("clean", frame.postContext!!.single())
    }

    @Test
    fun mechanismDescriptionAndDataLoseSecrets() {
        val event = SentryEvent()
        event.exceptions = listOf(
            SentryException().apply {
                value = "boom"
                mechanism = Mechanism().apply {
                    description = "failed for ada@example.com"
                    data = mapOf("invite" to "pomodorough1SECRET99", "code" to 7)
                }
            },
        )
        SentryScrubber.scrubEvent(event)
        val mechanism = event.exceptions!!.single().mechanism!!
        assertFalse(mechanism.description!!.contains("ada@example.com"))
        assertEquals(SentryScrubber.REDACTED, mechanism.data!!["invite"])
        assertEquals(7, mechanism.data!!["code"])
    }

    @Test
    fun threadStacktraceVarsLoseSecrets() {
        val event = SentryEvent()
        event.threads = listOf(
            SentryThread().apply {
                name = "worker"
                stacktrace = SentryStackTrace(
                    listOf(
                        SentryStackFrame().apply {
                            vars = mapOf("token" to "abc123")
                        },
                    ),
                )
            },
        )
        SentryScrubber.scrubEvent(event)
        val frame = event.threads!!.single().stacktrace!!.frames!!.single()
        assertEquals(SentryScrubber.REDACTED, frame.vars!!["token"])
    }

    @Test
    fun userNameDataAndGeoLoseSecrets() {
        val event = SentryEvent()
        event.user = User().apply {
            email = "ada@example.com"
            name = "ada@example.com"
            id = "550e8400-e29b-41d4-a716-446655440000"
            username = "ada@example.com"
            data = mapOf("invite" to "pomodorough1SECRET99", "plan" to "free")
            geo = Geo().apply {
                city = "hi ada@example.com"
                region = "clean"
            }
        }
        SentryScrubber.scrubEvent(event)
        val user = event.user!!
        assertEquals(SentryScrubber.REDACTED_EMAIL, user.email)
        assertFalse(user.name!!.contains("ada@example.com"))
        assertFalse(user.id!!.contains("446655440000"))
        assertEquals(SentryScrubber.REDACTED, user.data!!["invite"])
        assertEquals("free", user.data!!["plan"])
        assertFalse(user.geo!!.city!!.contains("ada@example.com"))
        assertEquals("clean", user.geo!!.region)
    }

    @Test
    fun typedDeviceAppAndOsContextsLoseSecrets() {
        val event = SentryEvent()
        event.contexts.setDevice(
            Device().apply {
                name = "Ada ada@example.com"
                id = "550e8400-e29b-41d4-a716-446655440000"
            },
        )
        event.contexts.setApp(
            App().apply {
                viewNames = listOf("sync ada@example.com", "timer")
            },
        )
        event.contexts.setOperatingSystem(
            OperatingSystem().apply {
                rawDescription = "join pomodorough1SECRET99"
                name = "Android"
            },
        )
        SentryScrubber.scrubEvent(event)
        assertFalse(event.contexts.device!!.name!!.contains("ada@example.com"))
        assertFalse(event.contexts.device!!.id!!.contains("446655440000"))
        assertTrue(event.contexts.app!!.viewNames!!.none { it.contains("ada@example.com") })
        assertTrue(event.contexts.app!!.viewNames!!.contains("timer"))
        assertFalse(event.contexts.operatingSystem!!.rawDescription!!.contains("pomodorough1"))
        assertEquals("Android", event.contexts.operatingSystem!!.name)
    }
}
