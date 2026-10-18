package io.github.arttolst12.netion

import io.github.arttolst12.netion.vpn.PacketUtils
import org.junit.Assert.*
import org.junit.Test

class PacketUtilsTest {

    @Test
    fun testIpAddressConversion() {
        val ipStr = "192.168.43.1"
        val ipBytes = PacketUtils.ipToBytes(ipStr)
        assertEquals(4, ipBytes.size)
        assertEquals(192.toByte(), ipBytes[0])
        assertEquals(168.toByte(), ipBytes[1])
        assertEquals(43.toByte(), ipBytes[2])
        assertEquals(1.toByte(), ipBytes[3])

        val convertedBack = PacketUtils.ipToString(ipBytes)
        assertEquals(ipStr, convertedBack)
    }

    @Test
    fun testBuildAndParseTcpPacket() {
        val srcIp = PacketUtils.ipToBytes("10.0.0.2")
        val dstIp = PacketUtils.ipToBytes("93.184.216.34")
        val payload = "Hello, Netion!".toByteArray(Charsets.UTF_8)

        val packet = PacketUtils.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 12345,
            dstPort = 80,
            seqNum = 1000L,
            ackNum = 2000L,
            flags = PacketUtils.TCP_ACK or PacketUtils.TCP_PSH,
            payload = payload,
            windowSize = 65535,
            includeMss = false
        )

        // Parse IP Header
        val ipHeader = PacketUtils.parseIpHeader(packet, packet.size)
        assertNotNull("IP Header should parse successfully", ipHeader)
        assertEquals(5 * 4, ipHeader!!.ihl)
        assertEquals(packet.size, ipHeader.totalLength)
        assertEquals(PacketUtils.PROTO_TCP, ipHeader.protocol)
        assertArrayEquals(srcIp, ipHeader.srcIp)
        assertArrayEquals(dstIp, ipHeader.dstIp)

        // Verify IP checksum: checksum over entire header with checksum field should yield 0
        assertEquals(0, PacketUtils.checksum(packet, 0, ipHeader.ihl))

        // Parse TCP Header
        val tcpHeader = PacketUtils.parseTcpHeader(packet, ipHeader.ihl)
        assertNotNull("TCP Header should parse successfully", tcpHeader)
        assertEquals(12345, tcpHeader!!.srcPort)
        assertEquals(80, tcpHeader.dstPort)
        assertEquals(1000L, tcpHeader.seqNum)
        assertEquals(2000L, tcpHeader.ackNum)
        assertEquals(PacketUtils.TCP_ACK or PacketUtils.TCP_PSH, tcpHeader.flags)
        assertEquals(65535, tcpHeader.windowSize)

        // Extract and verify payload
        val extractedPayload = PacketUtils.tcpPayload(packet, ipHeader, tcpHeader)
        assertArrayEquals(payload, extractedPayload)
    }

    @Test
    fun testBuildAndParseTcpPacketWithMss() {
        val srcIp = PacketUtils.ipToBytes("10.0.0.2")
        val dstIp = PacketUtils.ipToBytes("1.1.1.1")

        val packet = PacketUtils.buildTcpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 54321,
            dstPort = 443,
            seqNum = 500L,
            ackNum = 0L,
            flags = PacketUtils.TCP_SYN,
            includeMss = true
        )

        val ipHeader = PacketUtils.parseIpHeader(packet, packet.size)
        assertNotNull(ipHeader)
        val tcpHeader = PacketUtils.parseTcpHeader(packet, ipHeader!!.ihl)
        assertNotNull(tcpHeader)
        assertEquals(PacketUtils.TCP_HEADER_SIZE_MSS, tcpHeader!!.dataOffset)
        assertEquals(PacketUtils.TCP_SYN, tcpHeader.flags)
    }

    @Test
    fun testBuildAndParseUdpPacket() {
        val srcIp = PacketUtils.ipToBytes("10.0.0.2")
        val dstIp = PacketUtils.ipToBytes("8.8.8.8")
        val dnsPayload = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0x00, 0x01)

        val packet = PacketUtils.buildUdpPacket(
            srcIp = srcIp,
            dstIp = dstIp,
            srcPort = 40000,
            dstPort = 53,
            payload = dnsPayload
        )

        val ipHeader = PacketUtils.parseIpHeader(packet, packet.size)
        assertNotNull("IP Header should parse", ipHeader)
        assertEquals(PacketUtils.PROTO_UDP, ipHeader!!.protocol)

        val udpHeader = PacketUtils.parseUdpHeader(packet, ipHeader.ihl)
        assertNotNull("UDP Header should parse", udpHeader)
        assertEquals(40000, udpHeader!!.srcPort)
        assertEquals(53, udpHeader.dstPort)
        assertEquals(PacketUtils.UDP_HEADER_SIZE + dnsPayload.size, udpHeader.length)

        val extracted = PacketUtils.udpPayload(packet, ipHeader)
        assertArrayEquals(dnsPayload, extracted)
    }

    @Test
    fun testChecksumCalculation() {
        // Standard IP checksum test with known vector
        val header = byteArrayOf(
            0x45.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
            0x1c.toByte(), 0x46.toByte(), 0x40.toByte(), 0x00.toByte(),
            0x40.toByte(), 0x06.toByte(), 0x00.toByte(), 0x00.toByte(), // checksum at offset 10-11
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x63.toByte(),
            0xac.toByte(), 0x10.toByte(), 0x0a.toByte(), 0x0c.toByte()
        )
        val cksum = PacketUtils.checksum(header, 0, header.size)
        assertTrue(cksum in 0..65535)

        // Inject checksum back into header and re-verify
        header[10] = (cksum shr 8).toByte()
        header[11] = (cksum and 0xFF).toByte()
        assertEquals(0, PacketUtils.checksum(header, 0, header.size))
    }
}
