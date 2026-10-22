package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class TunWriterCleanupTest {
    @Test
    fun closeReleasesOutputAfterSynchronousWriteFailure() {
        val closes = AtomicInteger()
        val writer = TunWriter(failingOutput(closes))
        try {
            writer.write(byteArrayOf(1))
            fail("write must fail")
        } catch (_: IOException) {
        }
        writer.close()
        writer.close()
        assertEquals("failed writer must still release its output exactly once", 1, closes.get())
    }

    @Test
    fun closeReleasesOutputAfterQueuedWriteFailure() {
        val closes = AtomicInteger()
        val failed = CountDownLatch(1)
        val writer = TunWriter(failingOutput(closes), queued = true, onFailure = { failed.countDown() })
        try {
            writer.write(byteArrayOf(1))
            assertTrue(failed.await(2, TimeUnit.SECONDS))
        } finally {
            writer.close()
        }
        writer.close()
        assertEquals(1, closes.get())
    }

    @Test
    fun concurrentCloseReleasesOutputOnlyOnce() {
        val closes = AtomicInteger()
        val writer = TunWriter(object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() { closes.incrementAndGet() }
        }, queued = true)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(8)
        repeat(8) {
            Thread {
                try {
                    start.await()
                    writer.close()
                } finally {
                    finished.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, closes.get())
    }

    private fun failingOutput(closes: AtomicInteger) = object : OutputStream() {
        override fun write(b: Int): Unit = throw IOException("injected TUN failure")
        override fun close() { closes.incrementAndGet() }
    }
}
