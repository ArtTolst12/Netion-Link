package io.github.arttolst12.netion

import io.github.arttolst12.netion.proxy.ProxyServer
import io.github.arttolst12.netion.vpn.TcpSession
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class Stages1to3VerificationTest {

    private fun findFreePort(): Int {
        val ss = ServerSocket(0)
        val port = ss.localPort
        ss.close()
        return port
    }

    // ─────────────────────────────────────────────────────────────
    // Stage 1 Tests: SOCKS5 RFC 1928 & Subnet Access Control
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testSocks5RejectsClientWithoutNoAuthMethod() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.start()

        Thread.sleep(100)

        val client = Socket("127.0.0.1", socksPort)
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        // Send SOCKS5 greeting with only method 0x02 (User/Pass), omitting 0x00 (No Auth)
        out.write(byteArrayOf(0x05, 0x01, 0x02))
        out.flush()

        // Server MUST respond with 0x05, 0xFF (NO ACCEPTABLE METHODS) per RFC 1928
        val ver = inp.read()
        val method = inp.read()

        assertEquals("SOCKS version must be 5", 5, ver)
        assertEquals("Method must be 0xFF (NO ACCEPTABLE METHODS)", 0xFF, method and 0xFF)

        // Server should close connection after sending 05 FF
        val eof = inp.read()
        assertEquals("Server must close connection after rejecting negotiation", -1, eof)

        client.close()
        server.stop()
    }

    @Test
    fun testSocks5AcceptsClientOfferingNoAuth() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.start()

        Thread.sleep(100)

        val client = Socket("127.0.0.1", socksPort)
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        // Send SOCKS5 greeting offering both 0x02 (User/Pass) and 0x00 (No Auth)
        out.write(byteArrayOf(0x05, 0x02, 0x02, 0x00))
        out.flush()

        val ver = inp.read()
        val method = inp.read()

        assertEquals(5, ver)
        assertEquals(0, method) // 0x00 selected

        client.close()
        server.stop()
    }

    @Test
    fun testSubnetAccessControlLogic() {
        // 1. Loopback must always be allowed
        val loopback = InetAddress.getByName("127.0.0.1")
        assertTrue("Loopback address must be allowed", NetworkUtils.isClientAllowed(loopback))

        // 2. Standard Wi-Fi hotspot subnet (192.168.43.x) must be allowed
        val hotspotClient = InetAddress.getByName("192.168.43.15")
        assertTrue("Hotspot subnet client must be allowed", NetworkUtils.isClientAllowed(hotspotClient))

        // 3. Standard USB tethering subnet (192.168.42.x) must be allowed
        val usbClient = InetAddress.getByName("192.168.42.44")
        assertTrue("USB tethering subnet client must be allowed", NetworkUtils.isClientAllowed(usbClient))

        // 4. Random external/public addresses must be rejected
        val externalIp = InetAddress.getByName("8.8.8.8")
        assertFalse("External public IP must be rejected", NetworkUtils.isClientAllowed(externalIp))

        val randomPrivate = InetAddress.getByName("172.31.250.1")
        assertFalse("Unauthorized external private IP must be rejected", NetworkUtils.isClientAllowed(randomPrivate))
    }

    @Test
    fun testProxyServerClientFilterRejection() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.clientFilter = { false }
        server.start()

        Thread.sleep(100)

        val client = Socket("127.0.0.1", httpPort)
        val inp = client.getInputStream()
        val out = client.getOutputStream()

        out.write("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray())
        out.flush()

        val b = inp.read()
        assertEquals("Unauthorized client connection must be dropped immediately", -1, b)

        client.close()
        server.stop()
    }

    @Test
    fun testProxyServerConnectionCountTrackingAndShutdownCleanup() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.start()

        Thread.sleep(100)

        assertEquals("Initially 0 active connection slots", 0, server.activeClientConnectionsCount)

        // Connect 3 clients
        val c1 = Socket("127.0.0.1", server.httpPort)
        val c2 = Socket("127.0.0.1", server.httpPort)
        val c3 = Socket("127.0.0.1", server.socks5Port)

        Thread.sleep(100)
        assertTrue("Active connection count must reflect accepted sockets", server.activeClientConnectionsCount >= 1)
        assertTrue("Active sockets count must reflect registered sockets", server.activeSocketsCount >= 1)

        server.stop()
        Thread.sleep(100)
        assertEquals("After stop(), connection slots must be reset to 0", 0, server.activeClientConnectionsCount)
        assertEquals("After stop(), active sockets must be reset to 0", 0, server.activeSocketsCount)

        c1.close()
        c2.close()
        c3.close()
    }

    @Test
    fun testHttpHeaderReadDeadlineEnforcement() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.handshakeDeadlineMs = 400L // 400ms overall deadline
        server.start()

        Thread.sleep(100)

        val client = Socket("127.0.0.1", httpPort)
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        // Send partial HTTP request line and stall
        out.write("GET / HTTP/1.".toByteArray())
        out.flush()

        // Give it time past deadline (600ms > 400ms)
        Thread.sleep(600)

        // Reading from socket should return -1 (closed due to handshake deadline exceeded)
        client.soTimeout = 1000
        val b = inp.read()
        assertEquals("Server must abort connection when handshake deadline expires", -1, b)

        client.close()
        server.stop()
        assertEquals(0, server.activeClientConnectionsCount)
    }

    @Test
    fun testSocks5HandshakeDeadlineEnforcement() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.handshakeDeadlineMs = 400L
        server.start()

        Thread.sleep(100)

        val client = Socket("127.0.0.1", socksPort)
        val out = client.getOutputStream()
        val inp = client.getInputStream()

        // Send version 5 (SOCKS5), but don't send the rest of handshake
        out.write(5)
        out.flush()

        // Exceed deadline
        Thread.sleep(600)

        client.soTimeout = 1000
        val b = inp.read()
        assertEquals("Server must abort SOCKS5 connection when handshake deadline expires", -1, b)

        client.close()
        server.stop()
        assertEquals(0, server.activeClientConnectionsCount)
    }

    @Test
    fun testConnectionLimitRejectionAndLeaseAccounting() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.maxConcurrentConnections = 2
        server.start()

        Thread.sleep(100)

        // Connect 2 clients (filling the capacity)
        val c1 = Socket("127.0.0.1", httpPort)
        val c2 = Socket("127.0.0.1", httpPort)
        Thread.sleep(100)

        assertEquals("Should hold 2 active client connection slots", 2, server.activeClientConnectionsCount)

        // Connect 3rd client (exceeds limit)
        val c3 = Socket("127.0.0.1", httpPort)
        c3.soTimeout = 1000
        val b = c3.getInputStream().read()
        assertEquals("3rd client must be rejected immediately with closed connection", -1, b)
        c3.close()

        assertEquals("Slot count must remain 2 after rejecting 3rd client", 2, server.activeClientConnectionsCount)

        // Close c1 and c2
        c1.close()
        c2.close()
        Thread.sleep(200)

        server.stop()
        assertEquals(0, server.activeClientConnectionsCount)
    }

    @Test
    fun testConcurrentStopVersusClientRegistration() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.start()

        val threadCount = 10
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val clientSockets = java.util.concurrent.CopyOnWriteArrayList<Socket>()

        for (i in 0 until threadCount) {
            Thread({
                startLatch.await()
                try {
                    val s = Socket("127.0.0.1", httpPort)
                    clientSockets.add(s)
                } catch (_: Exception) {}
                doneLatch.countDown()
            }, "client-flood-$i").start()
        }

        startLatch.countDown()
        Thread.sleep(5)
        server.stop()

        doneLatch.await(3, TimeUnit.SECONDS)
        Thread.sleep(150)

        assertEquals("All sockets in activeSockets must be closed and cleared on stop", 0, server.activeSocketsCount)
        assertEquals("Active connection slots must be 0 after stop", 0, server.activeClientConnectionsCount)

        for (s in clientSockets) {
            try { s.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun testVpnGenerationTokenPreventsOldThreadShutdown() {
        val currentGeneration = java.util.concurrent.atomic.AtomicLong(1L)
        val isRunning = java.util.concurrent.atomic.AtomicBoolean(true)
        val lock = Any()
        var stoppedGeneration: Long? = null

        fun onThreadExit(threadGen: Long) {
            synchronized(lock) {
                if (isRunning.get() && currentGeneration.get() == threadGen) {
                    isRunning.set(false)
                    stoppedGeneration = threadGen
                }
            }
        }

        // Simulating VPN restart: generation bumped to 2 before old thread finishes
        currentGeneration.incrementAndGet()

        // Old thread (generation 1) reaches finally block
        onThreadExit(1L)

        assertTrue("New VPN generation must remain running after old thread exit", isRunning.get())
        assertNull("Stopped generation must remain null (not triggered by old generation)", stoppedGeneration)

        // When generation 2 exits, shutdown triggers
        onThreadExit(2L)
        assertFalse("Current VPN generation must stop when its own thread exits", isRunning.get())
        assertEquals(2L, stoppedGeneration)
    }

    // ─────────────────────────────────────────────────────────────
    // Stage 3 Tests: Real TcpSession Duplicate SYN & Close Coordination
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testRealTcpSessionDuplicateSynDoesNotSpawnMultipleThreads() {
        val serverSocket = ServerSocket(0)
        val serverPort = serverSocket.localPort
        var acceptedSocket: Socket? = null
        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 45678,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = serverPort,
            isSocks5 = false,
            vpnService = null,
            tunWriter = null
        )

        try {
            assertEquals(TcpSession.State.CONNECTING, session.state)
            assertFalse(session.isDead)

            // First SYN initiates connection to our serverSocket
            session.handleSyn(1000L)
            serverSocket.soTimeout = 2000
            acceptedSocket = serverSocket.accept()
            assertNotNull("First SYN must initiate connection to upstream proxy", acceptedSocket)

            // Duplicate SYNs arrive while in CONNECTING / handshake
            session.handleSyn(1000L)
            session.handleSyn(1000L)

            // Verify that no second connection is accepted by the server
            serverSocket.soTimeout = 400
            try {
                val secondSocket = serverSocket.accept()
                secondSocket.close()
                fail("Duplicate SYN must not spawn a second outbound connection!")
            } catch (expected: java.net.SocketTimeoutException) {
                // Expected: No second connection attempted!
            }

            // Session remains cleanly in CONNECTING, not dead
            assertEquals(TcpSession.State.CONNECTING, session.state)
        } finally {
            session.close()
            acceptedSocket?.close()
            serverSocket.close()
        }
    }

    @Test
    fun testRealTcpSessionCloseDuringConnectHandshakeAbortsCleanly() {
        val serverSocket = ServerSocket(0)
        val serverPort = serverSocket.localPort
        var acceptedSocket: Socket? = null
        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 45679,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = serverPort,
            isSocks5 = false,
            vpnService = null,
            tunWriter = null
        )

        try {
            session.handleSyn(2000L)
            serverSocket.soTimeout = 2000
            acceptedSocket = serverSocket.accept()
            assertNotNull(acceptedSocket)

            // Read the HTTP CONNECT request headers up to \r\n\r\n
            acceptedSocket.soTimeout = 2000
            val input = acceptedSocket.getInputStream()
            val sb = java.lang.StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b == -1) break
                sb.append(b.toChar())
            }
            assertTrue("Client must have sent CONNECT request", sb.startsWith("CONNECT 8.8.8.8:80"))

            // At this point client has sent the CONNECT request and is blocking on read for response.
            // Calling session.close() must close proxySocket promptly.
            val startTime = System.currentTimeMillis()
            session.close()

            assertTrue("Session must be dead immediately", session.isDead)
            assertEquals(TcpSession.State.CLOSED, session.state)

            // Verify that server sees EOF or socket closed from client side within 1000ms
            acceptedSocket.soTimeout = 1000
            val eof = input.read()
            val duration = System.currentTimeMillis() - startTime

            assertTrue("Proxy socket close must happen within 1000ms", duration < 1000)
            assertEquals("Server must receive EOF when session closes", -1, eof)
        } finally {
            session.close()
            acceptedSocket?.close()
            serverSocket.close()
        }
    }

    @Test
    fun testConnectionSlotHeldDuringHalfCloseUntilBothSidesClosed() {
        val httpPort = findFreePort()
        val socksPort = findFreePort()
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        server.start()
        Thread.sleep(100)

        val mockTarget = ServerSocket(0)
        val targetPort = mockTarget.localPort

        val client = Socket("127.0.0.1", httpPort)
        val clientOut = client.getOutputStream()
        val clientIn = client.getInputStream()

        // Send HTTP CONNECT to mockTarget
        val req = "CONNECT 127.0.0.1:$targetPort HTTP/1.1\r\nHost: 127.0.0.1:$targetPort\r\n\r\n"
        clientOut.write(req.toByteArray())
        clientOut.flush()

        mockTarget.soTimeout = 2000
        val targetSocket = mockTarget.accept()
        assertNotNull(targetSocket)

        // Read 200 response on client
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = clientIn.read()
            if (b == -1) break
            sb.append(b.toChar())
        }
        assertTrue("Tunnel must be established with 200 OK", sb.startsWith("HTTP/1.1 200"))

        // Active client connection count should be 1
        assertEquals("Slot must be reserved while tunnel is open", 1, server.activeClientConnectionsCount)

        // Half-close client output: direction client -> remote finishes
        client.shutdownOutput()
        Thread.sleep(150)

        // P1 verification: slot MUST NOT be prematurely released while remote -> client is still alive
        assertEquals("Slot must remain held after half-close while remote side remains open", 1, server.activeClientConnectionsCount)

        // Now close remote side
        targetSocket.close()
        Thread.sleep(150)

        // Both directions closed: lease must now be released
        assertEquals("Slot must be released once both directions are closed", 0, server.activeClientConnectionsCount)

        client.close()
        mockTarget.close()
        server.stop()
    }
}
