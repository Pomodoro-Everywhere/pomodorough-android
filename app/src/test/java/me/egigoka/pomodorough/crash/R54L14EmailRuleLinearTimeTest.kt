package me.egigoka.pomodorough.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// R54-L14: the free-text email rule reads each local-part run once. Long text
// without a complete address took seconds, because the old rule retried every
// later start inside a run. The rows pin the addresses the old rule redacted
// and the texts it kept, so the linear rule must give the same output.
// Synthetic addresses only.

private const val EMAIL = SentryScrubber.REDACTED_EMAIL
private val localRun = "+.".repeat(2500)

private val emailRows: Map<String, Pair<String, String>> = linkedMapOf(
    "plain" to ("sync failed for ada@example.com today" to "sync failed for $EMAIL today"),
    "tagged_local" to ("to x.y+tag@mail.example.org, then" to "to $EMAIL, then"),
    "percent_local" to ("from a%b@ex-ample.io" to "from $EMAIL"),
    "bracketed" to ("(ada@example.com)" to "($EMAIL)"),
    // The TLD needs two letters, so the address ends before `.x`.
    "tld_backtrack" to ("ada@example.com.x end" to "$EMAIL.x end"),
    // A run can start right after a redacted address.
    "digit_after_tld" to ("ada@b.com9x@y.org" to "$EMAIL$EMAIL"),
    "second_at" to ("a@b@c.io" to "a@$EMAIL"),
    "long_local" to ("a".repeat(5000) + "@example.com" to EMAIL),
    "run_then_address" to ("$localRun ada@example.com" to "$localRun $EMAIL"),
    "run_glued_to_address" to ("${localRun}ada@example.com" to EMAIL),
    // Controls: no complete address, so the text stays.
    "no_tld" to ("user@localhost failed" to "user@localhost failed"),
    "short_tld" to ("mail a@b.c now" to "mail a@b.c now"),
    "bare_run" to ("retry x.y+tag later" to "retry x.y+tag later"),
)

/** Texts of [length] characters with no complete address; each was quadratic under the old rule. */
private val slowShapes: Map<String, (Int) -> String> = linkedMapOf(
    "local_run" to { n -> "a".repeat(n) },
    "json_long_key" to { n -> "{\"" + "a".repeat(n) + "\":\"x\"}" },
    "unclosed_secret" to { n -> "\"token\":\"" + "a".repeat(n) },
    "domain_without_tld" to { n -> "a@" + "b".repeat(n) },
    "mixed_local_run" to { n -> "a.b-c_d%e+f".repeat(n / 11) },
    "nested_encoded_at" to { n -> "a%" + "25".repeat(n / 2) + "40example.com" },
    "nested_encoded_non_at" to { n -> "a%" + "25".repeat(n / 2) + "41example.com" },
    "encoded_without_tld" to { n -> "a%40" + "b".repeat(n) },
    "literal_with_encoded_local" to { n -> "a%40tag@example.com ".repeat(n / 20) },
)

private const val SMALL = 15_000
private const val LARGE = 60_000
private const val ROUNDS = 3
private const val SMALL_REPEATS = LARGE / SMALL
// Linear work grows 4x from SMALL to LARGE, and the old rule grew about 16x.
// The slack keeps timer noise on millisecond scrubs from deciding the result.
private const val MAX_GROWTH = 8
private const val SLACK_NANOS = 50_000_000L
// A hang guard only: one timing over this much CPU fails the test at once.
private const val HANG_NANOS = 5_000_000_000L

/**
 * CPU time of the calling thread. android.jar leaves out java.lang.management,
 * so the test reaches the host JVM's thread bean through reflection.
 */
private object ThreadCpuClock {
    private val beanType = Class.forName("java.lang.management.ThreadMXBean")
    private val bean = Class.forName("java.lang.management.ManagementFactory")
        .getMethod("getThreadMXBean").invoke(null)
    private val supportedMethod = beanType.getMethod("isCurrentThreadCpuTimeSupported")
    private val nanosMethod = beanType.getMethod("getCurrentThreadCpuTime")

    val supported: Boolean get() = supportedMethod.invoke(bean) as Boolean

    fun nanos(): Long = nanosMethod.invoke(bean) as Long
}

class R54L14EmailRuleLinearTimeTest {
    @Test
    fun emailRuleRedactsTheSameAddresses() {
        for ((name, row) in emailRows) {
            assertEquals(name, row.second, SentryScrubber.scrubText(row.first))
        }
    }

    // Thread CPU time leaves out time spent waiting for a core. The small text
    // runs SMALL_REPEATS times per timing, so both sizes hold the CPU about as
    // long, and each size keeps its best per-scrub time of ROUNDS rounds.
    @Test
    fun scrubTextGrowsLinearlyOnLongTextWithoutAnAddress() {
        assertTrue("thread CPU time is needed", ThreadCpuClock.supported)
        for ((name, shape) in slowShapes) {
            val small = shape(SMALL)
            val large = shape(LARGE)
            SentryScrubber.scrubText(small)
            val smallNanos = bestScrubNanos(name, small, SMALL_REPEATS)
            val largeNanos = bestScrubNanos(name, large, 1)
            val limit = MAX_GROWTH * smallNanos + SLACK_NANOS
            assertTrue(
                "$name: ${largeNanos / 1_000_000} ms at $LARGE chars, limit ${limit / 1_000_000} ms " +
                    "from ${smallNanos / 1_000_000} ms at $SMALL chars",
                largeNanos <= limit,
            )
        }
    }

    private fun bestScrubNanos(name: String, text: String, repeats: Int): Long = (1..ROUNDS).minOf {
        val start = ThreadCpuClock.nanos()
        repeat(repeats) { SentryScrubber.scrubText(text) }
        val nanos = ThreadCpuClock.nanos() - start
        assertTrue("$name: ${nanos / 1_000_000} ms of CPU for ${text.length} chars", nanos <= HANG_NANOS)
        nanos / repeats
    }
}
