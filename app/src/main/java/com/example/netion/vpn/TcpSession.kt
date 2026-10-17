package com.example.netion.vpn

import android.net.VpnService
import android.util.Log
import com.example.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One TCP connection intercepted from TUN and tunnelled through
 * the upstream HTTP proxy (CONNECT tunnel) or SOCKS5 proxy.
 *
 * Lifecycle: CONNECTING → ESTABLISHED → CLOSED.
 */
class TcpSession(
    private val srcIp: ByteArray,   // client (TUN) side
    private val srcPort: Int,
    private val dstIp: ByteArray,   // real destination
    private val dstPort: Int,
    private val proxyIp: String,
    private val proxyPort: Int,
    private val isSocks5: Boolean = (proxyPort == 1080),
    private val vpnService: VpnService,
    private val tunWriter: TunWriter
) {
    companion object {
        private const val TAG = "TcpSession"
        private const val MSS = 1360  // safe MSS for MTU 1400 (leaves room for IP/TCP headers)
    }

    // TCP state
    @Volatile var state = State.CONNECTING; private set
    private val closed = AtomicBoolean(false)
    val isDead get() = closed.get()

    // Lock protecting TCP sequence numbers and packet sending
    private val stateLock = Any()

    // Sequence numbers (unsigned-32 in Long)
    private var initialOurSeq: Long = (System.nanoTime() and 0xFFFFFFFFL) // ISN
    private var ourSeq: Long = initialOurSeq
    private var theirSeq: Long = 0L  // set from SYN

    // Track last activity time (read/write/ack/syn) to detect orphaned/stale connections
    @Volatile var lastActivityTime: Long = System.currentTimeMillis(); private set

    fun markActive() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Checks if session is orphaned/abandoned:
     * - CONNECTING for > 20s without establishing
     * - ESTABLISHED with zero activity for > 900s (15 minutes).
     *   SO_KEEPALIVE keeps active long-polling / WebSocket connections alive by sending TCP keepalive probes.
     *   If a remote host goes away silently without FIN/RST, OS keepalive will fail and drop the socket.
     *   The 15-minute ceiling prevents truly abandoned dead sockets from lingering forever.
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (isDead) return true
        val idleMs = now - lastActivityTime
        return when (state) {
            State.CONNECTING -> idleMs > 20_000L
            State.ESTABLISHED -> idleMs > 900_000L // 15 minutes safe idle threshold
            State.CLOSED -> true
        }
    }

    // Background single-thread executor for proxy writes to never block the TUN read loop
    private val sendExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    // Upstream proxy socket
    private var proxySocket: Socket? = null
    private var proxyOut: OutputStream? = null
    private var proxyIn: InputStream? = null
    private var readerThread: Thread? = null

    enum class State { CONNECTING, ESTABLISHED, CLOSED }

    // ─────────────────────────────────────────────────────────
    // Called from TUN read loop
    // ─────────────────────────────────────────────────────────

    fun handleSyn(clientSeq: Long) {
        markActive()
        synchronized(stateLock) {
            if (state == State.ESTABLISHED) {
                // Re-send SYN-ACK if client retransmitted SYN
                sendTcpPacketLocked(
                    PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
                    ByteArray(0),
                    includeMss = true,
                    overrideSeq = initialOurSeq
                )
                return
            }
            if (state == State.CLOSED) return
            theirSeq = (clientSeq + 1) and 0xFFFFFFFFL // expect next byte
        }
        Thread({ connectAndHandshake() }, "tcp-connect-${key()}").also {
            it.isDaemon = true
            it.start()
        }
    }

    fun handleData(tcp: PacketUtils.TcpHeader, payload: ByteArray) {
        if (state != State.ESTABLISHED || isDead) return
        markActive()
        var toForward: ByteArray? = null

        synchronized(stateLock) {
            if (state != State.ESTABLISHED || isDead) return

            // Modular 32-bit sequence distance: (tcp.seqNum - theirSeq)
            val diff = (tcp.seqNum - theirSeq) and 0xFFFFFFFFL
            if (diff == 0L) {
                // Expected in-order segment
                theirSeq = (theirSeq + payload.size) and 0xFFFFFFFFL
                sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
                toForward = payload
            } else if (diff > 0x7FFFFFFFL) {
                // tcp.seqNum < theirSeq: retransmission or overlapping segment
                val alreadyReceived = ((theirSeq - tcp.seqNum) and 0xFFFFFFFFL).toInt()
                if (alreadyReceived >= payload.size) {
                    // Fully duplicate segment: do NOT duplicate write to proxy, just re-ACK
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
                } else {
                    // Partially new segment: only write the unreceived slice
                    val newBytes = payload.copyOfRange(alreadyReceived, payload.size)
                    theirSeq = (theirSeq + newBytes.size) and 0xFFFFFFFFL
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
                    toForward = newBytes
                }
            } else {
                // Out-of-order segment ahead of theirSeq: prompt retransmission with cumulative ACK
                sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
            }
        }

        // Asynchronously forward payload to proxy without blocking TUN read loop
        toForward?.let { chunk ->
            if (!sendExecutor.isShutdown) {
                try {
                    sendExecutor.execute {
                        try {
                            proxyOut?.write(chunk)
                            proxyOut?.flush()
                        } catch (e: Exception) {
                            Log.d(TAG, "Proxy write failed: ${e.message}")
                            sendRst()
                            close()
                        }
                    }
                } catch (_: Exception) {
                    close()
                }
            }
        }
    }

    fun handleAck(tcp: PacketUtils.TcpHeader) {
        markActive()
    }

    fun handleFin(tcp: PacketUtils.TcpHeader) {
        synchronized(stateLock) {
            if (isDead) return
            theirSeq = (tcp.seqNum + 1) and 0xFFFFFFFFL
            // Acknowledge client's FIN
            sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
        }

        // TCP Half-Close: inform upstream that client finished uploading,
        // but keep readerThread alive to continue receiving server responses
        if (!sendExecutor.isShutdown) {
            try {
                sendExecutor.execute {
                    try {
                        if (proxySocket?.isOutputShutdown == false && proxySocket?.isClosed == false) {
                            proxySocket?.shutdownOutput()
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun handleRst() {
        close()
    }

    // ─────────────────────────────────────────────────────────
    // Proxy connection
    // ─────────────────────────────────────────────────────────

    private fun connectAndHandshake() {
        val dstStr = PacketUtils.ipToString(dstIp)
        try {
            AppLogger.i("Client", "Подключение к прокси $proxyIp:$proxyPort для $dstStr:$dstPort...")
            val sock = Socket()
            vpnService.protect(sock)
            val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(vpnService, proxyIp)
            if (underlyingNetwork != null) {
                try {
                    underlyingNetwork.bindSocket(sock)
                    AppLogger.d("Client", "Сокет привязан к базовой сети: $underlyingNetwork")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind socket to network $underlyingNetwork: ${e.message}")
                }
            }
            sock.connect(InetSocketAddress(proxyIp, proxyPort), 10_000)
            sock.soTimeout = 0
            sock.tcpNoDelay = true
            sock.keepAlive = true
            proxySocket = sock
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            proxyOut = out
            proxyIn = inp
            AppLogger.s("Client", "Соединение с $proxyIp:$proxyPort установлено! Рукопожатие для $dstStr:$dstPort...")

            if (isSocks5) {
                // SOCKS5 Handshake
                // 1. Send greeting: VER 5, 1 auth method: 0 (No Auth)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()

                val ver = inp.read()
                val method = inp.read()
                if (ver != 5 || method != 0) {
                    Log.w(TAG, "SOCKS5 greeting rejected: ver=$ver, method=$method")
                    AppLogger.e("Client", "SOCKS5 прокси $proxyIp:$proxyPort отклонил метод аутентификации (ver=$ver, method=$method)")
                    sendRst()
                    close()
                    return
                }

                // 2. Send CONNECT request for dstIp:dstPort
                val req = ByteArray(10)
                req[0] = 0x05
                req[1] = 0x01 // CONNECT
                req[2] = 0x00 // RSV
                req[3] = 0x01 // IPv4
                System.arraycopy(dstIp, 0, req, 4, 4)
                req[8] = (dstPort shr 8).toByte()
                req[9] = (dstPort and 0xFF).toByte()
                out.write(req)
                out.flush()

                // 3. Read response: VER, REP, RSV, ATYP...
                val respVer = inp.read()
                val respRep = inp.read()
                inp.read() // RSV
                val respAtyp = inp.read()
                if (respVer != 5 || respRep != 0) {
                    Log.w(TAG, "SOCKS5 connect rejected: rep=$respRep")
                    AppLogger.e("Client", "SOCKS5 прокси отклонил CONNECT к $dstStr:$dstPort (код ответа=$respRep)")
                    sendRst()
                    close()
                    return
                }
                when (respAtyp) {
                    1 -> { val b = ByteArray(6); readFully(inp, b) }
                    3 -> { val len = inp.read(); val b = ByteArray(len + 2); readFully(inp, b) }
                    4 -> { val b = ByteArray(18); readFully(inp, b) }
                }
            } else {
                // HTTP CONNECT Handshake
                val req = "CONNECT $dstStr:$dstPort HTTP/1.1\r\nHost: $dstStr:$dstPort\r\n\r\n"
                out.write(req.toByteArray())
                out.flush()

                val resp = readHttpResponse(inp)
                if (!resp.startsWith("HTTP/1.1 200") && !resp.startsWith("HTTP/1.0 200")) {
                    Log.w(TAG, "Proxy CONNECT rejected: $resp")
                    AppLogger.e("Client", "HTTP прокси отклонил CONNECT к $dstStr:$dstPort: $resp")
                    sendRst()
                    close()
                    return
                }
            }

            synchronized(stateLock) {
                if (isDead) {
                    try { sock.close() } catch (_: Exception) {}
                    return
                }
                // Send SYN-ACK to client
                sendTcpPacketLocked(
                    PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
                    ByteArray(0),
                    includeMss = true,
                    overrideSeq = initialOurSeq
                )
                ourSeq = (initialOurSeq + 1) and 0xFFFFFFFFL  // SYN consumes 1 seq
                state = State.ESTABLISHED
                AppLogger.s("Client", "Туннель открыт к $dstStr:$dstPort через $proxyIp:$proxyPort")
            }

            // Start reading from proxy
            readerThread = Thread({ proxyReaderLoop() }, "tcp-read-${key()}").also {
                it.isDaemon = true
                it.start()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Proxy connect/handshake failed for [${key()}]: [${e.javaClass.simpleName}] ${e.message}", e)
            AppLogger.e("Client", "Не удалось подключиться к прокси $proxyIp:$proxyPort для $dstStr:$dstPort: ${e.message}")
            sendRst()
            close()
        }
    }

    /** Reads data from upstream proxy and sends as TCP data packets to TUN */
    private fun proxyReaderLoop() {
        val buf = ByteArray(MSS)
        try {
            while (!isDead) {
                val n = proxyIn?.read(buf) ?: break
                if (n == -1) break
                markActive()
                synchronized(stateLock) {
                    if (isDead) break
                    // Send data to client via TUN
                    sendTcpPacketLocked(PacketUtils.TCP_ACK or PacketUtils.TCP_PSH, buf.copyOf(n))
                    ourSeq = (ourSeq + n) and 0xFFFFFFFFL
                }
            }
        } catch (_: Exception) {
            // Socket closed or error
        }
        synchronized(stateLock) {
            if (!isDead) {
                // Remote proxy closed → send FIN to client
                sendTcpPacketLocked(PacketUtils.TCP_FIN or PacketUtils.TCP_ACK, ByteArray(0))
                ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
            }
        }
        close()
    }

    // ─────────────────────────────────────────────────────────
    // Packet sending
    // ─────────────────────────────────────────────────────────

    private fun sendTcpPacketLocked(
        flags: Int,
        payload: ByteArray,
        includeMss: Boolean = false,
        overrideSeq: Long? = null
    ) {
        if (isDead && (flags and PacketUtils.TCP_RST) == 0) return
        val seq = overrideSeq ?: ourSeq
        try {
            val pkt = PacketUtils.buildTcpPacket(
                srcIp      = dstIp,    // swap: we pretend to be the destination
                dstIp      = srcIp,
                srcPort    = dstPort,
                dstPort    = srcPort,
                seqNum     = seq,
                ackNum     = theirSeq,
                flags      = flags,
                payload    = payload,
                includeMss = includeMss
            )
            tunWriter.write(pkt)
        } catch (e: Exception) {
            Log.d(TAG, "TUN write failed: ${e.message}")
        }
    }

    private fun sendRst() {
        synchronized(stateLock) {
            try {
                val pkt = PacketUtils.buildTcpPacket(
                    srcIp   = dstIp,
                    dstIp   = srcIp,
                    srcPort = dstPort,
                    dstPort = srcPort,
                    seqNum  = ourSeq,
                    ackNum  = theirSeq,
                    flags   = PacketUtils.TCP_RST or PacketUtils.TCP_ACK
                )
                tunWriter.write(pkt)
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        state = State.CLOSED
        readerThread?.interrupt()
        sendExecutor.shutdownNow()
        try { proxySocket?.close() } catch (_: Exception) {}
    }

    fun key() = "$srcPort→${PacketUtils.ipToString(dstIp)}:$dstPort"

    // ─────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────

    private fun readFully(input: InputStream, b: ByteArray) {
        var total = 0
        while (total < b.size) {
            val count = input.read(b, total, b.size - total)
            if (count < 0) throw java.io.EOFException("Premature EOF")
            total += count
        }
    }

    private fun readHttpResponse(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.length >= 4 && sb.substring(sb.length - 4) == "\r\n\r\n") break
            if (sb.length > 4096) break // safety
        }
        return sb.toString().lines().firstOrNull() ?: ""
    }
}

/**
 * Thread-safe wrapper for writing packets to TUN FileOutputStream.
 */
class TunWriter(
    private val fd: java.io.FileOutputStream,
    private val onBytesWritten: ((Int) -> Unit)? = null
) {
    private val lock = Any()
    fun write(packet: ByteArray) {
        synchronized(lock) {
            fd.write(packet)
            fd.flush()
        }
        onBytesWritten?.invoke(packet.size)
    }
}
