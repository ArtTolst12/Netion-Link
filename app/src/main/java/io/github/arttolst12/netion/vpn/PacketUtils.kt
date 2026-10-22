package io.github.arttolst12.netion.vpn

/**
 * Low-level utilities for parsing and constructing IPv4 / TCP / UDP packets.
 * All methods work on raw byte arrays (as read from / written to TUN fd).
 */
object PacketUtils {

    const val IP4_HEADER_SIZE = 20
    const val TCP_HEADER_SIZE = 20
    const val TCP_HEADER_SIZE_MSS = 24  // with MSS option
    const val UDP_HEADER_SIZE = 8
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    // TCP flags
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    // ───────── Data classes ─────────

    data class IpHeader(
        val ihl: Int,             // header length in bytes
        val totalLength: Int,
        val protocol: Int,
        val srcIp: ByteArray,     // 4 bytes
        val dstIp: ByteArray,     // 4 bytes
    )

    data class TcpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val seqNum: Long,         // unsigned 32-bit stored in Long
        val ackNum: Long,
        val dataOffset: Int,      // header length in bytes
        val flags: Int,
        val windowSize: Int,
    )

    data class UdpHeader(
        val srcPort: Int,
        val dstPort: Int,
        val length: Int,
    )

    // ───────── Parsing ─────────

    /**
     * Parses a complete, non-fragmented IPv4 packet prefix.
     * [len] is the number of valid bytes in [data], which may be a reusable buffer.
     */
    fun parseIpHeader(data: ByteArray, len: Int): IpHeader? {
        if (len < 0 || len > data.size || len < IP4_HEADER_SIZE) return null

        val versionIhl = data[0].toInt() and 0xFF
        if ((versionIhl ushr 4) != 4) return null // IPv4 only

        val ihl = (versionIhl and 0x0F) * 4
        if (ihl < IP4_HEADER_SIZE || ihl > len) return null

        val totalLength = u16(data, 2)
        if (totalLength < ihl || totalLength > len) return null

        // Fragments cannot safely be interpreted as self-contained TCP/UDP packets.
        val flagsAndFragmentOffset = u16(data, 6)
        val moreFragments = (flagsAndFragmentOffset and 0x2000) != 0
        val fragmentOffset = flagsAndFragmentOffset and 0x1FFF
        if (moreFragments || fragmentOffset != 0) return null

        return IpHeader(
            ihl = ihl,
            totalLength = totalLength,
            protocol = data[9].toInt() and 0xFF,
            srcIp = data.copyOfRange(12, 16),
            dstIp = data.copyOfRange(16, 20),
        )
    }

    /**
     * Parses a TCP header. [packetLength] permits callers using reusable buffers to
     * restrict parsing to valid bytes; the IPv4 total length is the transport bound.
     */
    fun parseTcpHeader(
        data: ByteArray,
        ipHeaderLen: Int,
        packetLength: Int = data.size,
    ): TcpHeader? {
        val ipTotalLength = validIpv4TransportLength(data, ipHeaderLen, packetLength) ?: return null
        if (ipTotalLength - ipHeaderLen < TCP_HEADER_SIZE) return null

        val o = ipHeaderLen
        val dataOffset = ((data[o + 12].toInt() and 0xFF) ushr 4) * 4
        val transportLength = ipTotalLength - ipHeaderLen
        if (dataOffset < TCP_HEADER_SIZE || dataOffset > transportLength) return null

        return TcpHeader(
            srcPort = u16(data, o),
            dstPort = u16(data, o + 2),
            seqNum = u32(data, o + 4),
            ackNum = u32(data, o + 8),
            dataOffset = dataOffset,
            flags = data[o + 13].toInt() and 0x3F,
            windowSize = u16(data, o + 14),
        )
    }

    /**
     * Parses a UDP header. [packetLength] permits callers using reusable buffers to
     * restrict parsing to valid bytes; the IPv4 total length is the transport bound.
     */
    fun parseUdpHeader(
        data: ByteArray,
        ipHeaderLen: Int,
        packetLength: Int = data.size,
    ): UdpHeader? {
        val ipTotalLength = validIpv4TransportLength(data, ipHeaderLen, packetLength) ?: return null
        val ipPayloadLength = ipTotalLength - ipHeaderLen
        if (ipPayloadLength < UDP_HEADER_SIZE) return null

        val o = ipHeaderLen
        val udpLength = u16(data, o + 4)
        if (udpLength < UDP_HEADER_SIZE || udpLength > ipPayloadLength) return null

        return UdpHeader(
            srcPort = u16(data, o),
            dstPort = u16(data, o + 2),
            length = udpLength,
        )
    }

    fun tcpPayload(data: ByteArray, ipHeader: IpHeader, tcpHeader: TcpHeader): ByteArray {
        if (!validIpHeaderForPayload(data, ipHeader)) return ByteArray(0)
        val start = ipHeader.ihl.toLong() + tcpHeader.dataOffset.toLong()
        val end = ipHeader.totalLength.toLong()
        if (tcpHeader.dataOffset < TCP_HEADER_SIZE || start >= end || start > data.size) return ByteArray(0)
        return data.copyOfRange(start.toInt(), end.toInt())
    }

    /**
     * Extracts payload from a valid UDP packet. The end is taken from the UDP length
     * field rather than the enclosing IPv4 packet, excluding trailing IP bytes.
     */
    fun udpPayload(data: ByteArray, ipHeader: IpHeader): ByteArray {
        if (!validIpHeaderForPayload(data, ipHeader)) return ByteArray(0)
        val udpHeader = parseUdpHeader(data, ipHeader.ihl, ipHeader.totalLength) ?: return ByteArray(0)
        val start = ipHeader.ihl + UDP_HEADER_SIZE
        val end = ipHeader.ihl + udpHeader.length
        if (start >= end) return ByteArray(0)
        return data.copyOfRange(start, end)
    }

    // ───────── Building ─────────

    /**
     * Build a complete IPv4 + TCP packet.
     * @param includeMss if true, adds MSS=1460 option (for SYN-ACK)
     */
    fun buildTcpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        seqNum: Long, ackNum: Long,
        flags: Int,
        payload: ByteArray = ByteArray(0),
        windowSize: Int = 65535,
        includeMss: Boolean = false
    ): ByteArray {
        requireIpv4(srcIp, "srcIp")
        requireIpv4(dstIp, "dstIp")
        requirePort(srcPort, "srcPort")
        requirePort(dstPort, "dstPort")
        requireU16(windowSize, "windowSize")
        requireU32(seqNum, "seqNum")
        requireU32(ackNum, "ackNum")
        require(flags in 0..0xFF) { "flags must be in 0..255" }

        val tcpHeaderLen = if (includeMss) TCP_HEADER_SIZE_MSS else TCP_HEADER_SIZE
        val maxPayloadLength = MAX_IP4_TOTAL_LENGTH - IP4_HEADER_SIZE - tcpHeaderLen
        require(payload.size <= maxPayloadLength) {
            "payload is too large for an IPv4 TCP packet: ${payload.size} > $maxPayloadLength"
        }
        val totalLen = IP4_HEADER_SIZE + tcpHeaderLen + payload.size
        val pkt = ByteArray(totalLen)

        // ── IP header ──
        pkt[0] = 0x45.toByte() // v4, IHL=5
        w16(pkt, 2, totalLen)
        pkt[6] = 0x40.toByte() // Don't fragment
        pkt[8] = 64 // TTL
        pkt[9] = PROTO_TCP.toByte()
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        val ipCksum = checksum(pkt, 0, IP4_HEADER_SIZE)
        w16(pkt, 10, ipCksum)

        // ── TCP header ──
        val t = IP4_HEADER_SIZE
        w16(pkt, t, srcPort)
        w16(pkt, t + 2, dstPort)
        w32(pkt, t + 4, seqNum)
        w32(pkt, t + 8, ackNum)
        pkt[t + 12] = ((tcpHeaderLen / 4) shl 4).toByte()
        pkt[t + 13] = flags.toByte()
        w16(pkt, t + 14, windowSize)

        // MSS option (kind=2, len=4, value=1360 for MTU 1400)
        if (includeMss) {
            pkt[t + 20] = 2
            pkt[t + 21] = 4
            w16(pkt, t + 22, 1360)
        }

        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, t + tcpHeaderLen, payload.size)
        }

        val tcpCksum = tcpUdpChecksum(srcIp, dstIp, PROTO_TCP, pkt, t, tcpHeaderLen + payload.size)
        w16(pkt, t + 16, tcpCksum)

        return pkt
    }

    /** Build a complete IPv4 + UDP packet. */
    fun buildUdpPacket(
        srcIp: ByteArray, dstIp: ByteArray,
        srcPort: Int, dstPort: Int,
        payload: ByteArray
    ): ByteArray {
        requireIpv4(srcIp, "srcIp")
        requireIpv4(dstIp, "dstIp")
        requirePort(srcPort, "srcPort")
        requirePort(dstPort, "dstPort")
        val maxPayloadLength = MAX_IP4_TOTAL_LENGTH - IP4_HEADER_SIZE - UDP_HEADER_SIZE
        require(payload.size <= maxPayloadLength) {
            "payload is too large for an IPv4 UDP packet: ${payload.size} > $maxPayloadLength"
        }

        val udpLen = UDP_HEADER_SIZE + payload.size
        val totalLen = IP4_HEADER_SIZE + udpLen
        val pkt = ByteArray(totalLen)

        // IP header
        pkt[0] = 0x45.toByte()
        w16(pkt, 2, totalLen)
        pkt[6] = 0x40.toByte()
        pkt[8] = 64
        pkt[9] = PROTO_UDP.toByte()
        System.arraycopy(srcIp, 0, pkt, 12, 4)
        System.arraycopy(dstIp, 0, pkt, 16, 4)
        w16(pkt, 10, checksum(pkt, 0, IP4_HEADER_SIZE))

        // UDP header
        val u = IP4_HEADER_SIZE
        w16(pkt, u, srcPort)
        w16(pkt, u + 2, dstPort)
        w16(pkt, u + 4, udpLen)
        System.arraycopy(payload, 0, pkt, u + UDP_HEADER_SIZE, payload.size)

        // RFC 768 reserves zero to mean no checksum; a calculated zero is transmitted as 0xFFFF.
        val udpCksum = tcpUdpChecksum(srcIp, dstIp, PROTO_UDP, pkt, u, udpLen)
        w16(pkt, u + 6, if (udpCksum == 0) 0xFFFF else udpCksum)

        return pkt
    }

    // ───────── Checksums ─────────

    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= data.size - length) {
            "checksum range is outside data"
        }
        var sum = 0L
        var i = offset
        var rem = length
        while (rem > 1) {
            sum += u16(data, i).toLong()
            i += 2; rem -= 2
        }
        if (rem == 1) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun tcpUdpChecksum(
        srcIp: ByteArray, dstIp: ByteArray,
        proto: Int,
        data: ByteArray, offset: Int, length: Int
    ): Int {
        var sum = 0L
        // Pseudo-header
        for (i in 0..1) sum += u16(srcIp, i * 2).toLong()
        for (i in 0..1) sum += u16(dstIp, i * 2).toLong()
        sum += proto.toLong()
        sum += length.toLong()
        // Data
        var i = offset; var rem = length
        while (rem > 1) {
            sum += u16(data, i).toLong()
            i += 2; rem -= 2
        }
        if (rem == 1) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    // ───────── Helpers ─────────

    fun ipToString(ip: ByteArray): String {
        require(ip.size == IPV4_ADDRESS_LENGTH) { "IPv4 address must contain exactly 4 bytes" }
        return "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
            "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    /** Parses dotted-decimal IPv4 only; host names and abbreviated forms are rejected. */
    fun ipToBytes(ip: String): ByteArray {
        require(ip.isNotEmpty() && ip.first() != '.' && ip.last() != '.') { "Invalid IPv4 address: $ip" }
        val parts = ip.split('.')
        require(parts.size == IPV4_ADDRESS_LENGTH) { "Invalid IPv4 address: $ip" }
        return ByteArray(IPV4_ADDRESS_LENGTH) { index ->
            val part = parts[index]
            require(part.isNotEmpty() && part.all { it in '0'..'9' }) { "Invalid IPv4 address: $ip" }
            val value = part.toIntOrNull() ?: throw IllegalArgumentException("Invalid IPv4 address: $ip")
            require(value in 0..255) { "Invalid IPv4 address: $ip" }
            value.toByte()
        }
    }

    private fun validIpv4TransportLength(data: ByteArray, ipHeaderLen: Int, packetLength: Int): Int? {
        if (packetLength < 0 || packetLength > data.size) return null
        if (ipHeaderLen < IP4_HEADER_SIZE || ipHeaderLen > packetLength) return null
        if (packetLength < IP4_HEADER_SIZE) return null

        val versionIhl = data[0].toInt() and 0xFF
        if ((versionIhl ushr 4) != 4 || (versionIhl and 0x0F) * 4 != ipHeaderLen) return null

        val totalLength = u16(data, 2)
        if (totalLength < ipHeaderLen || totalLength > packetLength) return null

        val flagsAndFragmentOffset = u16(data, 6)
        if ((flagsAndFragmentOffset and 0x2000) != 0 || (flagsAndFragmentOffset and 0x1FFF) != 0) return null
        return totalLength
    }

    private fun validIpHeaderForPayload(data: ByteArray, ipHeader: IpHeader): Boolean {
        if (ipHeader.ihl < IP4_HEADER_SIZE || ipHeader.totalLength < ipHeader.ihl ||
            ipHeader.totalLength > data.size || ipHeader.ihl > data.size
        ) return false
        return validIpv4TransportLength(data, ipHeader.ihl, ipHeader.totalLength) == ipHeader.totalLength
    }

    private fun requireIpv4(ip: ByteArray, name: String) {
        require(ip.size == IPV4_ADDRESS_LENGTH) { "$name must contain exactly 4 bytes" }
    }

    private fun requirePort(port: Int, name: String) = requireU16(port, name)

    private fun requireU16(value: Int, name: String) {
        require(value in 0..0xFFFF) { "$name must be in 0..65535" }
    }

    private fun requireU32(value: Long, name: String) {
        require(value in 0..0xFFFF_FFFFL) { "$name must be an unsigned 32-bit value" }
    }

    private fun u16(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
            ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

    private fun w16(d: ByteArray, o: Int, v: Int) {
        d[o] = (v shr 8).toByte()
        d[o + 1] = (v and 0xFF).toByte()
    }

    private fun w32(d: ByteArray, o: Int, v: Long) {
        d[o] = (v shr 24).toByte()
        d[o + 1] = (v shr 16).toByte()
        d[o + 2] = (v shr 8).toByte()
        d[o + 3] = (v and 0xFF).toByte()
    }

    private const val IPV4_ADDRESS_LENGTH = 4
    private const val MAX_IP4_TOTAL_LENGTH = 0xFFFF
}
