package io.github.arttolst12.netion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLogQueueTest {

    @Test
    fun capacityDropsOldestAndReportsSkipBeforeSurvivingFifoEntries() {
        val queue = BoundedLogQueue<String>(3)
        assertTrue(queue.offer("one"))
        assertFalse(queue.offer("two"))
        assertFalse(queue.offer("three"))
        assertFalse(queue.offer("four"))

        val batch = queue.drain(3)
        assertEquals(1, batch.skippedCount)
        assertEquals(listOf("two", "three", "four"), batch.entries.map { it.value })
        assertFalse(batch.hasMore)
    }

    @Test
    fun offersCoalesceUntilTheScheduledConsumerDrainsEverything() {
        val queue = BoundedLogQueue<Int>(4)
        assertTrue(queue.offer(1))
        assertFalse(queue.offer(2))

        val firstBatch = queue.drain(1)
        assertTrue(firstBatch.hasMore)
        assertFalse(queue.offer(3))

        val finalBatch = queue.drain(4)
        assertEquals(listOf(2, 3), finalBatch.entries.map { it.value })
        assertFalse(finalBatch.hasMore)
        assertTrue(queue.offer(4))
    }

    @Test
    fun clearStartsNewEpochAndDiscardsOldEntriesWithoutDuplicateScheduling() {
        val queue = BoundedLogQueue<String>(3)
        assertTrue(queue.offer("old"))
        val oldEpoch = queue.currentEpoch()

        val newEpoch = queue.clear()
        assertTrue(newEpoch > oldEpoch)
        assertFalse(queue.offer("new")) // The already-coalesced drain will consume it.

        val batch = queue.drain(3)
        assertEquals(0, batch.skippedCount)
        assertEquals(listOf("new"), batch.entries.map { it.value })
        assertEquals(listOf(newEpoch), batch.entries.map { it.epoch })
        assertFalse(batch.hasMore)
        assertTrue(queue.offer("after-drain"))
    }
}
