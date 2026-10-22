package io.github.arttolst12.netion

import java.util.ArrayDeque

/**
 * A thread-safe, bounded FIFO used to coalesce requests for a single consumer drain.
 *
 * A successful [offer] returns true only when the caller must schedule that drain.
 * Entries receive an epoch so a consumer can reject work that was drained immediately
 * before [clear] was called.
 */
class BoundedLogQueue<T>(private val capacity: Int) {

    data class Entry<T>(val epoch: Long, val value: T)

    data class Batch<T>(
        val entries: List<Entry<T>>,
        val skippedCount: Int,
        val hasMore: Boolean,
    )

    private val entries = ArrayDeque<Entry<T>>()
    private var epoch = 0L
    private var drainScheduled = false
    private var skippedCount = 0

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /**
     * Adds [value], dropping the oldest pending item when full.
     *
     * @return true if this call transitioned the queue from idle to needing a drain.
     */
    @Synchronized
    fun offer(value: T): Boolean {
        if (entries.size == capacity) {
            entries.removeFirst()
            skippedCount++
        }
        entries.addLast(Entry(epoch, value))
        if (drainScheduled) return false
        drainScheduled = true
        return true
    }

    /**
     * Removes no more than [maxEntries] items in FIFO order. This method is intended
     * to be called by the sole scheduled consumer.
     */
    @Synchronized
    fun drain(maxEntries: Int): Batch<T> {
        require(maxEntries > 0) { "maxEntries must be positive" }

        val skipped = skippedCount
        val itemLimit = maxEntries
        val batch = ArrayList<Entry<T>>(minOf(itemLimit, entries.size))
        repeat(minOf(itemLimit, entries.size)) {
            batch += entries.removeFirst()
        }
        val hasMore = entries.isNotEmpty()
        if (!hasMore) {
            // The currently executing consumer is now the final coalesced drain.
            drainScheduled = false
        }
        skippedCount = 0
        return Batch(batch, skipped, hasMore)
    }

    /**
     * Drops pending entries and starts a new epoch. A drain already posted remains
     * coalesced and will finish harmlessly, so a concurrent producer does not post a
     * duplicate drain.
     */
    @Synchronized
    fun clear(): Long {
        entries.clear()
        skippedCount = 0
        epoch++
        return epoch
    }

    @Synchronized
    fun currentEpoch(): Long = epoch
}
