package me.egigoka.pomodorough.data

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPublicationLinearizerTest {
    @Test
    fun quarantineTransitionWaitsForAdmittedPublicationBeforeReturning() {
        val linearizer = AccountPublicationLinearizer()
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val transitionReturned = CountDownLatch(1)
        val commits = Collections.synchronizedList(mutableListOf<Boolean>())

        val publisher = thread {
            linearizer.publish { quarantined ->
                publicationEntered.countDown()
                assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                commits += quarantined
            }
        }
        assertTrue(publicationEntered.await(5, TimeUnit.SECONDS))

        val transition = thread {
            linearizer.transition(quarantined = true)
            transitionReturned.countDown()
        }
        assertFalse(transitionReturned.await(100, TimeUnit.MILLISECONDS))

        releasePublication.countDown()
        publisher.join(5_000)
        transition.join(5_000)
        assertFalse(publisher.isAlive)
        assertFalse(transition.isAlive)
        assertTrue(transitionReturned.await(0, TimeUnit.MILLISECONDS))

        linearizer.publish { quarantined -> commits += quarantined }
        assertEquals(listOf(false, true), commits)
    }

    @Test
    fun repairingTransitionPublishesUnderTheSameLinearizationPoint() {
        val linearizer = AccountPublicationLinearizer()
        linearizer.transition(quarantined = true)
        val commits = mutableListOf<Boolean>()

        linearizer.transition(quarantined = false) { quarantined ->
            commits += quarantined
        }
        linearizer.publish { quarantined -> commits += quarantined }

        assertEquals(listOf(false, false), commits)
    }
}
