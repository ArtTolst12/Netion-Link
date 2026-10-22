package io.github.arttolst12.netion.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class PacketUtilsHardeningTest {

    @Test
    fun malformedIpv4TableIsRejectedWithoutThrowing() {
        val cases = listOf(
            ByteArray(0) to 0,
            ByteArray(19) to 19,
            ipv4Packet(ihl = 4, totalLength = 20) to 20,
            ipv4Packet(ihl = 6, totalLength = 20) to 20,
            ipv4Packet(ihl = 5, totalLength = 19) to 20,
            ipv4Packet(ihl = 5, totalLength = 21) to 20,
            ipv4Packet(totalLength = 20, flagsAndOffset = 0x2000) to 20,
            ipv4Packet(totalLength = 20, flagsAndOffset = 1) to 20,
        )

        cases.forEach { (packet, length) ->
            assertNull(PacketUtils.parseIpHeader(packet, length))
        }
        assertNull(PacketUtils.parseIpHeader(ByteArray(20), -1))
        assertNull(PacketUtils.parseIpHeader(ByteArray(20), 21))
    }

    @Test
    fun transportParsersUseIpv4LengthNotBufferCapacity() {
        val tcpPacket = PacketUtils.buildTcpPacket(
            srcIp = byteArrayOf(10, 0, 0, 1),
            dstIp = byteArrayOf(10, 0, 0, 2),
            srcPort = 1,
            dstPort = 2,
            seqNum = 0,
            ackNum = 0,
            flags = PacketUtils.TCP_ACK,
        )
        val reusableBuffer = ByteArray(128)
        System.arraycopy(tcpPacket, 0, reusableBuffer, 0, tcpPacket.size)
        reusableBuffer[32] = 0xF0.toByte() // TCP data offset of 60, beyond total length 40.

        assertNull(PacketUtils.parseTcpHeader(reusableBuffer, 20, tcpPacket.size))
        assertNull(PacketUtils.parseTcpHeader(reusableBuffer, 20))

        val udpPacket = ipv4Packet(totalLength = 32, protocol = PacketUtils.PROTO_UDP)
        udpPacket[20] = 0
        udpPacket[21] = 1
        udpPacket[22] = 0
        udpPacket[23] = 2
        write16(udpPacket, 24, 7) // UDP length cannot be shorter than its header.
        assertNull(PacketUtils.parseUdpHeader(udpPacket, 20, udpPacket.size))

        write16(udpPacket, 24, 13) // IP payload is only 12 bytes.
        assertNull(PacketUtils.parseUdpHeader(udpPacket, 20, udpPacket.size))
    }

    @Test
    fun udpPayloadHonorsDeclaredUdpLength() {
        val packet = ipv4Packet(totalLength = 40, protocol = PacketUtils.PROTO_UDP)
        write16(packet, 20, 1000)
        write16(packet, 22, 53)
        write16(packet, 24, 10)
        packet[28] = 10
        packet[29] = 11
        packet[30] = 12 // Trailing IPv4 payload bytes must not leak into UDP payload.
        packet[31] = 13

        val ipHeader = PacketUtils.parseIpHeader(packet, packet.size)
        assertNotNull(ipHeader)
        val udpHeader = PacketUtils.parseUdpHeader(packet, ipHeader!!.ihl, packet.size)
        assertNotNull(udpHeader)
        assertArrayEquals(byteArrayOf(10, 11), PacketUtils.udpPayload(packet, ipHeader))
    }

    @Test
    fun malformedInputFuzzNeverEscapesAsAnException() {
        val random = Random(0x4E4554494F4EL)
        repeat(2_000) {
            val data = ByteArray(random.nextInt(96))
            random.nextBytes(data)
            val packetLength = random.nextInt(data.size + 5) - 2

            val ipHeader = PacketUtils.parseIpHeader(data, packetLength)
            val headerLength = random.nextInt(120) - 20
            assertNullOrNoException(data, headerLength, packetLength)
            if (ipHeader != null) {
                PacketUtils.tcpPayload(data, ipHeader, PacketUtils.TcpHeader(0, 0, 0, 0, 20, 0, 0))
                PacketUtils.udpPayload(data, ipHeader)
            }
        }
    }

    @Test
    fun buildersValidateFieldsAndEncodeZeroUdpChecksumAsFFFF() {
        assertFails { PacketUtils.buildUdpPacket(ByteArray(3), ByteArray(4), 1, 2, ByteArray(0)) }
        assertFails { PacketUtils.buildUdpPacket(ByteArray(4), ByteArray(4), -1, 2, ByteArray(0)) }
        assertFails {
            PacketUtils.buildTcpPacket(
                ByteArray(4), ByteArray(4), 1, 2, 0, 0, 0,
                payload = ByteArray(65_496),
            )
        }
        assertFails { PacketUtils.ipToBytes("127.0.0") }
        assertFails { PacketUtils.ipToBytes("127.0.0.256") }
        assertFails { PacketUtils.ipToBytes("127.0.0.a") }

        // Folded UDP pseudo-header + header sum is 0xFFFF, so the calculated
        // checksum is zero and RFC 768 requires 0xFFFF on the wire.
        val packet = PacketUtils.buildUdpPacket(
            srcIp = ByteArray(4),
            dstIp = ByteArray(4),
            srcPort = 0xFFDE,
            dstPort = 0,
            payload = ByteArray(0),
        )
        assertEquals(0xFFFF, read16(packet, 26))
    }

    private fun assertNullOrNoException(data: ByteArray, headerLength: Int, packetLength: Int) {
        PacketUtils.parseTcpHeader(data, headerLength, packetLength)
        PacketUtils.parseUdpHeader(data, headerLength, packetLength)
    }

    private fun ipv4Packet(
        ihl: Int = 5,
        totalLength: Int = 20,
        protocol: Int = PacketUtils.PROTO_TCP,
        flagsAndOffset: Int = 0x4000,
    ): ByteArray {
        val size = maxOf(20, totalLength)
        return ByteArray(size).also { packet ->
            packet[0] = ((4 shl 4) or ihl).toByte()
            write16(packet, 2, totalLength)
            write16(packet, 6, flagsAndOffset)
            packet[9] = protocol.toByte()
        }
    }

    private fun read16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun write16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 8).toByte()
        data[offset + 1] = value.toByte()
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
