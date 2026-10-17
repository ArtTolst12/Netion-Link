package com.example.netion.vpn

import android.net.VpnService
import android.util.Log
import com.example.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Resolves DNS queries arriving on TUN (UDP port 53).
 *
 * In an Android-to-Android hotspot or restricted network environment, the OS kernel
 * drops non-VPN transit UDP packets, so raw UDP DatagramSocket to 8.8.8.8 fails with timeout.
 * This resolver forwards DNS queries as RFC 1035 DNS-over-TCP through the established
 * upstream proxy (SOCKS5 or HTTP CONNECT to 8.8.8.8:53 / 1.1.1.1:53), packing the response
 * back into standard UDP packets for the TUN interface.
 * Falls back to protected UDP socket if proxy parameters are not provided.
 */
class DnsResolver(
    private val vpnService: VpnService,
    private val tunWriter: TunWriter,
    private val proxyIp: String? = null,
    private val proxyPort: Int = 0,
    private val isSocks: Boolean = false
) {
    companion object {
        private const val TAG = "DnsResolver"
        private const val DNS_TIMEOUT_MS = 6_000
        private const val MAX_DNS_THREADS = 8
        private const val MAX_DNS_QUEUE = 64
    }

    // Bounded thread pool: max 8 concurrent DNS resolutions with a bounded queue of 64 requests.
    // AbortPolicy throws RejectedExecutionException when queue is saturated, allowing immediate SERVFAIL response.
    private val executor: ExecutorService = java.util.concurrent.ThreadPoolExecutor(
        2,
        MAX_DNS_THREADS,
        30L,
        java.util.concurrent.TimeUnit.SECONDS,
        java.util.concurrent.LinkedBlockingQueue(MAX_DNS_QUEUE),
        java.util.concurrent.ThreadPoolExecutor.AbortPolicy()
    )
    @Volatile private var closed = false

    /**
     * Handle a DNS query packet from TUN.
     * Forwards to the real DNS server and writes the response back to TUN.
     */
    fun handleQuery(
        ipHeader: PacketUtils.IpHeader,
        udpHeader: PacketUtils.UdpHeader,
        queryPayload: ByteArray
    ) {
        if (closed || queryPayload.isEmpty()) return
        val srcIp = ipHeader.srcIp.copyOf()
        val dstIp = ipHeader.dstIp.copyOf()
        val srcPort = udpHeader.srcPort
        val dstPort = udpHeader.dstPort

        val task = Runnable {
            if (closed) return@Runnable
            var resolved = false

            // 1. Try DNS-over-TCP via proxy if proxy settings are available
            if (!proxyIp.isNullOrEmpty() && proxyPort > 0) {
                try {
                    resolved = resolveViaProxyTcp(srcIp, dstIp, srcPort, dstPort, queryPayload)
                    if (resolved) {
                        AppLogger.s("DNS", "DNS успешно разрешен через TCP прокси $proxyIp:$proxyPort")
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy DNS-over-TCP attempt failed: ${e.message}")
                    AppLogger.w("DNS", "DNS через прокси $proxyIp:$proxyPort не удался: ${e.message}")
                }
            }

            // 2. Fallback to direct protected UDP DatagramSocket if proxy resolution didn't succeed
            if (!resolved && !closed) {
                try {
                    resolveViaDirectUdp(srcIp, dstIp, srcPort, dstPort, queryPayload)
                    AppLogger.s("DNS", "DNS разрешен через прямой UDP к ${PacketUtils.ipToString(dstIp)}")
                } catch (e: Exception) {
                    Log.d(TAG, "Direct UDP DNS failed: ${e.message}")
                    AppLogger.e("DNS", "Прямой UDP DNS к ${PacketUtils.ipToString(dstIp)} не удался: ${e.message}")
                }
            }
        }

        try {
            executor.execute(task)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Queue full: reply immediately with DNS SERVFAIL (RCODE=2) so OS client fails fast without hanging
            sendDnsServFail(srcIp, dstIp, srcPort, dstPort, queryPayload)
        }
    }

    private fun resolveViaProxyTcp(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray
    ): Boolean {
        val socket = Socket()
        vpnService.protect(socket)
        val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(vpnService, proxyIp)
        if (underlyingNetwork != null) {
            try {
                underlyingNetwork.bindSocket(socket)
            } catch (_: Exception) {}
        }
        socket.soTimeout = DNS_TIMEOUT_MS
        socket.tcpNoDelay = true

        try {
            socket.connect(InetSocketAddress(proxyIp, proxyPort), DNS_TIMEOUT_MS)
            val output = socket.getOutputStream()
            val input = socket.getInputStream()

            val dnsHost = PacketUtils.ipToString(dnsIp)
            val targetPort = if (dnsPort > 0) dnsPort else 53

            if (isSocks) {
                // SOCKS5 Handshake
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()

                val gVer = input.read()
                val gMethod = input.read()
                if (gVer != 5 || gMethod != 0) return false

                // SOCKS5 CONNECT to dnsHost:targetPort
                val req = ByteArray(10)
                req[0] = 0x05
                req[1] = 0x01 // CONNECT
                req[2] = 0x00 // RSV
                req[3] = 0x01 // IPv4
                System.arraycopy(dnsIp, 0, req, 4, 4)
                req[8] = (targetPort shr 8).toByte()
                req[9] = (targetPort and 0xFF).toByte()
                output.write(req)
                output.flush()

                val rVer = input.read()
                val rRep = input.read()
                input.read() // RSV
                val rAtyp = input.read()
                if (rVer != 5 || rRep != 0) return false

                when (rAtyp) {
                    1 -> readFully(input, ByteArray(6))
                    3 -> {
                        val len = input.read()
                        if (len > 0) readFully(input, ByteArray(len + 2))
                    }
                    4 -> readFully(input, ByteArray(18))
                    else -> return false
                }
            } else {
                // HTTP CONNECT Handshake
                val req = "CONNECT $dnsHost:$targetPort HTTP/1.1\r\nHost: $dnsHost:$targetPort\r\n\r\n"
                output.write(req.toByteArray(Charsets.US_ASCII))
                output.flush()

                val resp = readHttpResponseLine(input)
                if (!resp.startsWith("HTTP/1.1 200") && !resp.startsWith("HTTP/1.0 200")) {
                    return false
                }
            }

            // RFC 1035: DNS over TCP frames queries and responses with a 2-byte big-endian length prefix
            val qLen = queryPayload.size
            output.write((qLen shr 8).toByte().toInt())
            output.write((qLen and 0xFF).toByte().toInt())
            output.write(queryPayload)
            output.flush()

            val respLenHi = input.read()
            val respLenLo = input.read()
            if (respLenHi == -1 || respLenLo == -1) return false
            val respLen = (respLenHi shl 8) or respLenLo
            if (respLen <= 0 || respLen > 4096) return false

            val dnsResponse = ByteArray(respLen)
            readFully(input, dnsResponse)

            // Construct UDP packet for TUN client
            val pkt = PacketUtils.buildUdpPacket(
                srcIp   = dnsIp,
                dstIp   = clientIp,
                srcPort = dnsPort,
                dstPort = clientPort,
                payload = dnsResponse
            )
            tunWriter.write(pkt)
            return true
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun resolveViaDirectUdp(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray
    ) {
        val socket = DatagramSocket()
        try {
            vpnService.protect(socket)
            val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(vpnService)
            if (underlyingNetwork != null) {
                try {
                    underlyingNetwork.bindSocket(socket)
                } catch (_: Exception) {}
            }
            socket.soTimeout = DNS_TIMEOUT_MS

            val dnsServer = InetAddress.getByAddress(dnsIp)
            val request = DatagramPacket(queryPayload, queryPayload.size, dnsServer, dnsPort)
            socket.send(request)

            val response = ByteArray(4096)
            val resp = DatagramPacket(response, response.size)
            socket.receive(resp)

            val pkt = PacketUtils.buildUdpPacket(
                srcIp   = dnsIp,
                dstIp   = clientIp,
                srcPort = dnsPort,
                dstPort = clientPort,
                payload = response.copyOf(resp.length)
            )
            tunWriter.write(pkt)
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw java.io.EOFException("Unexpected EOF reading DNS buffer")
            offset += count
        }
    }

    private fun readHttpResponseLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") break
            if (sb.length > 2048) break
        }
        return sb.toString().lines().firstOrNull() ?: ""
    }

    private fun sendDnsServFail(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray
    ) {
        if (queryPayload.size < 12) return // Need at least DNS 12-byte header
        try {
            val response = queryPayload.copyOf()
            // Set QR=1 (bit 7 of byte 2), Opcode=0, RCODE=2 (Server Failure: bits 0..3 of byte 3)
            response[2] = (response[2].toInt() or 0x80).toByte() // QR = 1 (response)
            response[3] = ((response[3].toInt() and 0xF0) or 0x02).toByte() // RCODE = 2 (SERVFAIL)

            val pkt = PacketUtils.buildUdpPacket(
                srcIp   = dnsIp,
                dstIp   = clientIp,
                srcPort = dnsPort,
                dstPort = clientPort,
                payload = response
            )
            tunWriter.write(pkt)
            AppLogger.w("DNS", "Очередь DNS перегружена: отправлен SERVFAIL клиенту для порта $clientPort")
        } catch (_: Exception) {}
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
