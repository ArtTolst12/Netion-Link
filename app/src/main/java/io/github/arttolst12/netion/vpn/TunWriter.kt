package io.github.arttolst12.netion.vpn

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.util.ArrayDeque

/**
 * Serializes packets written back to the TUN device.
 *
 * The default mode writes synchronously to retain the small, deterministic test seam used by
 * callers. Production may opt into [queued], which owns packet bytes until one daemon writer has
 * written them to the output stream. A full queue is an explicit failure: callers never silently
 * lose a packet.
 */
class TunWriter(
    private val output: OutputStream,
    private val queued: Boolean = false,
    private val maxQueuedPackets: Int = DEFAULT_MAX_QUEUED_PACKETS,
    private val maxQueuedBytes: Int = DEFAULT_MAX_QUEUED_BYTES,
    private val canWrite: (() -> Boolean)? = null,
    private val onFailure: ((IOException) -> Unit)? = null,
    private val onBytesWritten: ((Int) -> Unit)? = null
) : Closeable {
    companion object {
        const val DEFAULT_MAX_QUEUED_PACKETS = 256
        const val DEFAULT_MAX_QUEUED_BYTES = 512 * 1024
    }

    private val lock = java.lang.Object()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var closed = false
    private var outputClosed = false
    private var failure: IOException? = null
    private var failureReported = false

    private val writerThread: Thread? = if (queued) {
        require(maxQueuedPackets > 0) { "maxQueuedPackets must be positive" }
        require(maxQueuedBytes > 0) { "maxQueuedBytes must be positive" }
        Thread({ drainQueuedPackets() }, "tun-writer").apply {
            isDaemon = true
            start()
        }
    } else {
        null
    }

    /**
     * Writes one complete IP packet. In queued mode the packet is copied before this method
     * returns; [onBytesWritten] is invoked only after the daemon actually writes it.
     *
     * @throws IOException when this writer has closed/failed or its bounded queue is full.
     */
    @Throws(IOException::class)
    fun write(packet: ByteArray) {
        if (packet.isEmpty()) return
        if (!queued) {
            writeSynchronously(packet)
            return
        }

        synchronized(lock) {
            if (canWrite?.invoke() == false) throw IOException("TUN writer generation is no longer active")
            failure?.let { throw IOException("TUN writer failed", it) }
            if (closed) throw IOException("TUN writer is closed")
            if (pending.size >= maxQueuedPackets || packet.size > maxQueuedBytes - pendingBytes) {
                throw IOException(
                    "TUN writer queue overflow: ${pending.size}/$maxQueuedPackets packets, " +
                        "$pendingBytes/$maxQueuedBytes bytes"
                )
            }
            // Callers commonly reuse read buffers. Queue-owned bytes prevent a later mutation
            // from changing a packet that has already been accepted for publication.
            pending.addLast(packet.copyOf())
            pendingBytes += packet.size
            lock.notifyAll()
        }
    }

    private fun writeSynchronously(packet: ByteArray) {
        try {
            synchronized(lock) {
                if (canWrite?.invoke() == false) throw IOException("TUN writer generation is no longer active")
                failure?.let { throw IOException("TUN writer failed", it) }
                if (closed) throw IOException("TUN writer is closed")
                output.write(packet)
                output.flush()
            }
        } catch (t: Throwable) {
            val error = asIOException(t)
            synchronized(lock) {
                closed = true
                recordFailureLocked(error)
            }
            reportFailure(error)
            throw error
        }
        // Deliberately outside the lock so accounting/failure handling cannot block a producer.
        onBytesWritten?.invoke(packet.size)
    }

    private fun drainQueuedPackets() {
        while (true) {
            val packet = synchronized(lock) {
                while (pending.isEmpty() && !closed) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        if (closed) return
                    }
                }
                if (closed) return
                pending.removeFirst()
            }

            // A VPN generation can be invalidated while this packet waited in the bounded
            // queue. Recheck ownership immediately before publication and discard stale work.
            if (canWrite?.invoke() == false) {
                synchronized(lock) {
                    pending.clear()
                    pendingBytes = 0
                    closed = true
                    lock.notifyAll()
                }
                return
            }

            try {
                output.write(packet)
                output.flush()
                synchronized(lock) {
                    pendingBytes = (pendingBytes - packet.size).coerceAtLeast(0)
                }
                onBytesWritten?.invoke(packet.size)
            } catch (t: Throwable) {
                val error = asIOException(t)
                synchronized(lock) {
                    // The in-flight packet plus every queued packet are no longer deliverable.
                    pending.clear()
                    pendingBytes = 0
                    closed = true
                    recordFailureLocked(error)
                    lock.notifyAll()
                }
                reportFailure(error)
                return
            }
        }
    }

    private fun recordFailureLocked(error: IOException) {
        if (failure == null) {
            failure = error
        }
    }

    private fun reportFailure(error: IOException) {
        val report = synchronized(lock) {
            if (failureReported) {
                false
            } else {
                failureReported = true
                true
            }
        }
        if (report) {
            try {
                onFailure?.invoke(error)
            } catch (_: Throwable) {
                // Failure reporting must not change writer shutdown semantics.
            }
        }
    }

    private fun asIOException(error: Throwable): IOException = when (error) {
        is IOException -> error
        else -> IOException("TUN output write failed", error)
    }

    /**
     * Rejects future writes, discards packets that have not begun I/O, and closes the stream.
     * It never joins the daemon writer. The VPN service closes its ParcelFileDescriptor first so
     * an in-flight FileOutputStream operation is unblocked before this method is used on cleanup.
     */
    override fun close() {
        synchronized(lock) {
            if (outputClosed) return
            outputClosed = true
            closed = true
            pending.clear()
            pendingBytes = 0
            lock.notifyAll()
        }
        writerThread?.interrupt()
        try {
            output.close()
        } catch (_: IOException) {
            // Close is idempotent cleanup; a previous descriptor close is expected during stop.
        }
    }
}
