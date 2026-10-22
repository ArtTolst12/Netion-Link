package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.PacketUtils
import io.github.arttolst12.netion.vpn.TcpSession
import io.github.arttolst12.netion.vpn.TunVpnService
import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class Stage4TcpWindowTest {

    private fun createDummyTunWriter(packetsList: MutableList<ByteArray>): TunWriter {
        val dummyFd = java.io.FileOutputStream(java.io.File.createTempFile("tun_test", ".tmp").apply { deleteOnExit() })
        return TunWriter(dummyFd) { len ->
            // Track bytes written
        }
    }

    @Test
    fun testWindowCalculationNeverShrinksRightEdge() {
        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50001,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = null
        )

        // Initial established session at seq 1000
        session.markEstablishedForTesting(1000L)
        val initialTheirSeq = session.theirSeq
        assertEquals(1001L, initialTheirSeq)

        val initialRightEdge = session.rightEdge
        assertTrue("Right edge must be at least theirSeq + window", initialRightEdge >= initialTheirSeq)

        var prevRightEdge = initialRightEdge

        // Simulate advancing theirSeq with in-order packets
        val dummyTcp = PacketUtils.TcpHeader(
            srcPort = 50001,
            dstPort = 80,
            seqNum = 1001L,
            ackNum = session.nextSendSequence,
            dataOffset = 20,
            flags = PacketUtils.TCP_ACK,
            windowSize = 65535
        )

        // Send 10 chunks of 4 KB each (40 KB total < HIGH_WATERMARK = 48 KB)
        var currentSeq = 1001L
        val chunk = ByteArray(4096)
        for (i in 1..10) {
            val tcpHeader = dummyTcp.copy(seqNum = currentSeq)
            session.handleData(tcpHeader, chunk)
            currentSeq += chunk.size

            val currentRightEdge = session.rightEdge
            // Rule 1: Right edge must never move backward
            assertFalse(
                "Right edge must never move backward (RFC 793)",
                TcpSession.isSeqBefore(currentRightEdge, prevRightEdge)
            )
            prevRightEdge = currentRightEdge
        }

        // Now send chunks to exceed HIGH_WATERMARK (48 KB)
        for (i in 1..4) { // another 16 KB -> total 56 KB >= 48 KB
            val tcpHeader = dummyTcp.copy(seqNum = currentSeq)
            session.handleData(tcpHeader, chunk)
            currentSeq += chunk.size

            val currentRightEdge = session.rightEdge
            assertFalse(
                "Right edge must never shrink even when exceeding HIGH_WATERMARK",
                TcpSession.isSeqBefore(currentRightEdge, prevRightEdge)
            )
            prevRightEdge = currentRightEdge
        }

        session.close()
    }

    @Test
    fun testBufferCapExceededDropsDataAndRetainsOldAckSeq() {
        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50002,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = null
        )

        session.markEstablishedForTesting(2000L)
        val initialTheirSeq = session.theirSeq
        assertEquals(2001L, initialTheirSeq)

        val dummyTcp = PacketUtils.TcpHeader(
            srcPort = 50002,
            dstPort = 80,
            seqNum = 2001L,
            ackNum = session.nextSendSequence,
            dataOffset = 20,
            flags = PacketUtils.TCP_ACK,
            windowSize = 65535
        )

        // Feed data until rightEdge is reached (advertised window becomes 0)
        var seq = 2001L
        val chunkSize = 16384 // 16 KB
        while (session.theirSeq < session.rightEdge) {
            val remaining = (session.rightEdge - session.theirSeq).toInt().coerceAtMost(chunkSize)
            session.handleData(dummyTcp.copy(seqNum = seq), ByteArray(remaining))
            seq += remaining
        }

        val seqAtCap = session.theirSeq
        assertEquals("theirSeq must reach rightEdge", session.rightEdge, seqAtCap)

        // Attempt to send another chunk beyond the advertised window / buffer cap
        val extraChunk = ByteArray(1024)
        session.handleData(dummyTcp.copy(seqNum = seqAtCap), extraChunk)

        // Verify that theirSeq was NOT advanced (data was rejected/dropped without uncommitted ACK)
        assertEquals("theirSeq must NOT advance when window/buffer cap is exceeded", seqAtCap, session.theirSeq)

        session.close()
    }

    @Test
    fun testGlobalMemoryLimitStopsWindowAdvancement() {
        val globalTracker = AtomicLong(TcpSession.MAX_GLOBAL_UPLOAD_BYTES) // Global memory already exhausted

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50003,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = null,
            globalMemoryTracker = globalTracker
        )

        session.markEstablishedForTesting(3000L)
        val seqBefore = session.theirSeq

        val dummyTcp = PacketUtils.TcpHeader(
            srcPort = 50003,
            dstPort = 80,
            seqNum = seqBefore,
            ackNum = session.nextSendSequence,
            dataOffset = 20,
            flags = PacketUtils.TCP_ACK,
            windowSize = 65535
        )

        // Send data when global limit is exhausted
        session.handleData(dummyTcp, ByteArray(1024))

        // Must reject since global memory cap is full
        assertEquals("Session must not accept new data when global memory is exhausted", seqBefore, session.theirSeq)

        session.close()
    }

    @Test
    fun testHalfCloseDrainsQueueBeforeProxyShutdownOutput() {
        val serverSocket = ServerSocket(0)
        val serverPort = serverSocket.localPort

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50004,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = serverPort,
            isSocks5 = false,
            vpnService = null,
            tunWriter = null
        )

        try {
            session.handleSyn(4000L)
            serverSocket.soTimeout = 2000
            val acceptedSocket = serverSocket.accept()
            acceptedSocket.soTimeout = 2000

            // Drain CONNECT request headers from client
            val sin = acceptedSocket.getInputStream()
            val sout = acceptedSocket.getOutputStream()
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = sin.read()
                if (b == -1) break
                sb.append(b.toChar())
            }

            // Send 200 Connection Established
            sout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            sout.flush()

            Thread.sleep(150)
            assertEquals("Session must be ESTABLISHED", TcpSession.State.ESTABLISHED, session.state)

            // Queue data into session BEFORE sending FIN
            val dummyTcp = PacketUtils.TcpHeader(
                srcPort = 50004,
                dstPort = 80,
                seqNum = session.theirSeq,
                ackNum = session.nextSendSequence,
                dataOffset = 20,
                flags = PacketUtils.TCP_ACK,
                windowSize = 65535
            )

            val payload1 = "CHUNK_ONE_DATA_12345\n".toByteArray()
            val payload2 = "CHUNK_TWO_DATA_67890\n".toByteArray()

            session.handleData(dummyTcp.copy(seqNum = session.theirSeq), payload1)
            session.handleData(dummyTcp.copy(seqNum = session.theirSeq), payload2)

            // Client sends FIN (half-close)
            session.handleFin(dummyTcp.copy(seqNum = session.theirSeq, flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK))
            assertEquals("State must be HALF_CLOSED_LOCAL", TcpSession.State.HALF_CLOSED_LOCAL, session.state)
            assertFalse("Session must not be dead during half-close", session.isDead)

            // Server side must read the queued chunks in exact order
            val received = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = sin.read(buf)
                if (n == -1) break
                received.write(buf, 0, n)
                if (received.size() >= payload1.size + payload2.size) {
                    break
                }
            }

            val expectedTotal = payload1 + payload2
            assertArrayEquals("All queued payload data must be delivered to upstream proxy before shutdown", expectedTotal, received.toByteArray())

            // Server sends response back to client during half-close
            sout.write("SERVER_FINAL_RESPONSE\n".toByteArray())
            sout.flush()
            Thread.sleep(100)

            // Upstream closes its side
            acceptedSocket.close()
            Thread.sleep(150)

            assertFalse("Proxy EOF must retain unacknowledged DATA/FIN", session.isDead)
            session.handleAck(dummyTcp.copy(seqNum = session.theirSeq, ackNum = session.nextSendSequence))
            assertTrue("Both half-closes complete only after the client acknowledges DATA/FIN", session.isDead)
            assertEquals(TcpSession.State.CLOSED, session.state)

        } finally {
            session.close()
            serverSocket.close()
        }
    }

    @Test
    fun testRetransmissionAndOutOfOrderHandling() {
        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50005,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = null
        )

        session.markEstablishedForTesting(5000L)
        val initialTheirSeq = session.theirSeq
        assertEquals(5001L, initialTheirSeq)

        val dummyTcp = PacketUtils.TcpHeader(
            srcPort = 50005,
            dstPort = 80,
            seqNum = 5001L,
            ackNum = session.nextSendSequence,
            dataOffset = 20,
            flags = PacketUtils.TCP_ACK,
            windowSize = 65535
        )

        // 1. Send in-order packet (100 bytes)
        val payload1 = ByteArray(100) { it.toByte() }
        session.handleData(dummyTcp.copy(seqNum = 5001L), payload1)
        assertEquals(5101L, session.theirSeq)

        // 2. Retransmission of exact same packet (seqNum = 5001L < theirSeq 5101L)
        session.handleData(dummyTcp.copy(seqNum = 5001L), payload1)
        assertEquals("Duplicate retransmission must not advance theirSeq", 5101L, session.theirSeq)

        // 3. Out-of-order packet ahead of theirSeq (seqNum = 5500L > theirSeq 5101L)
        val outOfOrder = ByteArray(50)
        session.handleData(dummyTcp.copy(seqNum = 5500L), outOfOrder)
        assertEquals("Out-of-order packet must not advance theirSeq", 5101L, session.theirSeq)

        session.close()
    }

    @Test
    fun testFinWithPayloadProcessesDataBeforeFin() {
        val serverSocket = ServerSocket(0)
        val serverPort = serverSocket.localPort

        val outputStream = ByteArrayOutputStream()
        val tunWriter = TunWriter(outputStream)

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50006,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = serverPort,
            isSocks5 = false,
            vpnService = null,
            tunWriter = tunWriter
        )

        try {
            session.handleSyn(6000L)
            serverSocket.soTimeout = 2000
            val acceptedSocket = serverSocket.accept()
            acceptedSocket.soTimeout = 2000

            // Drain CONNECT request
            val sin = acceptedSocket.getInputStream()
            val sout = acceptedSocket.getOutputStream()
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                val b = sin.read()
                if (b == -1) break
                sb.append(b.toChar())
            }
            sout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            sout.flush()
            Thread.sleep(150)
            assertEquals(TcpSession.State.ESTABLISHED, session.state)

            val payload = "PAYLOAD_ATTACHED_TO_FIN_SEGMENT_12345\n".toByteArray()
            val finTcp = PacketUtils.TcpHeader(
                srcPort = 50006,
                dstPort = 80,
                seqNum = session.theirSeq,
                ackNum = session.nextSendSequence,
                dataOffset = 20,
                flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
                windowSize = 65535
            )

            val seqBefore = session.theirSeq
            // Send FIN WITH PAYLOAD
            session.handleFin(finTcp, payload)

            // Sequence must advance by payload.size + 1 (for FIN control bit)
            val expectedSeq = (seqBefore + payload.size + 1) and 0xFFFFFFFFL
            assertEquals("theirSeq must advance by payload size + 1 (FIN)", expectedSeq, session.theirSeq)
            assertEquals(TcpSession.State.HALF_CLOSED_LOCAL, session.state)

            // Verify server received payload before EOF
            val received = ByteArrayOutputStream()
            val buf = ByteArray(1024)
            while (true) {
                val n = sin.read(buf)
                if (n == -1) break
                received.write(buf, 0, n)
            }
            assertArrayEquals("Server must receive payload sent with FIN before EOF", payload, received.toByteArray())

        } finally {
            session.close()
            serverSocket.close()
        }
    }

    @Test
    fun testPartialSocketWriteAndOpWriteResume() {
        val serverChannel = ServerSocketChannel.open()
        try {
            serverChannel.setOption(java.net.StandardSocketOptions.SO_RCVBUF, 1024)
        } catch (_: Exception) {}
        serverChannel.bind(InetSocketAddress("127.0.0.1", 0))
        val port = (serverChannel.localAddress as InetSocketAddress).port

        val selector = Selector.open()

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50007,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = port,
            isSocks5 = false,
            vpnService = null,
            tunWriter = null,
            onEstablished = { sess, ch ->
                val k = ch.register(selector, SelectionKey.OP_READ, sess)
                sess.selectionKey = k
                sess.selector = selector
                sess.updateInterestOps()
                selector.wakeup()
            }
        )

        try {
            session.handleSyn(7000L)
            val accepted = serverChannel.accept()
            accepted.configureBlocking(true)

            // Respond to CONNECT
            val readBuf = ByteBuffer.allocate(1024)
            val sb = StringBuilder()
            while (!sb.endsWith("\r\n\r\n")) {
                readBuf.clear()
                val n = accepted.read(readBuf)
                if (n <= 0) break
                readBuf.flip()
                while (readBuf.hasRemaining()) sb.append(readBuf.get().toInt().toChar())
            }
            accepted.write(ByteBuffer.wrap("HTTP/1.1 200 OK\r\n\r\n".toByteArray()))

            Thread.sleep(150)
            assertEquals(TcpSession.State.ESTABLISHED, session.state)
            assertNotNull(session.selectionKey)

            // 1. Force saturation of socket send buffer by writing directly until write() returns 0
            val satChunk = ByteBuffer.allocate(16 * 1024)
            var satTotal = 0
            while (true) {
                satChunk.clear()
                val n = session.proxyChannel!!.write(satChunk)
                if (n <= 0) break
                satTotal += n
            }
            assertTrue("Socket send buffer must be saturated", satTotal > 0)

            // 2. Queue test payload via handleData
            val dummyTcp = PacketUtils.TcpHeader(
                srcPort = 50007, dstPort = 80, seqNum = session.theirSeq,
                ackNum = session.nextSendSequence, dataOffset = 20, flags = PacketUtils.TCP_ACK, windowSize = 65535
            )
            val testData = "OP_WRITE_RESUME_VERIFIED_PAYLOAD_12345\n".toByteArray()
            session.handleData(dummyTcp.copy(seqNum = session.theirSeq), testData)

            // 3. Attempt drain into saturated socket: write() returns 0 (zero / partial write)
            session.drainUploadQueue()

            // Confirm that unwritten bytes remain in uploadQueue because socket buffer is full
            assertEquals("UploadQueue must retain unwritten bytes when socket is saturated", testData.size, session.uploadQueueBytes)

            // When upload queue has pending bytes, OP_WRITE must be registered on key
            val key = session.selectionKey!!
            assertTrue(
                "When upload queue has pending bytes, OP_WRITE must be registered",
                (key.interestOps() and SelectionKey.OP_WRITE) != 0
            )

            // 4. Server drains the initial saturation bytes
            accepted.configureBlocking(false)
            val satDrainBuf = ByteBuffer.allocate(8192)
            var satRead = 0
            while (satRead < satTotal) {
                satDrainBuf.clear()
                val n = accepted.read(satDrainBuf)
                if (n > 0) satRead += n
            }

            // 5. Now service OP_WRITE via selector
            val received = ByteArrayOutputStream()
            val drainBuf = ByteBuffer.allocate(8192)

            var iterations = 0
            while (received.size() < testData.size && iterations++ < 100) {
                selector.select(50)
                val iter = selector.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val k = iter.next()
                    iter.remove()
                    if (k.isValid && k.isWritable) {
                        (k.attachment() as TcpSession).onProxyWritable()
                    }
                }
                drainBuf.clear()
                val n = accepted.read(drainBuf)
                if (n > 0) {
                    drainBuf.flip()
                    val b = ByteArray(n)
                    drainBuf.get(b)
                    received.write(b)
                }
            }

            assertEquals("All data must be drained through OP_WRITE resume", testData.size, received.size())
            assertArrayEquals("Received data must match sent data", testData, received.toByteArray())
            assertEquals("Upload queue must be empty after full drain", 0, session.uploadQueueBytes)
            assertEquals("OP_WRITE must be cleared once queue is empty", 0, key.interestOps() and SelectionKey.OP_WRITE)

        } finally {
            session.close()
            selector.close()
            serverChannel.close()
        }
    }

    @Test
    fun testBoundedHandshakePoolRejectsOverflowWithRst() {
        val blockingPool = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            ThreadPoolExecutor.AbortPolicy()
        )

        val latch = CountDownLatch(1)
        val startedLatch = CountDownLatch(1)

        // Fill the 1 worker thread with a blocking task
        blockingPool.execute {
            startedLatch.countDown()
            latch.await(5, TimeUnit.SECONDS)
        }
        startedLatch.await(1, TimeUnit.SECONDS)

        val rstPackets = CopyOnWriteArrayList<ByteArray>()
        val testWriter = object : OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) {
                rstPackets.add(b.copyOfRange(off, off + len))
            }
        }
        val writer = TunWriter(testWriter)

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 50008,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = writer,
            handshakeExecutor = blockingPool
        )

        // Attempt handleSyn - pool is saturated, task should be rejected
        session.handleSyn(8000L)

        // Session must be closed immediately
        assertTrue("Session must be dead when handshake pool rejects task", session.isDead)
        assertEquals(TcpSession.State.CLOSED, session.state)

        // Must have sent TCP RST packet
        assertTrue("Must have sent at least one TCP RST packet", rstPackets.isNotEmpty())
        val rstPkt = rstPackets.first()
        val ipH = PacketUtils.parseIpHeader(rstPkt, rstPkt.size)
        assertNotNull(ipH)
        val tcpH = PacketUtils.parseTcpHeader(rstPkt, ipH!!.ihl)
        assertNotNull(tcpH)
        assertTrue("Packet must have RST flag set", (tcpH!!.flags and PacketUtils.TCP_RST) != 0)

        latch.countDown()
        blockingPool.shutdownNow()
        session.close()
    }

    @Test
    fun testConcurrentCloseAndDrainPreservesMemoryCounterInvariant() {
        val globalTracker = AtomicLong(0L)

        for (iteration in 1..25) {
            val session = TcpSession(
                srcIp = byteArrayOf(10, 0, 0, 2),
                srcPort = 50000 + iteration,
                dstIp = byteArrayOf(8, 8, 8, 8),
                dstPort = 80,
                proxyIp = "127.0.0.1",
                proxyPort = 8080,
                vpnService = null,
                tunWriter = null,
                globalMemoryTracker = globalTracker
            )

            session.markEstablishedForTesting(9000L)

            // Feed 32 KB of data
            val dummyTcp = PacketUtils.TcpHeader(
                srcPort = 50000 + iteration, dstPort = 80, seqNum = session.theirSeq,
                ackNum = session.nextSendSequence, dataOffset = 20, flags = PacketUtils.TCP_ACK, windowSize = 65535
            )
            val chunk = ByteArray(8192)
            for (i in 1..4) {
                session.handleData(dummyTcp.copy(seqNum = session.theirSeq), chunk)
            }
            assertTrue("Global memory should be 32 KB", globalTracker.get() > 0L)

            val latch = CountDownLatch(2)

            val t1 = Thread {
                latch.countDown()
                latch.await()
                session.drainUploadQueue()
            }

            val t2 = Thread {
                latch.countDown()
                latch.await()
                session.close()
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join()

            assertTrue("Global counter must never be negative", globalTracker.get() >= 0L)
            assertEquals("Global counter must return to 0 after session close", 0L, globalTracker.get())
        }
    }

    @Test
    fun testSelectorLoopContinuesServicingOtherChannelsWhenOneKeyThrows() {
        val service = TunVpnService()
        TunVpnService.isRunning = true
        service.tunGeneration.set(1L)

        val selector = Selector.open()

        val serverChannel = ServerSocketChannel.open().bind(null)
        val port = (serverChannel.localAddress as InetSocketAddress).port

        val selectorThread = Thread({
            service.runSelectorLoop(selector, 1L)
        }, "test-real-selector-thread").apply {
            isDaemon = true
            start()
        }

        try {
            // Channel 1: Faulty channel that throws when read
            val client1 = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            val accepted1 = serverChannel.accept()
            client1.configureBlocking(false)

            val faultySession = TcpSession(
                srcIp = byteArrayOf(10, 0, 0, 2), srcPort = 60001,
                dstIp = byteArrayOf(8, 8, 8, 8), dstPort = 80,
                proxyIp = "127.0.0.1", proxyPort = port,
                vpnService = null, tunWriter = null
            )
            val key1 = client1.register(selector, SelectionKey.OP_READ, faultySession)
            faultySession.selectionKey = key1

            // Channel 2: Healthy channel
            val client2 = SocketChannel.open(InetSocketAddress("127.0.0.1", port))
            val accepted2 = serverChannel.accept()
            client2.configureBlocking(false)

            val receivedBytes = ByteArrayOutputStream()
            val tunWriter2 = TunWriter(object : OutputStream() {
                override fun write(b: Int) {}
                override fun write(b: ByteArray, off: Int, len: Int) {
                    val ip = PacketUtils.parseIpHeader(b.copyOfRange(off, off + len), len) ?: return
                    val tcp = PacketUtils.parseTcpHeader(b.copyOfRange(off, off + len), ip.ihl) ?: return
                    val payload = PacketUtils.tcpPayload(b.copyOfRange(off, off + len), ip, tcp)
                    if (payload.isNotEmpty()) {
                        receivedBytes.write(payload)
                    }
                }
            })

            val healthySession = TcpSession(
                srcIp = byteArrayOf(10, 0, 0, 2), srcPort = 60002,
                dstIp = byteArrayOf(8, 8, 8, 8), dstPort = 80,
                proxyIp = "127.0.0.1", proxyPort = port,
                vpnService = null, tunWriter = tunWriter2
            ).also { it.markEstablishedForTesting(1000L) }

            val field = TcpSession::class.java.getDeclaredField("proxyChannel")
            field.isAccessible = true
            field.set(healthySession, client2)

            val key2 = client2.register(selector, SelectionKey.OP_READ, healthySession)
            healthySession.selectionKey = key2
            selector.wakeup()

            // 1. Invalidate faulty channel / session to trigger error handling in loop
            client1.close()
            faultySession.close()
            accepted1.write(ByteBuffer.wrap("FAULT_TRIGGER".toByteArray()))
            Thread.sleep(100)

            // Selector thread must still be ALIVE (per-key exception caught, loop not terminated)
            assertTrue("Selector thread must NOT die when one key fails", selectorThread.isAlive)

            // 2. Write data to healthy channel 2
            val testMsg = "HEALTHY_SESSION_PAYLOAD_OK\n".toByteArray()
            accepted2.write(ByteBuffer.wrap(testMsg))

            var waitMs = 0
            while (receivedBytes.size() < testMsg.size && waitMs < 2000) {
                Thread.sleep(50)
                waitMs += 50
            }

            assertArrayEquals("Healthy session must receive full data despite error on another key", testMsg, receivedBytes.toByteArray())
            assertTrue("Selector thread must still be alive", selectorThread.isAlive)

        } finally {
            TunVpnService.isRunning = false
            selectorThread.interrupt()
            selector.close()
            serverChannel.close()
        }
    }

    @Test
    fun testBuiltPacketSizeNeverExceedsConfiguredMtu() {
        val configuredMtu = 1400

        // 1. Data packet with maximum safe MSS payload (1360 bytes)
        val maxPayload = ByteArray(TcpSession.MSS) { (it % 127).toByte() }
        val dataPkt = PacketUtils.buildTcpPacket(
            srcIp = byteArrayOf(8, 8, 8, 8),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 50001,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_ACK or PacketUtils.TCP_PSH,
            payload = maxPayload,
            windowSize = 65535,
            includeMss = false
        )
        assertTrue("Data packet size (${dataPkt.size}) must never exceed MTU ($configuredMtu)", dataPkt.size <= configuredMtu)
        assertEquals("Max MSS packet must be exactly 1400 bytes (20 IP + 20 TCP + 1360 payload)", configuredMtu, dataPkt.size)

        // 2. SYN-ACK packet with MSS option
        val synAckPkt = PacketUtils.buildTcpPacket(
            srcIp = byteArrayOf(8, 8, 8, 8),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 50001,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
            payload = ByteArray(0),
            windowSize = 65535,
            includeMss = true
        )
        assertTrue("SYN-ACK packet with MSS option (${synAckPkt.size}) must never exceed MTU ($configuredMtu)", synAckPkt.size <= configuredMtu)

        // 3. FIN packet
        val finPkt = PacketUtils.buildTcpPacket(
            srcIp = byteArrayOf(8, 8, 8, 8),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 50001,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
            payload = ByteArray(0),
            windowSize = 65535
        )
        assertTrue("FIN packet size (${finPkt.size}) must never exceed MTU ($configuredMtu)", finPkt.size <= configuredMtu)

        // 4. RST packet
        val rstPkt = PacketUtils.buildTcpPacket(
            srcIp = byteArrayOf(8, 8, 8, 8),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 80,
            dstPort = 50001,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
            payload = ByteArray(0),
            windowSize = 0
        )
        assertTrue("RST packet size (${rstPkt.size}) must never exceed MTU ($configuredMtu)", rstPkt.size <= configuredMtu)
    }

    @Test
    fun testWireWindowRightEdgeNeverShrinksUnderGlobalMemoryFluctuations() {
        val globalTracker = AtomicLong(0L)

        val session = TcpSession(
            srcIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 55555,
            dstIp = byteArrayOf(8, 8, 8, 8),
            dstPort = 80,
            proxyIp = "127.0.0.1",
            proxyPort = 8080,
            vpnService = null,
            tunWriter = null,
            globalMemoryTracker = globalTracker
        )

        session.markEstablishedForTesting(10000L)
        val initialTheirSeq = session.theirSeq

        val initialWindow = session.computeAdvertisedWindowLocked()
        var prevWireRightEdge = (initialTheirSeq + initialWindow) and 0xFFFFFFFFL

        val dummyTcp = PacketUtils.TcpHeader(
            srcPort = 55555,
            dstPort = 80,
            seqNum = initialTheirSeq,
            ackNum = session.nextSendSequence,
            dataOffset = 20,
            flags = PacketUtils.TCP_ACK,
            windowSize = 65535
        )

        var curSeq = initialTheirSeq
        val chunk = ByteArray(4096)

        // Feed chunks while simulating sudden global memory spikes and drops from other sessions
        for (i in 1..20) {
            // Fluctuate global memory tracker
            if (i % 2 == 0) {
                globalTracker.set(TcpSession.MAX_GLOBAL_UPLOAD_BYTES - 1024L) // almost full
            } else {
                globalTracker.set(1024L) // mostly free
            }

            session.handleData(dummyTcp.copy(seqNum = curSeq), chunk)
            curSeq = session.theirSeq

            val advertisedWindow = session.computeAdvertisedWindowLocked()
            val currentWireRightEdge = (session.theirSeq + advertisedWindow) and 0xFFFFFFFFL

            // RFC 793 Rule: The wire right edge (RCV.NXT + RCV.WND) must NEVER move backward
            assertTrue(
                "Wire right edge must be monotonically non-decreasing (prev=$prevWireRightEdge, cur=$currentWireRightEdge, theirSeq=${session.theirSeq}, win=$advertisedWindow)",
                TcpSession.isSeqBeforeOrEqual(prevWireRightEdge, currentWireRightEdge)
            )
            prevWireRightEdge = currentWireRightEdge
        }

        session.close()
    }

    @Test
    fun testMultiSessionWindowAllocationNeverOvercommitsGlobalBudget() {
        val tracker = AtomicLong(0L)
        val budget = 8192L

        // Four sessions contend for a deliberately small, actually reachable budget.
        val sessions = (1..4).map { i ->
            TcpSession(
                srcIp = byteArrayOf(10, 0, 0, 2),
                srcPort = 45000 + i,
                dstIp = byteArrayOf(8, 8, 8, 8),
                dstPort = 80,
                proxyIp = "127.0.0.1",
                proxyPort = 8080,
                vpnService = null,
                tunWriter = null,
                globalMemoryTracker = tracker,
                uploadBudgetBytes = budget
            ).also { it.markEstablishedForTesting(10000L + i * 1000) }
        }

        try {
            // Attempt to pump data into each session
            val chunk = ByteArray(4096)
            for (round in 1..16) {
                for (s in sessions) {
                    val dummyTcp = PacketUtils.TcpHeader(
                        srcPort = 45001, dstPort = 80, seqNum = s.theirSeq,
                        ackNum = s.nextSendSequence, dataOffset = 20, flags = PacketUtils.TCP_ACK, windowSize = 65535
                    )
                    s.handleData(dummyTcp, chunk)
                }
            }

            assertEquals("The shared budget must actually be exhausted", budget, tracker.get())
            assertEquals("Only two 4096-byte reservations fit", 2, sessions.count { it.uploadQueueBytes == 4096 })

            // When global memory is exhausted, rightEdge cannot be extended further
            for (s in sessions) {
                val edgeBefore = s.rightEdge
                s.computeAdvertisedWindowLocked()
                assertEquals("rightEdge must not expand when available global space is exhausted", edgeBefore, s.rightEdge)
            }

        } finally {
            sessions.forEach { it.close() }
            assertEquals("After closing all sessions, global memory must return to 0", 0L, tracker.get())
        }
    }

    @Test
    fun testFatalSelectorErrorShutsDownVpnServiceGenerationCleanly() {
        val vpn = TunVpnService()
        TunVpnService.isRunning = true
        val curGen = vpn.tunGeneration.get()

        // 1. Invoking onFatalSelectorError for the active generation must stop VPN and increment generation
        vpn.onFatalSelectorError(curGen, java.io.IOException("Fatal epoll/selector crash"))

        assertFalse("VPN isRunning must become false after fatal selector error", TunVpnService.isRunning)
        assertTrue("tunGeneration must be incremented to invalidate dead generation", vpn.tunGeneration.get() > curGen)

        // 2. An error reported for a stale/older generation must NOT affect active state
        val newGen = vpn.tunGeneration.get()
        TunVpnService.isRunning = true
        vpn.onFatalSelectorError(curGen, java.io.IOException("Stale error from old thread"))

        assertTrue("Stale selector error must not shut down newer active generation", TunVpnService.isRunning)
        assertEquals("tunGeneration must remain unchanged when stale error arrives", newGen, vpn.tunGeneration.get())

        TunVpnService.isRunning = false
    }
}

