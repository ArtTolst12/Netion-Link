package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TunWriterTest {

    @Test
    fun synchronousWriterRetainsTrailingLambdaCompatibility() {
        val output = ByteArrayOutputStream()
        var accounted = 0
        val writer = TunWriter(output) { accounted += it }

        writer.write(byteArrayOf(1, 2, 3))
        writer.close()

        assertArrayEquals(byteArrayOf(1, 2, 3), output.toByteArray())
        assertEquals(3, accounted)
    }

    @Test
    fun queuedWriterCopiesBuffersAndReportsOnlyActualWrites() {
        val releaseWrite = CountDownLatch(1)
        val wrote = CountDownLatch(1)
        val accounted = CountDownLatch(1)
        val sink = ByteArrayOutputStream()
        val output = object : OutputStream() {
            override fun write(b: Int) = sink.write(b)
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                assertTrue("writer must unblock", releaseWrite.await(2, TimeUnit.SECONDS))
                sink.write(bytes, off, len)
                wrote.countDown()
            }
        }
        val accounting = AtomicInteger()
        val writer = TunWriter(output, queued = true, onBytesWritten = {
            accounting.addAndGet(it)
            accounted.countDown()
        })
        val mutablePacket = byteArrayOf(7, 8, 9)

        writer.write(mutablePacket)
        mutablePacket.fill(0)
        assertEquals("queued bytes must not be accounted before physical write", 0, accounting.get())
        releaseWrite.countDown()
        assertTrue(wrote.await(2, TimeUnit.SECONDS))
        assertTrue("write accounting callback must complete", accounted.await(2, TimeUnit.SECONDS))
        writer.close()

        assertArrayEquals(byteArrayOf(7, 8, 9), sink.toByteArray())
        assertEquals(3, accounting.get())
    }

    @Test
    fun queueOverflowIsExplicitAndDoesNotDropSilently() {
        val releaseWrite = CountDownLatch(1)
        val firstWrite = CountDownLatch(1)
        val output = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(bytes: ByteArray, off: Int, len: Int) {
                firstWrite.countDown()
                releaseWrite.await(2, TimeUnit.SECONDS)
            }
        }
        val writer = TunWriter(output, queued = true, maxQueuedPackets = 1, maxQueuedBytes = 8)
        try {
            writer.write(byteArrayOf(1))
            assertTrue(firstWrite.await(2, TimeUnit.SECONDS))
            writer.write(byteArrayOf(2))
            try {
                writer.write(byteArrayOf(3))
                throw AssertionError("expected queue overflow")
            } catch (_: IOException) {
                // Required contract: producer sees a failure rather than a silently lost packet.
            }
        } finally {
            releaseWrite.countDown()
            writer.close()
        }
    }

    @Test
    fun writerFailureIsReportedOnceAndFutureWritesFail() {
        val callbacks = AtomicInteger()
        val writer = TunWriter(
            output = object : OutputStream() {
                override fun write(b: Int) = throw IOException("broken tun")
            },
            onFailure = { callbacks.incrementAndGet() }
        )
        try {
            try {
                writer.write(byteArrayOf(1))
                throw AssertionError("expected write failure")
            } catch (_: IOException) {}
            try {
                writer.write(byteArrayOf(2))
                throw AssertionError("expected latched write failure")
            } catch (_: IOException) {}
            assertEquals(1, callbacks.get())
        } finally {
            writer.close()
        }
    }

    @Test
    fun generationGuardRejectsStalePublication() {
        val output = ByteArrayOutputStream()
        val active = java.util.concurrent.atomic.AtomicBoolean(false)
        val writer = TunWriter(output, canWrite = { active.get() })
        try {
            try {
                writer.write(byteArrayOf(1))
                throw AssertionError("stale generation must be rejected")
            } catch (_: IOException) {}
            assertEquals(0, output.size())
        } finally {
            writer.close()
        }
    }
}
