package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.PacketUtils
import io.github.arttolst12.netion.vpn.TcpSession
import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class TcpReliabilityTest {
    private val sourceIp = byteArrayOf(10, 0, 0, 2)
    private val destinationIp = byteArrayOf(8, 8, 8, 8)

    private class Link : AutoCloseable {
        private val listener = ServerSocketChannel.open().apply { bind(InetSocketAddress("127.0.0.1", 0)) }
        val local = SocketChannel.open(listener.localAddress as InetSocketAddress)
        val remote = listener.accept()
        init { local.configureBlocking(false); listener.close() }
        fun awaitReadable() {
            java.nio.channels.Selector.open().use { selector ->
                local.register(selector, java.nio.channels.SelectionKey.OP_READ)
                assertTrue("Loopback response must become readable", selector.select(3000) > 0)
            }
        }
        override fun close() { local.close(); remote.close() }
    }

    private fun attach(session: TcpSession, channel: SocketChannel) {
        TcpSession::class.java.getDeclaredField("proxyChannel").apply { isAccessible = true }.set(session, channel)
    }

    private fun packetSink(packets: MutableList<ByteArray>) = TunWriter(object : OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) { packets.add(b.copyOfRange(off, off + len)) }
    })

    private fun header(session: TcpSession, ack: Long, window: Int = 65535, flags: Int = PacketUtils.TCP_ACK) =
        PacketUtils.TcpHeader(50000, 80, session.theirSeq, ack, 20, flags, window)

    @Test
    fun zeroWindowStopsReadsAndAckReleasesOnlyConfirmedBytes() {
        val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
        val download = AtomicLong()
        Link().use { link ->
            val session = TcpSession(sourceIp, 50000, destinationIp, 80, "127.0.0.1", 8080,
                tunWriter = packetSink(packets), globalDownloadMemoryTracker = download)
            try {
                session.markEstablishedForTesting(1000L, 5000L)
                attach(session, link.local)
                session.handleAck(header(session, 5001L, 0))
                link.remote.write(ByteBuffer.wrap("hello".toByteArray()))
                link.awaitReadable()
                session.onProxyReadable()
                assertEquals(0L, download.get())
                assertTrue(packets.isEmpty())

                session.handleAck(header(session, 5001L, 3))
                session.onProxyReadable()
                assertEquals(3L, download.get())
                assertEquals(5004L, session.nextSendSequence)
                assertEquals("hel", String(PacketUtils.tcpPayload(packets.single(),
                    PacketUtils.parseIpHeader(packets.single(), packets.single().size)!!,
                    PacketUtils.parseTcpHeader(packets.single(), 20)!!)))

                session.handleAck(header(session, 5002L, 0))
                assertEquals(2L, download.get())
                session.handleAck(header(session, 5002L, 0))
                assertEquals("Duplicate ACK cannot release bytes twice", 2L, download.get())
                session.handleAck(header(session, 9000L))
                assertEquals("ACK beyond SND.NXT is invalid", 2L, download.get())
                session.handleAck(header(session, 5004L, 100))
                session.onProxyReadable()
                assertEquals(2L, download.get())
                assertEquals(5006L, session.nextSendSequence)
                session.handleAck(header(session, 5006L))
                assertEquals(0L, download.get())
            } finally { session.close() }
            assertEquals(0L, download.get())
        }
    }

    @Test
    fun lostDataAndFinAreRetriedAtSameSequenceUntilAcknowledged() {
        val clock = AtomicLong()
        val packets = Collections.synchronizedList(mutableListOf<ByteArray>())
        Link().use { link ->
            val session = TcpSession(sourceIp, 50000, destinationIp, 80, "127.0.0.1", 8080,
                tunWriter = packetSink(packets), nanoTime = clock::get)
            try {
                session.markEstablishedForTesting(1000L, 0xFFFFFFFDL)
                attach(session, link.local)
                link.remote.write(ByteBuffer.wrap(byteArrayOf(10, 20, 30, 40)))
                link.awaitReadable()
                session.onProxyReadable()
                assertEquals(2L, session.nextSendSequence)
                clock.set(500_000_000L)
                session.onTimer()
                assertArrayEquals("Retransmission keeps sequence and payload", packets[0], packets[1])
                assertEquals(2L, session.nextSendSequence)
                session.handleAck(header(session, 2L))
                link.remote.shutdownOutput()
                link.awaitReadable()
                session.onProxyReadable()
                val firstFin = packets.last()
                assertTrue(PacketUtils.parseTcpHeader(firstFin, 20)!!.flags and PacketUtils.TCP_FIN != 0)
                clock.set(1_000_000_000L)
                session.onTimer()
                assertArrayEquals(firstFin, packets.last())
                assertFalse("Remote EOF must preserve the client upload direction", session.isDead)
                session.handleFin(header(session, 3L, flags = PacketUtils.TCP_ACK or PacketUtils.TCP_FIN))
                session.drainUploadQueue()
                assertTrue(session.isDead)
            } finally { session.close() }
        }
    }

    @Test
    fun remoteEofDoesNotDiscardUploadAndFinWithDuplicatePayloadIsAcceptedOnce() {
        val budget = AtomicLong()
        Link().use { link ->
            val session = TcpSession(sourceIp, 50000, destinationIp, 80, "127.0.0.1", 8080,
                globalMemoryTracker = budget, onEstablished = { _, _ -> })
            try {
                session.markEstablishedForTesting(1000L, 5000L)
                attach(session, link.local)
                val payload = "must arrive before half close".toByteArray()
                session.handleData(header(session, 5001L), payload)
                assertEquals(payload.size.toLong(), budget.get())
                link.remote.shutdownOutput()
                link.awaitReadable()
                session.onProxyReadable()
                assertFalse(session.isDead)
                val start = 1001L
                session.handleFin(header(session, session.nextSendSequence, flags = PacketUtils.TCP_ACK or PacketUtils.TCP_FIN)
                    .copy(seqNum = start), payload)
                session.drainUploadQueue()
                val received = ByteArray(payload.size)
                val buffer = ByteBuffer.wrap(received)
                while (buffer.hasRemaining()) assertTrue(link.remote.read(buffer) > 0)
                assertArrayEquals(payload, received)
                assertEquals(0L, budget.get())
                assertTrue(session.isDead)
            } finally { session.close() }
        }
    }

    @Test
    fun drainOwnerCoversWriteAndCommitWhileCloseReleasesReservationOnlyOnce() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val budget = AtomicLong()
        val output = ByteArrayOutputStream()
        val session = TcpSession(sourceIp, 50000, destinationIp, 80, "127.0.0.1", 8080,
            globalMemoryTracker = budget, onEstablished = { _, _ -> })
        session.markEstablishedForTesting()
        val blockingOutput = object : OutputStream() {
            override fun write(b: Int) { output.write(b) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                entered.countDown()
                check(proceed.await(3, TimeUnit.SECONDS))
                output.write(b, off, len)
            }
        }
        TcpSession::class.java.getDeclaredField("proxyOut").apply { isAccessible = true }.set(session, blockingOutput)
        val payload = "exactly once".toByteArray()
        session.handleData(header(session, session.nextSendSequence), payload)
        val worker = Thread { session.drainUploadQueue() }
        worker.start()
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            session.drainUploadQueue() // Must return without entering the same blocking write.
            session.close()
            assertEquals(0L, budget.get())
        } finally {
            proceed.countDown()
            worker.join(3000)
            session.close()
        }
        assertFalse(worker.isAlive)
        assertArrayEquals(payload, output.toByteArray())
        assertEquals(0L, budget.get())
    }

    @Test
    fun concurrentSessionsActuallyReachAndRespectGlobalBudget() {
        val tracker = AtomicLong()
        val sessions = (1..16).map {
            TcpSession(sourceIp, 50000 + it, destinationIp, 80, "127.0.0.1", 8080,
                globalMemoryTracker = tracker, uploadBudgetBytes = 8192L).apply { markEstablishedForTesting() }
        }
        val ready = CountDownLatch(sessions.size)
        val go = CountDownLatch(1)
        val done = CountDownLatch(sessions.size)
        try {
            sessions.forEach { session ->
                Thread {
                    ready.countDown()
                    try {
                        check(go.await(3, TimeUnit.SECONDS))
                        session.handleData(header(session, session.nextSendSequence), ByteArray(4096))
                    } finally { done.countDown() }
                }.start()
            }
            assertTrue(ready.await(3, TimeUnit.SECONDS))
            go.countDown()
            assertTrue(done.await(3, TimeUnit.SECONDS))
            assertEquals("Exactly two reservations fit the real configured budget", 8192L, tracker.get())
            assertEquals(2, sessions.count { it.uploadQueueBytes == 4096 })
        } finally { go.countDown(); sessions.forEach { it.close() } }
        assertEquals(0L, tracker.get())
    }
}
