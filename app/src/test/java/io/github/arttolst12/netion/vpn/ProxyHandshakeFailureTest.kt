package io.github.arttolst12.netion.vpn

import io.github.arttolst12.netion.DiagnosticText
import io.github.arttolst12.netion.proxy.ProxyServer
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProxyHandshakeFailureTest {
    @Test
    fun httpClientAndRealProxyExchangeData() = assertRealProxyExchange(socks5 = false)

    @Test
    fun socks5ClientAndRealProxyExchangeData() = assertRealProxyExchange(socks5 = true)

    @Test
    fun peerResetDuringHttpResponseKeepsItsCauseAndExactPhase() {
        withHttpProxy({ peer -> peer.setSoLinger(true, 0) }) { port ->
            SocketChannel.open().use { channel ->
                var phase = "not-started"
                try {
                    connect(channel.socket(), port) { phase = it }
                    fail("A peer reset must not establish a tunnel")
                } catch (error: IOException) {
                    assertFalse("Peer reset must not be reported as a timeout", error is SocketTimeoutException)
                    assertTrue(DiagnosticText.failure(error), DiagnosticText.failure(error).startsWith("reason=connection-reset"))
                    assertEquals("http-connect-read", phase)
                    assertTrue(channel.socket().isClosed)
                }
            }
        }
    }

    @Test
    fun rejectedConnectRetainsHttpStatusAndClosesSocket() {
        withHttpProxy({ peer ->
            peer.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
        }) { port ->
            Socket().use { socket ->
                try {
                    connect(socket, port)
                    fail("A rejected CONNECT must not become a direct connection")
                } catch (error: IOException) {
                    assertTrue(error.message.orEmpty().contains("502 Bad Gateway"))
                    assertTrue(socket.isClosed)
                }
            }
        }
    }

    @Test
    fun truncatedSuccessResponseCannotEstablishTunnel() {
        withHttpProxy({ peer ->
            peer.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n".toByteArray())
        }) { port ->
            Socket().use { socket ->
                try {
                    connect(socket, port)
                    fail("An incomplete HTTP response must fail closed")
                } catch (error: IOException) {
                    assertTrue(DiagnosticText.failure(error).startsWith("reason=unexpected-eof"))
                    assertTrue(socket.isClosed)
                }
            }
        }
    }

    @Test
    fun deadlineDuringBlockingWriteIsReportedAsTimeout() {
        val closed = CountDownLatch(1)
        val socket = writeFailureSocket {
            assertTrue("Deadline must close the blocked socket", closed.await(3, TimeUnit.SECONDS))
            throw IOException("Socket closed")
        }.also { it.onClose = { closed.countDown() } }
        socket.use {
            var phase = "not-started"
            try {
                ProxyHandshake.connect(socket, "127.0.0.1", 1, byteArrayOf(127, 0, 0, 1), 443,
                    socks5 = false, timeoutMs = 150, onPhase = { phase = it })
                fail("A blocked handshake write must time out")
            } catch (error: SocketTimeoutException) {
                assertEquals("http-connect-write", phase)
                assertEquals("Socket closed", error.cause?.message)
                assertTrue(socket.isClosed)
            }
        }
    }

    @Test
    fun earlyWriteFailureIsNotRelabeledAsTimeout() {
        writeFailureSocket { throw IOException("Connection reset by peer") }.use { socket ->
            try {
                ProxyHandshake.connect(socket, "127.0.0.1", 1, byteArrayOf(127, 0, 0, 1), 443,
                    socks5 = false, timeoutMs = 2_000)
                fail("A failed write must abort the handshake")
            } catch (error: IOException) {
                assertFalse(error is SocketTimeoutException)
                assertTrue(DiagnosticText.failure(error).startsWith("reason=connection-reset"))
                assertTrue(socket.isClosed)
            }
        }
    }

    private fun assertRealProxyExchange(socks5: Boolean) {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { origin ->
            origin.soTimeout = 3_000
            val pool = Executors.newSingleThreadExecutor()
            val proxy = ProxyServer(requestedHttpPort = 0, requestedSocks5Port = 0)
            try {
                proxy.start()
                val served = pool.submit {
                    origin.accept().use { peer ->
                        peer.soTimeout = 3_000
                        assertEquals(42, peer.getInputStream().read())
                        peer.getOutputStream().write(43)
                    }
                }
                SocketChannel.open().use { channel ->
                    val phases = ArrayList<String>()
                    val socket = channel.socket()
                    ProxyHandshake.connect(socket, "127.0.0.1",
                        if (socks5) proxy.socks5Port else proxy.httpPort,
                        byteArrayOf(127, 0, 0, 1), origin.localPort, socks5, 2_000,
                        onPhase = { phases += it })
                    socket.soTimeout = 2_000
                    socket.getOutputStream().write(42)
                    assertEquals(43, socket.getInputStream().read())
                    assertEquals("proxy-tcp-connect", phases.first())
                    assertEquals(if (socks5) "socks5-connect-read" else "http-connect-read", phases.last())
                }
                served.get(3, TimeUnit.SECONDS)
            } finally {
                proxy.stop()
                origin.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun withHttpProxy(reply: (Socket) -> Unit, test: (Int) -> Unit) {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { listener ->
            listener.soTimeout = 3_000
            val pool = Executors.newSingleThreadExecutor()
            try {
                val served = pool.submit {
                    listener.accept().use { peer ->
                        peer.soTimeout = 3_000
                        val bytes = ByteArrayOutputStream()
                        while (!bytes.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                            val next = peer.getInputStream().read()
                            check(next >= 0 && bytes.size() < 4096) { "Incomplete CONNECT request" }
                            bytes.write(next)
                        }
                        reply(peer)
                    }
                }
                test(listener.localPort)
                served.get(3, TimeUnit.SECONDS)
            } finally {
                listener.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun connect(socket: Socket, proxyPort: Int, onPhase: (String) -> Unit = {}) {
        ProxyHandshake.connect(socket, "127.0.0.1", proxyPort, byteArrayOf(127, 0, 0, 1), 443,
            socks5 = false, timeoutMs = 2_000, onPhase = onPhase)
    }

    private class WriteFailureSocket(private val write: () -> Unit) : Socket() {
        var onClose: () -> Unit = {}
        override fun connect(endpoint: SocketAddress?, timeout: Int) = Unit
        override fun getInputStream() = java.io.ByteArrayInputStream(ByteArray(0))
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) = write()
            override fun write(bytes: ByteArray, offset: Int, length: Int) = write()
        }
        override fun close() {
            super.close()
            onClose()
        }
    }

    private fun writeFailureSocket(write: () -> Unit) = WriteFailureSocket(write)
}
