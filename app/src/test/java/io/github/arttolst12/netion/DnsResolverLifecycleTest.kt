package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.DnsResolver
import io.github.arttolst12.netion.vpn.PacketUtils
import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DnsResolverLifecycleTest {

    @Test
    fun closeCancelsRegisteredTransportAndSuppressesLateReply() {
        val enteredTransport = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        val completedTransport = AtomicBoolean(false)
        val sink = ByteArrayOutputStream()
        val writer = TunWriter(sink)
        val resolver = DnsResolver(
            tunWriter = writer,
            udpTransportFn = { _, _, query ->
                enteredTransport.countDown()
                try {
                    releaseTransport.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // close() interrupts the worker; still return a syntactically valid answer
                    // to prove that the post-transport close check suppresses publication.
                }
                completedTransport.set(true)
                responseFor(query)
            }
        )
        val query = query(0x1234)
        val ip = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, CLIENT, DNS)
        val udp = PacketUtils.UdpHeader(53000, 53, 8 + query.size)

        resolver.handleQuery(ip, udp, query)
        assertTrue(enteredTransport.await(2, TimeUnit.SECONDS))
        resolver.close()
        releaseTransport.countDown()
        repeat(20) {
            if (completedTransport.get()) return@repeat
            Thread.sleep(10)
        }

        assertEquals("closed resolver must not publish a late response", 0, sink.size())
        writer.close()
    }

    @Test
    fun closePreventsCacheMutationAfterWorkerReturns() {
        val enteredTransport = CountDownLatch(1)
        val releaseTransport = CountDownLatch(1)
        val completedTransport = AtomicBoolean(false)
        val resolver = DnsResolver(
            udpTransportFn = { _, _, query ->
                enteredTransport.countDown()
                try {
                    releaseTransport.await(2, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {}
                completedTransport.set(true)
                responseFor(query)
            }
        )
        val query = query(0x2233)
        val ip = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, CLIENT, DNS)
        val udp = PacketUtils.UdpHeader(53001, 53, 8 + query.size)

        resolver.handleQuery(ip, udp, query)
        assertTrue(enteredTransport.await(2, TimeUnit.SECONDS))
        resolver.close()
        releaseTransport.countDown()
        repeat(20) {
            if (completedTransport.get()) return@repeat
            Thread.sleep(10)
        }

        assertEquals("closed resolver must not write late cache state", 0, resolver.cache.size())
    }

    private fun query(id: Int): ByteArray = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(),
        0x01, 0x00, // RD query
        0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 'a'.code.toByte(),
        0x03, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
        0x00, 0x00, 0x01, 0x00, 0x01
    )

    private fun responseFor(query: ByteArray): ByteArray {
        val response = query.copyOf()
        response[2] = (response[2].toInt() or 0x80).toByte() // QR
        response[3] = (response[3].toInt() or 0x80).toByte() // RA
        return response
    }

    private companion object {
        val CLIENT = byteArrayOf(10, 0, 0, 2)
        val DNS = byteArrayOf(8, 8, 8, 8)
    }
}
