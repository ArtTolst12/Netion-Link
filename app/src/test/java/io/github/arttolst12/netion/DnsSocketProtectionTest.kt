package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.DnsResolver
import io.github.arttolst12.netion.vpn.PacketUtils
import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DnsSocketProtectionTest {
    @Test
    fun httpDnsProtectsCreatedSocketBeforeConnecting() = assertProxyDnsWorks(socks5 = false)

    @Test
    fun socksDnsProtectsCreatedSocketBeforeConnecting() = assertProxyDnsWorks(socks5 = true)

    private fun assertProxyDnsWorks(socks5: Boolean) {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 2000
            val pool = Executors.newSingleThreadExecutor()
            val sink = ByteArrayOutputStream()
            val captured = AtomicReference<Socket>()
            val resolver = DnsResolver(
                tunWriter = TunWriter(sink),
                proxyIp = "127.0.0.1",
                proxyPort = server.localPort,
                isSocks = socks5,
                protectSocketFn = {
                    val socket = it as Socket
                    captured.set(socket)
                    // Android's protect(Socket) needs a real FD. A successful local bind
                    // guarantees creation without connecting or sending any network traffic.
                    socket.isBound && !socket.isConnected && !socket.isClosed
                }
            )
            val query = query()
            val peer = pool.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    val output = DataOutputStream(socket.getOutputStream())
                    if (socks5) {
                        val greeting = ByteArray(3)
                        input.readFully(greeting)
                        assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
                        output.write(byteArrayOf(5, 0))
                        output.flush()
                        val request = ByteArray(10)
                        input.readFully(request)
                        assertArrayEquals(byteArrayOf(5, 1, 0, 1), request.copyOfRange(0, 4))
                        assertArrayEquals(DNS, request.copyOfRange(4, 8))
                        assertEquals(53, request[9].toInt() and 0xFF)
                        output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1, 0, 53))
                    } else {
                        val headers = readHeaders(input)
                        assertTrue(headers.startsWith("CONNECT 8.8.8.8:53 HTTP/1.1\r\n"))
                        output.writeBytes("HTTP/1.1 200 Connection Established\r\n\r\n")
                    }
                    output.flush()
                    val received = ByteArray(input.readUnsignedShort())
                    input.readFully(received)
                    assertArrayEquals(query, received)
                    val response = responseFor(received)
                    output.writeShort(response.size)
                    output.write(response)
                    output.flush()
                }
            }
            try {
                val status = resolver.resolveViaProxyTcp(CLIENT, DNS, 53000, 53, query, resolver.parseDnsPacket(query))
                assertEquals(DnsResolver.ResolutionStatus.SUCCESS, status)
                peer.get(3, TimeUnit.SECONDS)
                assertTrue("resolver must close the socket after the response", captured.get().isClosed)
                val packet = sink.toByteArray()
                val ip = PacketUtils.parseIpHeader(packet, packet.size)!!
                assertArrayEquals(responseFor(query), PacketUtils.udpPayload(packet, ip))
            } finally {
                resolver.close()
                server.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun realProtectRefusalClosesSocketWithoutConnectingOrFallback() {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 200
            val sink = ByteArrayOutputStream()
            val captured = AtomicReference<Socket>()
            val attempts = AtomicInteger()
            val fallbackAttempts = AtomicInteger()
            val resolver = DnsResolver(
                tunWriter = TunWriter(sink),
                proxyIp = "127.0.0.1",
                proxyPort = server.localPort,
                enablePublicFallback = true,
                protectSocketFn = {
                    captured.set(it as Socket)
                    attempts.incrementAndGet()
                    false
                },
                udpTransportFn = { _, _, _ -> fallbackAttempts.incrementAndGet(); null }
            )
            try {
                val query = query()
                resolver.handleQuery(
                    PacketUtils.IpHeader(20, 28 + query.size, PacketUtils.PROTO_UDP, CLIENT, DNS),
                    PacketUtils.UdpHeader(53000, 53, 8 + query.size), query
                )
                resolver.executor.shutdown()
                assertTrue(resolver.executor.awaitTermination(2, TimeUnit.SECONDS))
                assertEquals(1, attempts.get())
                assertEquals(0, fallbackAttempts.get())
                assertTrue(captured.get().isBound)
                assertTrue(captured.get().isClosed)
                try {
                    server.accept().use { fail("protect=false must prevent any proxy connection") }
                } catch (_: SocketTimeoutException) {
                    // No connection reached the listening proxy.
                }
                val packet = sink.toByteArray()
                val ip = PacketUtils.parseIpHeader(packet, packet.size)!!
                assertEquals("exactly one SERVFAIL packet", packet.size, ip.totalLength)
                val dns = resolver.parseDnsPacket(PacketUtils.udpPayload(packet, ip))!!
                assertEquals(DnsResolver.RCODE_SERVFAIL, dns.rcode)
            } finally {
                resolver.close()
            }
        }
    }

    @Test
    fun closeCancelsRealSocketDuringProxyHandshake() = assertCloseCancelsTcp(waitForDnsQuery = false)

    @Test
    fun closeCancelsRealSocketWhileWaitingForDnsResponse() = assertCloseCancelsTcp(waitForDnsQuery = true)

    private fun assertCloseCancelsTcp(waitForDnsQuery: Boolean) {
        ServerSocket(0, 1, LOOPBACK).use { server ->
            server.soTimeout = 2000
            val pool = Executors.newFixedThreadPool(2)
            val waiting = CountDownLatch(1)
            val sink = ByteArrayOutputStream()
            val captured = AtomicReference<Socket>()
            val resolver = DnsResolver(
                tunWriter = TunWriter(sink), proxyIp = "127.0.0.1", proxyPort = server.localPort,
                protectSocketFn = { captured.set(it as Socket); true }
            )
            val peer = pool.submit<Int> {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val input = DataInputStream(socket.getInputStream())
                    readHeaders(input)
                    if (waitForDnsQuery) {
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                        output.flush()
                        val received = ByteArray(input.readUnsignedShort())
                        input.readFully(received)
                    }
                    waiting.countDown()
                    input.read()
                }
            }
            try {
                val query = query()
                val resolution = pool.submit<DnsResolver.ResolutionStatus> {
                    resolver.resolveViaProxyTcp(CLIENT, DNS, 53000, 53, query, resolver.parseDnsPacket(query))
                }
                assertTrue("fixture must reach a blocking read", waiting.await(2, TimeUnit.SECONDS))
                resolver.close()
                assertEquals(DnsResolver.ResolutionStatus.NETWORK_FAILURE, resolution.get(1, TimeUnit.SECONDS))
                assertEquals("peer must observe socket close", -1, peer.get(1, TimeUnit.SECONDS))
                assertTrue(captured.get().isClosed)
                assertEquals(0, sink.size())
                assertEquals(0, resolver.cache.size())
                resolver.close()
            } finally {
                resolver.close()
                server.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun closeCancelsRealUdpReceive() {
        DatagramSocket(0, LOOPBACK).use { server ->
            server.soTimeout = 2000
            val pool = Executors.newSingleThreadExecutor()
            val sink = ByteArrayOutputStream()
            val captured = AtomicReference<DatagramSocket>()
            val resolver = DnsResolver(
                tunWriter = TunWriter(sink),
                protectSocketFn = { captured.set(it as DatagramSocket); true }
            )
            try {
                val query = query()
                val resolution = pool.submit<DnsResolver.ResolutionStatus> {
                    resolver.resolveViaDirectUdp(CLIENT, LOOPBACK.address, 53000, server.localPort, query, resolver.parseDnsPacket(query))
                }
                val request = DatagramPacket(ByteArray(512), 512)
                server.receive(request)
                resolver.close()
                assertEquals(DnsResolver.ResolutionStatus.NETWORK_FAILURE, resolution.get(1, TimeUnit.SECONDS))
                assertTrue(captured.get().isClosed)
                assertEquals(0, sink.size())
            } finally {
                resolver.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val value = input.read()
            check(value >= 0) { "EOF before HTTP headers" }
            bytes.write(value)
            val text = bytes.toString("US-ASCII")
            if (text.endsWith("\r\n\r\n")) return text
        }
        error("HTTP header limit exceeded")
    }

    private fun query(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00,
        0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 'a'.code.toByte(), 0x00, 0x00, 0x01, 0x00, 0x01
    )

    private fun responseFor(query: ByteArray) = query.copyOf().apply {
        this[2] = 0x81.toByte()
        this[3] = 0x80.toByte()
    }

    private companion object {
        val LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val CLIENT = byteArrayOf(10, 0, 0, 2)
        val DNS = byteArrayOf(8, 8, 8, 8)
    }
}
