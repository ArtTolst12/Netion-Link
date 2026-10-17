package com.example.netion

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ProxyProtocolTest {

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = hostPort.trim()
        return if (trimmed.startsWith('[')) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket != -1) {
                val h = trimmed.substring(1, closeBracket)
                val p = if (closeBracket + 1 < trimmed.length && trimmed[closeBracket + 1] == ':') {
                    trimmed.substring(closeBracket + 2).toIntOrNull() ?: defaultPort
                } else {
                    defaultPort
                }
                Pair(h, p)
            } else {
                Pair(trimmed.trim('[', ']'), defaultPort)
            }
        } else if (trimmed.contains(':')) {
            val idx = trimmed.lastIndexOf(':')
            val h = trimmed.substring(0, idx)
            val p = trimmed.substring(idx + 1).toIntOrNull() ?: defaultPort
            Pair(h, p)
        } else {
            Pair(trimmed, defaultPort)
        }
    }

    private fun readSafeLine(input: ByteArrayInputStream, maxLen: Int = 8192): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code && prev == '\r'.code) {
                return sb.dropLast(1).toString()
            }
            if (sb.length >= maxLen) {
                return null // Guard against unbounded memory allocation
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    @Test
    fun testParseHostPortStandard() {
        val (h1, p1) = parseHostPort("example.com:8080", 80)
        assertEquals("example.com", h1)
        assertEquals(8080, p1)

        val (h2, p2) = parseHostPort("example.com", 80)
        assertEquals("example.com", h2)
        assertEquals(80, p2)

        val (h3, p3) = parseHostPort("192.168.1.100:1080", 1080)
        assertEquals("192.168.1.100", h3)
        assertEquals(1080, p3)
    }

    @Test
    fun testParseHostPortIpv6() {
        val (h1, p1) = parseHostPort("[2001:db8::1]:443", 443)
        assertEquals("2001:db8::1", h1)
        assertEquals(443, p1)

        val (h2, p2) = parseHostPort("[::1]", 80)
        assertEquals("::1", h2)
        assertEquals(80, p2)
    }

    @Test
    fun testReadSafeLineNormal() {
        val data = "CONNECT api.example.com:443 HTTP/1.1\r\nHost: api.example.com\r\n\r\n".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(data)

        val line1 = readSafeLine(stream)
        assertEquals("CONNECT api.example.com:443 HTTP/1.1", line1)

        val line2 = readSafeLine(stream)
        assertEquals("Host: api.example.com", line2)

        val line3 = readSafeLine(stream)
        assertEquals("", line3)
    }

    @Test
    fun testReadSafeLineExceedsMax() {
        // Line without CRLF longer than limit
        val bigData = ByteArray(100) { 'A'.code.toByte() }
        val stream = ByteArrayInputStream(bigData)

        val line = readSafeLine(stream, maxLen = 50)
        assertNull("Exceeding max line length must return null to reject unbounded memory growth", line)
    }

    @Test
    fun testSocks5GreetingValidation() {
        // Client sends VER=5, NMETHODS=2 (0x00 No Auth, 0x02 User/Pass)
        val greeting = byteArrayOf(0x05, 0x02, 0x00, 0x02)
        val stream = ByteArrayInputStream(greeting)

        val ver = stream.read()
        assertEquals(5, ver)

        val nmethods = stream.read()
        assertEquals(2, nmethods)

        val methods = ByteArray(nmethods)
        stream.read(methods)
        assertTrue(methods.contains(0x00)) // supports No Auth
    }

    @Test
    fun testSocks5Ipv4ConnectCommand() {
        // VER=5, CMD=1 (CONNECT), RSV=0, ATYP=1 (IPv4), IP=93.184.216.34, PORT=80 (0x0050)
        val req = byteArrayOf(
            0x05, 0x01, 0x00, 0x01,
            93.toByte(), 184.toByte(), 216.toByte(), 34.toByte(),
            0x00, 0x50
        )
        val stream = ByteArrayInputStream(req)

        assertEquals(5, stream.read()) // VER
        assertEquals(1, stream.read()) // CMD CONNECT
        assertEquals(0, stream.read()) // RSV
        val atyp = stream.read()
        assertEquals(1, atyp) // IPv4

        val ip = ByteArray(4)
        stream.read(ip)
        val ipStr = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}.${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
        assertEquals("93.184.216.34", ipStr)

        val p1 = stream.read()
        val p2 = stream.read()
        val port = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)
        assertEquals(80, port)
    }

    @Test
    fun testSocks5DomainConnectCommand() {
        // VER=5, CMD=1, RSV=0, ATYP=3 (Domain), length=11, "example.com", PORT=443 (0x01BB)
        val domainBytes = "example.com".toByteArray(Charsets.UTF_8)
        val buf = mutableListOf<Byte>()
        buf.add(0x05)
        buf.add(0x01)
        buf.add(0x00)
        buf.add(0x03)
        buf.add(domainBytes.size.toByte())
        domainBytes.forEach { buf.add(it) }
        buf.add(0x01)
        buf.add(0xBB.toByte())

        val stream = ByteArrayInputStream(buf.toByteArray())
        assertEquals(5, stream.read())
        assertEquals(1, stream.read())
        assertEquals(0, stream.read())
        assertEquals(3, stream.read()) // Domain name ATYP

        val len = stream.read()
        assertEquals(11, len)
        val dom = ByteArray(len)
        stream.read(dom)
        assertEquals("example.com", String(dom, Charsets.UTF_8))

        val p1 = stream.read()
        val p2 = stream.read()
        val port = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)
        assertEquals(443, port)
    }

    @Test
    fun testActiveSocketsCleanupOnFailedHandshake() {
        val httpPort = com.example.netion.NetworkUtils.findAvailablePort(18080)
        val socksPort = com.example.netion.NetworkUtils.findAvailablePort(19080, setOf(httpPort))
        val server = com.example.netion.proxy.ProxyServer(httpPort, socksPort)
        server.start()
        Thread.sleep(150)
        try {
            assertEquals(0, server.activeSocketsCount)

            // 1. Connect a client that sends invalid SOCKS handshake
            val client1 = java.net.Socket("127.0.0.1", server.httpPort)
            val out1 = client1.getOutputStream()
            out1.write(byteArrayOf(0x05, 0x01, 0xFF.toByte())) // invalid auth method
            out1.flush()
            val in1 = client1.getInputStream()
            in1.read() // read EOF or reply
            client1.close()

            val deadline1 = System.currentTimeMillis() + 2000
            while (server.activeSocketsCount > 0 && System.currentTimeMillis() < deadline1) {
                Thread.sleep(20)
            }
            assertEquals("activeSockets must be 0 after failed SOCKS handshake", 0, server.activeSocketsCount)

            // 2. Connect a client that sends garbage HTTP request line
            val client2 = java.net.Socket("127.0.0.1", server.httpPort)
            val out2 = client2.getOutputStream()
            out2.write("INVALID_REQUEST_LINE\r\n".toByteArray())
            out2.flush()
            val in2 = client2.getInputStream()
            in2.read() // read EOF
            client2.close()

            val deadline2 = System.currentTimeMillis() + 2000
            while (server.activeSocketsCount > 0 && System.currentTimeMillis() < deadline2) {
                Thread.sleep(20)
            }
            assertEquals("activeSockets must be 0 after malformed HTTP request", 0, server.activeSocketsCount)
        } finally {
            server.stop()
        }
    }

    @Test
    fun testTcpHalfCloseBidirectionalTransfer() {
        val mockBackend = java.net.ServerSocket(0)
        val backendPort = mockBackend.localPort

        val backendThread = Thread {
            try {
                val backendSocket = mockBackend.accept()
                val bIn = backendSocket.getInputStream()
                val bOut = backendSocket.getOutputStream()

                // Read until client half-closes its output (EOF)
                val received = java.io.ByteArrayOutputStream()
                val buf = ByteArray(1024)
                var n: Int
                while (bIn.read(buf).also { n = it } != -1) {
                    received.write(buf, 0, n)
                }

                assertEquals("HELLO_FROM_CLIENT_HALF_CLOSE", received.toString("UTF-8"))

                // Send response back to client now that client input reached EOF
                bOut.write("RESPONSE_AFTER_HALF_CLOSE".toByteArray(Charsets.UTF_8))
                bOut.flush()
                backendSocket.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        backendThread.isDaemon = true
        backendThread.start()

        val httpPort = com.example.netion.NetworkUtils.findAvailablePort(28080)
        val socksPort = com.example.netion.NetworkUtils.findAvailablePort(29080, setOf(httpPort))
        val server = com.example.netion.proxy.ProxyServer(httpPort, socksPort)
        server.start()
        Thread.sleep(150)

        try {
            val client = java.net.Socket("127.0.0.1", server.httpPort)
            client.soTimeout = 5000
            val cOut = client.getOutputStream()
            val cIn = client.getInputStream()

            // SOCKS / HTTP CONNECT to mockBackend
            val req = "CONNECT 127.0.0.1:$backendPort HTTP/1.1\r\nHost: 127.0.0.1:$backendPort\r\n\r\n"
            cOut.write(req.toByteArray(Charsets.UTF_8))
            cOut.flush()

            // Read 200 Connection Established
            val reader = java.io.BufferedReader(java.io.InputStreamReader(cIn, Charsets.UTF_8))
            val statusLine = reader.readLine()
            assertTrue(statusLine != null && statusLine.contains("200"))
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val socketDeadline = System.currentTimeMillis() + 1000
            while (server.activeSocketsCount < 2 && System.currentTimeMillis() < socketDeadline) {
                Thread.sleep(10)
            }
            assertTrue(server.activeSocketsCount >= 2)

            // Send payload to backend
            cOut.write("HELLO_FROM_CLIENT_HALF_CLOSE".toByteArray(Charsets.UTF_8))
            cOut.flush()

            // Half-close client output!
            client.shutdownOutput()

            // Read response from backend through proxy
            val respBuf = ByteArray(1024)
            val bytesRead = cIn.read(respBuf)
            assertTrue("Should read response from backend despite client half-close", bytesRead > 0)
            val respStr = String(respBuf, 0, bytesRead, Charsets.UTF_8)
            assertEquals("RESPONSE_AFTER_HALF_CLOSE", respStr)

            client.close()
            backendThread.join(2000)

            // Wait for proxy to clean up activeSockets
            val deadline = System.currentTimeMillis() + 3000
            while (server.activeSocketsCount > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals("activeSockets must be empty after connections close", 0, server.activeSocketsCount)
        } finally {
            server.stop()
            try { mockBackend.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun testServerStopCleansUpActiveSockets() {
        val httpPort = com.example.netion.NetworkUtils.findAvailablePort(38080)
        val socksPort = com.example.netion.NetworkUtils.findAvailablePort(39080, setOf(httpPort))
        val server = com.example.netion.proxy.ProxyServer(httpPort, socksPort)
        server.start()
        Thread.sleep(150)

        try {
            val client = java.net.Socket("127.0.0.1", server.httpPort)
            val deadline = System.currentTimeMillis() + 1000
            while (server.activeSocketsCount == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(1, server.activeSocketsCount)

            server.stop()
            assertEquals("activeSockets must be 0 after server.stop()", 0, server.activeSocketsCount)
            client.close()
        } finally {
            server.stop()
        }
    }

    @Test
    fun testServerStopDuringActiveTunnelCleansUpAndSendsNoConflictingError() {
        val mockBackend = java.net.ServerSocket(0)
        val backendPort = mockBackend.localPort

        val backendThread = Thread {
            try {
                val backendSocket = mockBackend.accept()
                // Keep connection open until closed by proxy stop
                val bIn = backendSocket.getInputStream()
                while (bIn.read() != -1) {}
                backendSocket.close()
            } catch (_: Exception) {}
        }
        backendThread.isDaemon = true
        backendThread.start()

        val httpPort = com.example.netion.NetworkUtils.findAvailablePort(48080)
        val socksPort = com.example.netion.NetworkUtils.findAvailablePort(49080, setOf(httpPort))
        val server = com.example.netion.proxy.ProxyServer(httpPort, socksPort)
        server.start()
        Thread.sleep(150)

        try {
            val client = java.net.Socket("127.0.0.1", server.httpPort)
            client.soTimeout = 5000
            val cOut = client.getOutputStream()
            val cIn = client.getInputStream()

            val req = "CONNECT 127.0.0.1:$backendPort HTTP/1.1\r\nHost: 127.0.0.1:$backendPort\r\n\r\n"
            cOut.write(req.toByteArray(Charsets.UTF_8))
            cOut.flush()

            val reader = java.io.BufferedReader(java.io.InputStreamReader(cIn, Charsets.UTF_8))
            val statusLine = reader.readLine()
            assertTrue(statusLine != null && statusLine.contains("200"))
            while (true) {
                val line = reader.readLine()
                if (line.isNullOrEmpty()) break
            }

            val socketDeadline = System.currentTimeMillis() + 1000
            while (server.activeSocketsCount < 2 && System.currentTimeMillis() < socketDeadline) {
                Thread.sleep(10)
            }
            assertTrue(server.activeSocketsCount >= 2)

            // Stop server while tunnel is active
            server.stop()
            assertEquals("activeSockets must be 0 after server.stop()", 0, server.activeSocketsCount)

            // The client stream should encounter EOF or socket closed, and MUST NOT receive "502 Bad Gateway"
            val remaining = reader.readLine()
            assertTrue("Client must not receive duplicate 502 error status after 200", remaining == null || !remaining.contains("502"))

            client.close()
        } finally {
            server.stop()
            try { mockBackend.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun testTcpPacketBuildAndParse() {
        val srcIp = byteArrayOf(10, 0, 0, 2)
        val dstIp = byteArrayOf(93, (-72).toByte(), (-40).toByte(), 34)
        val payload = "HELLO_TCP_PACKET".toByteArray(Charsets.UTF_8)
        val seq = 123456789L
        val ack = 987654321L

        val packet = com.example.netion.vpn.PacketUtils.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 54321,
            dstPort = 443,
            seqNum = seq,
            ackNum = ack,
            flags = com.example.netion.vpn.PacketUtils.TCP_ACK or com.example.netion.vpn.PacketUtils.TCP_PSH,
            payload = payload,
            includeMss = true
        )

        val ipHeader = com.example.netion.vpn.PacketUtils.parseIpHeader(packet, packet.size)
        assertNotNull(ipHeader)
        assertEquals(20, ipHeader!!.ihl)
        assertEquals(com.example.netion.vpn.PacketUtils.PROTO_TCP, ipHeader.protocol)
        assertArrayEquals(srcIp, ipHeader.srcIp)
        assertArrayEquals(dstIp, ipHeader.dstIp)

        // Verify IP checksum
        val ipCksum = com.example.netion.vpn.PacketUtils.checksum(packet, 0, 20)
        assertEquals(0, ipCksum)

        val tcpHeader = com.example.netion.vpn.PacketUtils.parseTcpHeader(packet, ipHeader.ihl)
        assertNotNull(tcpHeader)
        assertEquals(54321, tcpHeader!!.srcPort)
        assertEquals(443, tcpHeader.dstPort)
        assertEquals(seq, tcpHeader.seqNum)
        assertEquals(ack, tcpHeader.ackNum)
        assertEquals(24, tcpHeader.dataOffset) // With MSS option = 24 bytes

        val parsedPayload = com.example.netion.vpn.PacketUtils.tcpPayload(packet, ipHeader, tcpHeader)
        assertArrayEquals(payload, parsedPayload)
    }

    @Test
    fun testTcpSequenceDeduplicationLogic() {
        var theirSeq = 1000L

        fun processSegment(seqNum: Long, payloadSize: Int): Pair<Int, Boolean> {
            val diff = (seqNum - theirSeq) and 0xFFFFFFFFL
            return if (diff == 0L) {
                theirSeq = (theirSeq + payloadSize) and 0xFFFFFFFFL
                Pair(payloadSize, true) // forward all
            } else if (diff > 0x7FFFFFFFL) {
                val alreadyReceived = ((theirSeq - seqNum) and 0xFFFFFFFFL).toInt()
                if (alreadyReceived >= payloadSize) {
                    Pair(0, false) // duplicate, do not forward
                } else {
                    val newBytes = payloadSize - alreadyReceived
                    theirSeq = (theirSeq + newBytes) and 0xFFFFFFFFL
                    Pair(newBytes, true) // forward slice
                }
            } else {
                Pair(0, false) // out of order, request retransmission
            }
        }

        // 1. Initial in-order segment (1000..1500)
        val (f1, ok1) = processSegment(1000L, 500)
        assertEquals(500, f1)
        assertTrue(ok1)
        assertEquals(1500L, theirSeq)

        // 2. Retransmission of exact same segment (1000..1500)
        val (f2, ok2) = processSegment(1000L, 500)
        assertEquals(0, f2)
        assertFalse("Duplicate segment must not be forwarded to proxy", ok2)
        assertEquals(1500L, theirSeq)

        // 3. Partially overlapping retransmission (1200..1700) -> 300 duplicate, 200 new
        val (f3, ok3) = processSegment(1200L, 500)
        assertEquals(200, f3)
        assertTrue(ok3)
        assertEquals(1700L, theirSeq)

        // 4. In-order next segment (1700..2200)
        val (f4, ok4) = processSegment(1700L, 500)
        assertEquals(500, f4)
        assertTrue(ok4)
        assertEquals(2200L, theirSeq)
    }

    @Test
    fun testDnsOverTcpFramingAndPackaging() {
        val rawDnsQuery = byteArrayOf(
            0x12, 0x34, // ID
            0x01, 0x00, // standard query
            0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 // 1 Question
        )
        // RFC 1035: 2-byte prefix length
        val framed = ByteArray(2 + rawDnsQuery.size)
        framed[0] = (rawDnsQuery.size shr 8).toByte()
        framed[1] = (rawDnsQuery.size and 0xFF).toByte()
        System.arraycopy(rawDnsQuery, 0, framed, 2, rawDnsQuery.size)

        val parsedLen = ((framed[0].toInt() and 0xFF) shl 8) or (framed[1].toInt() and 0xFF)
        assertEquals(rawDnsQuery.size, parsedLen)

        // Mock DNS response
        val dnsResponsePayload = byteArrayOf(
            0x12, 0x34,
            0x81.toByte(), 0x80.toByte(), // Standard query response, No error
            0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00
        )
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val udpPacket = com.example.netion.vpn.PacketUtils.buildUdpPacket(
            srcIp = dnsIp,
            dstIp = clientIp,
            srcPort = 53,
            dstPort = 54321,
            payload = dnsResponsePayload
        )

        val ipHeader = com.example.netion.vpn.PacketUtils.parseIpHeader(udpPacket, udpPacket.size)
        assertNotNull(ipHeader)
        assertEquals(com.example.netion.vpn.PacketUtils.PROTO_UDP, ipHeader!!.protocol)

        val udpHeader = com.example.netion.vpn.PacketUtils.parseUdpHeader(udpPacket, ipHeader.ihl)
        assertNotNull(udpHeader)
        assertEquals(53, udpHeader!!.srcPort)
        assertEquals(54321, udpHeader.dstPort)

        val extracted = com.example.netion.vpn.PacketUtils.udpPayload(udpPacket, ipHeader)
        assertArrayEquals(dnsResponsePayload, extracted)
    }
}


