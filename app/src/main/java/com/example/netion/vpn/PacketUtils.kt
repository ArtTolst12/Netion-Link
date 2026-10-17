package com.example.netion.vpn

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

    fun parseIpHeader(data: ByteArray, len: Int): IpHeader? {
        if (len < IP4_HEADER_SIZE) return null
        val versionIhl = data[0].toInt() and 0xFF
        if ((versionIhl ushr 4) != 4) return null // IPv4 only
        val ihl = (versionIhl and 0x0F) * 4
        val totalLength = u16(data, 2)
        val protocol = data[9].toInt() and 0xFF
        val srcIp = data.copyOfRange(12, 16)
        val dstIp = data.copyOfRange(16, 20)
        return IpHeader(ihl, totalLength, protocol, srcIp, dstIp)
    }

    fun parseTcpHeader(data: ByteArray, ipHeaderLen: Int): TcpHeader? {
        if (data.size < ipHeaderLen + TCP_HEADER_SIZE) return null
        val o = ipHeaderLen
        return TcpHeader(
            srcPort    = u16(data, o),
            dstPort    = u16(data, o + 2),
            seqNum     = u32(data, o + 4),
            ackNum     = u32(data, o + 8),
            dataOffset = ((data[o + 12].toInt() and 0xFF) ushr 4) * 4,
            flags      = data[o + 13].toInt() and 0x3F,
            windowSize = u16(data, o + 14),
        )
    }

    fun parseUdpHeader(data: ByteArray, ipHeaderLen: Int): UdpHeader? {
        if (data.size < ipHeaderLen + UDP_HEADER_SIZE) return null
        val o = ipHeaderLen
        return UdpHeader(
            srcPort = u16(data, o),
            dstPort = u16(data, o + 2),
            length  = u16(data, o + 4),
        )
    }

    fun tcpPayload(data: ByteArray, ipHeader: IpHeader, tcpHeader: TcpHeader): ByteArray {
        val start = ipHeader.ihl + tcpHeader.dataOffset
        val end = ipHeader.totalLength
        if (start >= end || start >= data.size) return ByteArray(0)
        val realEnd = minOf(end, data.size)
        return data.copyOfRange(start, realEnd)
    }

    fun udpPayload(data: ByteArray, ipHeader: IpHeader): ByteArray {
        val start = ipHeader.ihl + UDP_HEADER_SIZE
        val end = ipHeader.totalLength
        if (start >= end || start >= data.size) return ByteArray(0)
        val realEnd = minOf(end, data.size)
        return data.copyOfRange(start, realEnd)
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
        val tcpHeaderLen = if (includeMss) TCP_HEADER_SIZE_MSS else TCP_HEADER_SIZE
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

        // Payload
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, pkt, t + tcpHeaderLen, payload.size)
        }

        // TCP checksum (with pseudo-header)
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

        // UDP checksum
        val udpCksum = tcpUdpChecksum(srcIp, dstIp, PROTO_UDP, pkt, u, udpLen)
        w16(pkt, u + 6, udpCksum)

        return pkt
    }

    // ───────── Checksums ─────────

    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
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

    fun ipToString(ip: ByteArray) =
        "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
        "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"

    fun ipToBytes(ip: String): ByteArray {
        val p = ip.split(".")
        return byteArrayOf(p[0].toInt().toByte(), p[1].toInt().toByte(),
                           p[2].toInt().toByte(), p[3].toInt().toByte())
    }

    private fun u16(d: ByteArray, o: Int) =
        ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

    private fun u32(d: ByteArray, o: Int): Long =
        ((d[o].toLong() and 0xFF) shl 24) or ((d[o+1].toLong() and 0xFF) shl 16) or
        ((d[o+2].toLong() and 0xFF) shl 8) or (d[o+3].toLong() and 0xFF)

    private fun w16(d: ByteArray, o: Int, v: Int) {
        d[o]   = (v shr 8).toByte()
        d[o+1] = (v and 0xFF).toByte()
    }

    private fun w32(d: ByteArray, o: Int, v: Long) {
        d[o]   = (v shr 24).toByte()
        d[o+1] = (v shr 16).toByte()
        d[o+2] = (v shr 8).toByte()
        d[o+3] = (v and 0xFF).toByte()
    }
}
