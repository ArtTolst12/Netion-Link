package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

class TcpAckValidationTest {
    @Test
    fun futureAckWithDataDoesNotAcceptOrReservePayload() = checkFutureAck(fin = false, payload = "not accepted".toByteArray())

    @Test
    fun futureAckWithFinDoesNotCloseUpload() = checkFutureAck(fin = true, payload = byteArrayOf())

    @Test
    fun futureAckWithDataAndFinDoesNotAdvanceReceiveSequence() = checkFutureAck(fin = true, payload = "not accepted".toByteArray())

    @Test
    fun futureAckIsRejectedAcrossSequenceWrap() = checkFutureAck(fin = false, payload = byteArrayOf(1), ourInitialSeq = 0xFFFFFFFFL)

    private fun checkFutureAck(fin: Boolean, payload: ByteArray, ourInitialSeq: Long = 5000L) {
        val memory = AtomicLong()
        val sink = ByteArrayOutputStream()
        val writer = TunWriter(sink)
        val session = session(memory, writer)
        try {
            session.markEstablishedForTesting(1000L, ourInitialSeq)
            val receiveSequence = session.theirSeq
            val sendSequence = session.nextSendSequence
            val flags = PacketUtils.TCP_ACK or if (fin) PacketUtils.TCP_FIN else PacketUtils.TCP_PSH
            val packet = header(receiveSequence, (sendSequence + 1) and 0xFFFFFFFFL, flags)
            if (fin) session.handleFin(packet, payload) else session.handleData(packet, payload)
            assertEquals("future ACK must not advance RCV.NXT", receiveSequence, session.theirSeq)
            assertEquals(0, session.uploadQueueBytes)
            assertEquals(0L, memory.get())
            assertEquals(TcpSession.State.ESTABLISHED, session.state)
            assertFalse(session.isDead)
            val response = sink.toByteArray()
            val ip = PacketUtils.parseIpHeader(response, response.size)!!
            val tcp = PacketUtils.parseTcpHeader(response, ip.ihl, ip.totalLength)!!
            assertEquals(PacketUtils.TCP_ACK, tcp.flags)
            assertEquals(receiveSequence, tcp.ackNum)
        } finally {
            session.close()
            writer.close()
        }
    }

    @Test
    fun dataWithoutAckFlagIsDropped() = checkMissingAck(fin = false)

    @Test
    fun finWithoutAckFlagIsDropped() = checkMissingAck(fin = true)

    private fun checkMissingAck(fin: Boolean) {
        val memory = AtomicLong()
        val session = session(memory)
        try {
            session.markEstablishedForTesting(1000L, 5000L)
            val packet = header(session.theirSeq, session.nextSendSequence,
                if (fin) PacketUtils.TCP_FIN else PacketUtils.TCP_PSH)
            if (fin) session.handleFin(packet, byteArrayOf(1)) else session.handleData(packet, byteArrayOf(1))
            assertEquals(1001L, session.theirSeq)
            assertEquals(TcpSession.State.ESTABLISHED, session.state)
            assertEquals(0, session.uploadQueueBytes)
            assertEquals(0L, memory.get())
        } finally {
            session.close()
        }
    }

    @Test
    fun staleAckDoesNotDiscardOtherwiseValidPayload() = checkAcceptedAck(stale = true)

    @Test
    fun currentAckStillAcceptsPayload() = checkAcceptedAck(stale = false)

    private fun checkAcceptedAck(stale: Boolean) {
        val memory = AtomicLong()
        val session = session(memory)
        try {
            session.markEstablishedForTesting(1000L, 5000L)
            val ack = if (stale) session.acknowledgedSequence - 1 else session.acknowledgedSequence
            session.handleData(header(session.theirSeq, ack, PacketUtils.TCP_ACK or PacketUtils.TCP_PSH), byteArrayOf(1, 2, 3))
            assertEquals(1004L, session.theirSeq)
            assertEquals(3, session.uploadQueueBytes)
            assertEquals(3L, memory.get())
        } finally {
            session.close()
        }
        assertEquals(0L, memory.get())
    }

    private fun session(memory: AtomicLong, writer: TunWriter? = null) = TcpSession(
        byteArrayOf(10, 0, 0, 2), 50000, byteArrayOf(127, 0, 0, 1), 80, "127.0.0.1", 8080,
        globalMemoryTracker = memory, tunWriter = writer
    )

    private fun header(seq: Long, ack: Long, flags: Int) =
        PacketUtils.TcpHeader(50000, 80, seq, ack, 20, flags, 65535)
}
