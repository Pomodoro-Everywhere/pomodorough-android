package me.egigoka.pomodorough.data.iroh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IrohReplicationServiceTest {
    @Test
    fun retryDelayCapsFinalJitteredValue() {
        assertEquals(15_000L, cappedRetryDelayMs(15_000L, 0L))
        assertEquals(60_000L, cappedRetryDelayMs(60_000L, 12_000L))
        assertEquals(60_000L, cappedRetryDelayMs(55_000L, 11_000L))
        assertThrows(IllegalArgumentException::class.java) {
            cappedRetryDelayMs(1_000L, -1L)
        }
    }
}
