package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.nio.channels.Selector
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VpnRuntimeResourcesTest {
    @Test
    fun outputCreationFailureClosesTun() {
        val fixture = Fixture()
        val failure = IOException("output failed")
        try {
            fixture.create(createOutput = { throw failure })
            fail("creation must fail")
        } catch (e: IOException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("tun"), fixture.closed)
    }

    @Test
    fun writerCreationFailureClosesRawOutputAfterTun() {
        val fixture = Fixture()
        try {
            fixture.create(createWriter = { throw IOException("writer failed") })
            fail("creation must fail")
        } catch (_: IOException) {
        }
        assertEquals(listOf("tun", "output"), fixture.closed)
    }

    @Test
    fun dnsCreationFailureClosesStartedWriter() {
        val fixture = Fixture()
        try {
            fixture.create(createResolver = { throw IOException("DNS failed") })
            fail("creation must fail")
        } catch (_: IOException) {
        }
        assertEquals(listOf("tun", "output"), fixture.closed)
        assertWriterClosed(fixture.writer!!)
    }

    @Test
    fun selectorCreationFailureClosesDnsAndWriter() {
        val fixture = Fixture()
        val failure = IOException("selector failed")
        try {
            fixture.create(createSelector = { throw failure })
            fail("creation must fail")
        } catch (e: IOException) {
            assertSame(failure, e)
        }
        assertEquals(listOf("tun", "output"), fixture.closed)
        assertTrue(fixture.resolver!!.executor.isShutdown)
        assertWriterClosed(fixture.writer!!)
    }

    @Test
    fun successfulOwnershipClosesAllResourcesExactlyOnce() {
        val fixture = Fixture()
        val resources = fixture.create()
        assertTrue(fixture.closed.isEmpty())
        assertTrue(resources.selector.isOpen)
        assertFalse(resources.dnsResolver.executor.isShutdown)
        resources.close()
        resources.close()
        assertEquals(listOf("tun", "output"), fixture.closed)
        assertFalse(resources.selector.isOpen)
        assertTrue(resources.dnsResolver.executor.isShutdown)
        assertWriterClosed(resources.writer)
    }

    @Test
    fun failureClosingTunDoesNotSkipOtherResources() {
        val fixture = Fixture(failTunClose = true)
        val resources = fixture.create()
        resources.close()
        assertEquals(listOf("tun", "output"), fixture.closed)
        assertFalse(resources.selector.isOpen)
        assertTrue(resources.dnsResolver.executor.isShutdown)
        assertWriterClosed(resources.writer)
    }

    @Test
    fun concurrentCloseDoesNotReleaseTunOrOutputTwice() {
        val tunCloses = AtomicInteger()
        val outputCloses = AtomicInteger()
        val resources = VpnRuntimeResources.create(
            tun = Closeable { tunCloses.incrementAndGet() },
            createOutput = {
                object : OutputStream() {
                    override fun write(b: Int) {}
                    override fun close() { outputCloses.incrementAndGet() }
                }
            },
            createWriter = { TunWriter(it, queued = true) },
            createDnsResolver = { DnsResolver(tunWriter = it) }
        )
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) {
            Thread {
                try {
                    start.await()
                    resources.close()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, tunCloses.get())
        assertEquals(1, outputCloses.get())
        assertFalse(resources.selector.isOpen)
        assertTrue(resources.dnsResolver.executor.isShutdown)
    }

    private fun assertWriterClosed(writer: TunWriter) {
        try {
            writer.write(byteArrayOf(1))
            fail("writer must reject work after cleanup")
        } catch (_: IOException) {
        }
    }

    private class Fixture(private val failTunClose: Boolean = false) {
        val closed = mutableListOf<String>()
        var writer: TunWriter? = null
        var resolver: DnsResolver? = null

        fun create(
            createOutput: () -> OutputStream = {
                object : OutputStream() {
                    override fun write(b: Int) {}
                    override fun close() { closed += "output" }
                }
            },
            createWriter: (OutputStream) -> TunWriter = {
                TunWriter(it, queued = true).also { created -> writer = created }
            },
            createResolver: (TunWriter) -> DnsResolver = {
                DnsResolver(tunWriter = it).also { created -> resolver = created }
            },
            createSelector: () -> Selector = { Selector.open() }
        ) = VpnRuntimeResources.create(
            tun = Closeable {
                closed += "tun"
                if (failTunClose) throw IOException("close failed")
            },
            createOutput = createOutput,
            createWriter = createWriter,
            createDnsResolver = createResolver,
            createSelector = createSelector
        )
    }
}
