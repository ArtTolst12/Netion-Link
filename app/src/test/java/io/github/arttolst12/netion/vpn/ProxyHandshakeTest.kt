package io.github.arttolst12.netion.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ProxyHandshakeTest {

    @Test
    fun socks5HandshakeUsesIpv4AndDrainsBoundAddress() {
        val served = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                listener.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    assertArrayEquals(byteArrayOf(0x05, 0x01, 0x00), readExact(input = input, count = 3))
                    output.write(byteArrayOf(0x05, 0x00))
                    output.flush()

                    assertArrayEquals(
                        byteArrayOf(
                            0x05, 0x01, 0x00, 0x01,
                            203.toByte(), 0, 113, 7,
                            0x01, 0xBB.toByte()
                        ),
                        readExact(input, 10)
                    )
                    // Success with an IPv4 BND.ADDR and BND.PORT.
                    output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0x12, 0x34))
                    output.flush()
                }
            } catch (t: Throwable) {
                serverFailure.set(t)
            } finally {
                served.countDown()
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            Socket().use { socket ->
                ProxyHandshake.connect(
                    socket = socket,
                    proxyIp = "127.0.0.1",
                    proxyPort = listener.localPort,
                    targetIp = byteArrayOf(203.toByte(), 0, 113, 7),
                    targetPort = 443,
                    socks5 = true,
                    timeoutMs = 2_000
                )
                assertTrue(socket.isConnected)
            }
            assertTrue("SOCKS5 test proxy did not complete", served.await(2, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw AssertionError("SOCKS5 test proxy failed", it) }
        } finally {
            listener.close()
            worker.join(2_000)
        }
    }

    @Test
    fun httpConnectRequiresExact200AndFullHeaderTerminator() {
        val served = CountDownLatch(1)
        val serverFailure = AtomicReference<Throwable?>()
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                listener.accept().use { socket ->
                    val request = readHttpHeader(socket)
                    assertEquals(
                        "CONNECT 203.0.113.7:443 HTTP/1.1\r\nHost: 203.0.113.7:443\r\n\r\n",
                        request
                    )
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: loopback\r\n\r\n".toByteArray())
                        flush()
                    }
                }
            } catch (t: Throwable) {
                serverFailure.set(t)
            } finally {
                served.countDown()
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            Socket().use { socket ->
                ProxyHandshake.connect(
                    socket = socket,
                    proxyIp = "127.0.0.1",
                    proxyPort = listener.localPort,
                    targetIp = byteArrayOf(203.toByte(), 0, 113, 7),
                    targetPort = 443,
                    socks5 = false,
                    timeoutMs = 2_000
                )
            }
            assertTrue("HTTP test proxy did not complete", served.await(2, TimeUnit.SECONDS))
            serverFailure.get()?.let { throw AssertionError("HTTP test proxy failed", it) }
        } finally {
            listener.close()
            worker.join(2_000)
        }
    }

    @Test
    fun socks5RejectsInvalidResponseReservedByte() {
        val served = CountDownLatch(1)
        val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            try {
                listener.accept().use { socket ->
                    val input = socket.getInputStream()
                    val output = socket.getOutputStream()
                    readExact(input, 3)
                    output.write(byteArrayOf(0x05, 0x00))
                    output.flush()
                    readExact(input, 10)
                    // RSV must be zero; a successful REP alone is insufficient.
                    output.write(byteArrayOf(0x05, 0x00, 0x01, 0x01, 127, 0, 0, 1, 0, 0))
                    output.flush()
                }
            } finally {
                served.countDown()
            }
        }.also { it.isDaemon = true; it.start() }

        try {
            Socket().use { socket ->
                try {
                    ProxyHandshake.connect(
                        socket = socket,
                        proxyIp = "127.0.0.1",
                        proxyPort = listener.localPort,
                        targetIp = byteArrayOf(127, 0, 0, 1),
                        targetPort = 80,
                        socks5 = true,
                        timeoutMs = 2_000
                    )
                    fail("A non-zero SOCKS5 RSV must be rejected")
                } catch (_: IOException) {
                    // Expected: callers own closing this socket after a failed handshake.
                }
            }
            assertTrue("SOCKS5 test proxy did not complete", served.await(2, TimeUnit.SECONDS))
        } finally {
            listener.close()
            worker.join(2_000)
        }
    }

    private fun readHttpHeader(socket: Socket): String {
        val input = socket.getInputStream()
        val bytes = ByteArrayOutputStream()
        var match = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
        while (bytes.size() < 4 * 1024) {
            val value = input.read()
            if (value < 0) throw IOException("Unexpected EOF while reading HTTP request")
            val byte = value.toByte()
            bytes.write(value)
            if (byte == terminator[match]) {
                match++
                if (match == terminator.size) return bytes.toString(Charsets.ISO_8859_1.name())
            } else {
                match = if (byte == terminator[0]) 1 else 0
            }
        }
        throw IOException("HTTP request header exceeded test limit")
    }

    private fun readExact(input: java.io.InputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IOException("Unexpected EOF")
            offset += read
        }
        return bytes
    }
}
