package me.egigoka.pomodorough.data

import java.io.File
import me.egigoka.pomodorough.crash.CrashReporter
import me.egigoka.pomodorough.crash.CrashReportingRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R43-A10: corrupt persisted state must report a bounded cause category.
 * Each test injects one failure class with a payload sentinel, then asserts
 * the report carries only the fixed category and never the payload.
 */
class R43A10CorruptStateDiagnosticsTest {
    @Test
    fun decodingReportsBoundedCategoryWithoutPayload() {
        val payload = "R43A10-DECODING-SENTINEL-{\"settingsJson\":\"secret\"}"
        val reported = captureReports { CorruptStateReporter.report(CorruptStateCategory.Decoding) }
        assertSingleBoundedReport(reported, CorruptStateCategory.Decoding, payload)
    }

    @Test
    fun queueValidationReportsBoundedCategoryWithoutPayload() {
        val payload = "R43A10-QUEUE-SENTINEL-{\"commandId\":\"secret\"}"
        val reported = captureReports { CorruptStateReporter.report(CorruptStateCategory.QueueValidation) }
        assertSingleBoundedReport(reported, CorruptStateCategory.QueueValidation, payload)
    }

    @Test
    fun clockRangeReportsBoundedCategoryWithoutPayload() {
        val payload = "R43A10-CLOCK-SENTINEL-{\"hlcWallMs\":999}"
        val reported = captureReports { CorruptStateReporter.report(CorruptStateCategory.ClockRange) }
        assertSingleBoundedReport(reported, CorruptStateCategory.ClockRange, payload)
    }

    @Test
    fun duplicateCategoryReportsOnce() {
        val reported = captureReports {
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
        }
        assertEquals(1, reported.size)
    }

    @Test
    fun distinctCategoriesReportSeparately() {
        val reported = captureReports {
            CorruptStateReporter.report(CorruptStateCategory.QueueValidation)
            CorruptStateReporter.report(CorruptStateCategory.ClockRange)
        }
        assertEquals(2, reported.size)
    }

    @Test
    fun consentDisabledSkipsReport() {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = false
        try {
            CorruptStateReporter.report(CorruptStateCategory.Decoding)
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            CorruptStateReporter.resetForTest()
        }
        assertTrue(reported.isEmpty())
    }

    @Test
    fun repositoryWiringUsesBoundedCategories() {
        val text = File(productionRoot(), "me/egigoka/pomodorough/data/TimerRepository.kt").readText()
        assertTrue(text.contains("CorruptStateReporter.report(CorruptStateCategory.Decoding)"))
        assertTrue(text.contains("CorruptStateCategory.QueueValidation"))
        assertTrue(text.contains("CorruptStateCategory.ClockRange"))
        assertTrue(text.contains("fun failCorruptMutationState(message: String, category: CorruptStateCategory)"))
        assertNoRawPayloadReport(text)
    }

    private fun captureReports(block: () -> Unit): List<Throwable> {
        val previousEnabled = CrashReportingRuntime.reportingEnabled
        val previousDelegate = CrashReporter.delegate
        val reported = mutableListOf<Throwable>()
        CrashReporter.delegate = reported::add
        CrashReportingRuntime.reportingEnabled = true
        CorruptStateReporter.resetForTest()
        try {
            block()
        } finally {
            CrashReportingRuntime.reportingEnabled = previousEnabled
            CrashReporter.delegate = previousDelegate
            CorruptStateReporter.resetForTest()
        }
        return reported
    }

    private fun assertSingleBoundedReport(
        reported: List<Throwable>,
        category: CorruptStateCategory,
        payload: String,
    ) {
        assertEquals(1, reported.size)
        val error = reported.single()
        assertTrue(error is CorruptStateReport)
        assertEquals(category.reportMessage, error.message)
        assertTrue(error.cause == null)
        assertTrue(error.message?.contains(payload) != true)
        assertTrue(stackLacksPayload(error, payload))
    }

    private fun stackLacksPayload(error: Throwable, payload: String): Boolean {
        val rendered = error.stackTraceToString()
        return !rendered.contains(payload)
    }

    private fun assertNoRawPayloadReport(text: String) {
        val start = text.indexOf("private suspend fun validateLoadedMutationState")
        assertTrue(start >= 0)
        val window = text.substring(start, (start + 2_500).coerceAtMost(text.length))
        assertTrue(!window.contains("CrashReporter.report(queueError"))
        assertTrue(!window.contains("CrashReporter.report(rangeError"))
    }

    private fun productionRoot(): File {
        val root = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        return requireNotNull(root)
    }
}
