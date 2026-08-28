package me.egigoka.pomodorough.data.iroh

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IrohLifecycleStateTest {
    @Test
    fun staleBackgroundOwnerCannotStopNewForegroundEndpoint() {
        val lifecycle = IrohLifecycleState()
        lifecycle.enterForeground()
        val background = lifecycle.enterBackground()

        lifecycle.enterForeground()

        assertFalse(lifecycle.permitsBackgroundStop(background.snapshot.generation))
        assertTrue(lifecycle.snapshot().foreground)
    }

    @Test
    fun startOwnerIsRejectedAfterBackgroundTransition() {
        val lifecycle = IrohLifecycleState()
        val foreground = lifecycle.enterForeground()

        lifecycle.enterBackground()

        assertFalse(lifecycle.permitsEndpoint(foreground.snapshot.generation))
        assertTrue(lifecycle.permitsBackgroundStop(lifecycle.snapshot().generation))
    }

    @Test
    fun concurrentTransitionsReceiveUniqueMonotonicGenerations() {
        val lifecycle = IrohLifecycleState()
        val owners = Collections.synchronizedList(mutableListOf<Long>())
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val futures = (0 until 64).map { index ->
                pool.submit {
                    start.await()
                    val event = if (index % 2 == 0) {
                        lifecycle.enterForeground()
                    } else {
                        lifecycle.enterBackground()
                    }
                    owners += event.snapshot.generation
                }
            }
            start.countDown()
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }

        assertEquals((1L..64L).toList(), owners.sorted())
        assertEquals(64L, lifecycle.snapshot().generation)
    }
}
