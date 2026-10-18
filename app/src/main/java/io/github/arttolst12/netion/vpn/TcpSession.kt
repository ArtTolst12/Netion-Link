package io.github.arttolst12.netion.vpn

import android.net.VpnService
import android.util.Log
import io.github.arttolst12.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * One TCP connection intercepted from TUN and tunnelled through
 * the upstream HTTP proxy (CONNECT tunnel) or SOCKS5 proxy.
 *
 * Lifecycle: CONNECTING → ESTABLISHED → HALF_CLOSED_LOCAL → CLOSED.
 */
class TcpSession(
    private val srcIp: ByteArray,   // client (TUN) side
    private val srcPort: Int,
    private val dstIp: ByteArray,   // real destination
    private val dstPort: Int,
    private val proxyIp: String,
    private val proxyPort: Int,
    private val isSocks5: Boolean = (proxyPort == 1080),
    private val vpnService: VpnService? = null,
    private val tunWriter: TunWriter? = null,
    private val globalMemoryTracker: AtomicLong? = null,
    private val handshakeExecutor: ExecutorService? = null,
    var onEstablished: ((TcpSession, SocketChannel) -> Unit)? = null
) {
    companion object {
        private const val TAG = "TcpSession"
        const val MSS = 1360  // safe MSS for MTU 1400 (leaves room for IP/TCP headers)

        // RFC 793 / Backpressure buffer limits
        const val HARD_BUFFER_CAP = 128 * 1024 // 128 KB max per-session buffer
        const val HIGH_WATERMARK = 48 * 1024   // 48 KB: freeze rightEdge expansion
        const val LOW_WATERMARK = 16 * 1024    // 16 KB: resume window expansion / window update
        const val MAX_GLOBAL_UPLOAD_BYTES = 16L * 1024 * 1024 // 16 MB global cap across all sessions

        // Bounded shared handshake pool (max 16 concurrent handshakes, AbortPolicy on overflow)
        val defaultHandshakeExecutor: ExecutorService = ThreadPoolExecutor(
            2,
            16,
            60L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            ThreadPoolExecutor.AbortPolicy()
        )

        // Bounded shared executor for fallback upload draining when running outside NIO selector
        private val sharedDrainPool = ThreadPoolExecutor(
            2,
            4,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
            ThreadPoolExecutor.DiscardPolicy()
        )

        fun isSeqBefore(a: Long, b: Long): Boolean {
            val diff = (b - a) and 0xFFFFFFFFL
            return diff in 1L..0x7FFFFFFFL
        }

        fun isSeqBeforeOrEqual(a: Long, b: Long): Boolean {
            return a == b || isSeqBefore(a, b)
        }
    }

    // TCP state
    @Volatile var state = State.CONNECTING; private set
    private val closed = AtomicBoolean(false)
    val isDead get() = closed.get() || state == State.CLOSED

    // Locks
    private val stateLock = Any()
    private val writeLock = Any()

    // Sequence numbers (unsigned-32 in Long)
    private var initialOurSeq: Long = (System.nanoTime() and 0xFFFFFFFFL) // ISN
    private var ourSeq: Long = initialOurSeq
    var theirSeq: Long = 0L; private set  // set from SYN (RCV.NXT)
    var rightEdge: Long = 0L; private set // Monotonic advertised window right edge (RCV.NXT + RCV.WND)

    // Upload buffering & backpressure
    private val uploadQueue = ArrayDeque<ByteBuffer>()
    var uploadQueueBytes = 0; private set
    private var clientFinReceived = false
    private var proxyOutputShutdown = false

    // NIO Selector attachment
    var selectionKey: SelectionKey? = null
    var selector: Selector? = null

    // Fallback drain coalescing flag (prevents unbound task accumulation)
    private val isDraining = AtomicBoolean(false)

    // Track last activity time (read/write/ack/syn) to detect orphaned/stale connections
    @Volatile var lastActivityTime: Long = System.currentTimeMillis(); private set

    fun markActive() {
        lastActivityTime = System.currentTimeMillis()
    }

    /**
     * Checks if session is orphaned/abandoned:
     * - CONNECTING for > 20s without establishing
     * - ESTABLISHED or HALF_CLOSED_LOCAL with zero activity for > 900s (15 minutes).
     */
    fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
        if (isDead) return true
        val idleMs = now - lastActivityTime
        return when (state) {
            State.CONNECTING -> idleMs > 20_000L
            State.ESTABLISHED, State.HALF_CLOSED_LOCAL -> idleMs > 900_000L
            State.CLOSED -> true
        }
    }

    // Upstream proxy socket channel and streams
    var proxyChannel: SocketChannel? = null; private set
    private var proxySocket: Socket? = null
    private var proxyOut: OutputStream? = null
    private var proxyIn: InputStream? = null

    // Track whether connectAndHandshake was already initiated to prevent duplicate threads on retransmitted SYNs
    private val isConnectStarted = AtomicBoolean(false)

    enum class State { CONNECTING, ESTABLISHED, HALF_CLOSED_LOCAL, CLOSED }

    internal fun markEstablishedForTesting(initialSeq: Long = 1000L) {
        synchronized(stateLock) {
            theirSeq = (initialSeq + 1) and 0xFFFFFFFFL
            rightEdge = (theirSeq + minOf(65535, HARD_BUFFER_CAP)) and 0xFFFFFFFFL
            state = State.ESTABLISHED
        }
    }

    // ─────────────────────────────────────────────────────────
    // Window calculation (RFC 793) & Overcommit Protection
    // ─────────────────────────────────────────────────────────

    fun computeAdvertisedWindowLocked(): Int {
        val sessionSpace = (HARD_BUFFER_CAP - uploadQueueBytes).coerceAtLeast(0)
        val globalUsed = globalMemoryTracker?.get() ?: 0L
        val globalAvailable = (MAX_GLOBAL_UPLOAD_BYTES - globalUsed).coerceAtLeast(0L)
        val availableSpace = minOf(sessionSpace.toLong(), globalAvailable).coerceIn(0L, 65535L).toInt()

        // Only advance rightEdge if pending bytes are below HIGH_WATERMARK and space is available
        if (uploadQueueBytes < HIGH_WATERMARK && availableSpace > 0) {
            val proposedRightEdge = (theirSeq + availableSpace) and 0xFFFFFFFFL
            // RFC 793: Right edge must never move backward
            if (isSeqBefore(rightEdge, proposedRightEdge)) {
                rightEdge = proposedRightEdge
            }
        }

        // rcvWnd is the distance from theirSeq to rightEdge
        val dist = (rightEdge - theirSeq) and 0xFFFFFFFFL
        return if (dist > 0x7FFFFFFFL || dist <= 0L) {
            0
        } else {
            minOf(65535L, dist).toInt()
        }
    }

    private fun releaseMemoryLocked(bytes: Int) {
        if (bytes <= 0) return
        globalMemoryTracker?.updateAndGet { current ->
            if (current >= bytes) current - bytes else 0L
        }
    }

    fun updateInterestOpsLocked() {
        val key = selectionKey ?: return
        if (!key.isValid) return
        try {
            val currentOps = key.interestOps()
            val hasPendingWrites = uploadQueue.isNotEmpty() || (clientFinReceived && !proxyOutputShutdown)
            val newOps = if (hasPendingWrites) {
                SelectionKey.OP_READ or SelectionKey.OP_WRITE
            } else {
                SelectionKey.OP_READ
            }
            if (currentOps != newOps) {
                key.interestOps(newOps)
                selector?.wakeup()
            }
        } catch (_: Exception) {}
    }

    internal fun updateInterestOps() {
        synchronized(stateLock) {
            updateInterestOpsLocked()
        }
    }

    // ─────────────────────────────────────────────────────────
    // Called from TUN read loop
    // ─────────────────────────────────────────────────────────

    fun handleSyn(clientSeq: Long) {
        markActive()
        var shouldStartConnect = false
        synchronized(stateLock) {
            if (state == State.ESTABLISHED || state == State.HALF_CLOSED_LOCAL) {
                // Re-send SYN-ACK if client retransmitted SYN
                sendTcpPacketLocked(
                    PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
                    ByteArray(0),
                    includeMss = true,
                    overrideSeq = initialOurSeq,
                    windowSize = computeAdvertisedWindowLocked()
                )
                return
            }
            if (state == State.CLOSED) return
            theirSeq = (clientSeq + 1) and 0xFFFFFFFFL // expect next byte
            rightEdge = (theirSeq + minOf(65535, HARD_BUFFER_CAP)) and 0xFFFFFFFFL

            // Prevent duplicate connect attempts if another SYN arrives while still CONNECTING
            if (isConnectStarted.compareAndSet(false, true)) {
                shouldStartConnect = true
            }
        }

        if (shouldStartConnect) {
            val pool = handshakeExecutor ?: defaultHandshakeExecutor
            try {
                pool.execute {
                    connectAndHandshake()
                }
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "Handshake worker pool saturated. Rejecting SYN for ${key()}")
                sendRst()
                close()
            }
        }
    }

    fun handleData(tcp: PacketUtils.TcpHeader, payload: ByteArray) {
        if ((state != State.ESTABLISHED && state != State.HALF_CLOSED_LOCAL) || isDead) return
        markActive()
        if (payload.isEmpty()) return

        var accepted = false
        var currentWin: Int

        synchronized(stateLock) {
            if ((state != State.ESTABLISHED && state != State.HALF_CLOSED_LOCAL) || isDead) return

            val segSeq = tcp.seqNum
            val diff = (segSeq - theirSeq) and 0xFFFFFFFFL

            if (diff == 0L) {
                // In-order segment matching theirSeq
                val canAccept = (uploadQueueBytes + payload.size <= HARD_BUFFER_CAP) &&
                        ((globalMemoryTracker?.get() ?: 0L) + payload.size <= MAX_GLOBAL_UPLOAD_BYTES) &&
                        isSeqBeforeOrEqual((theirSeq + payload.size) and 0xFFFFFFFFL, rightEdge)

                if (canAccept) {
                    val buf = ByteBuffer.wrap(payload.copyOf())
                    uploadQueue.addLast(buf)
                    uploadQueueBytes += payload.size
                    globalMemoryTracker?.addAndGet(payload.size.toLong())

                    theirSeq = (theirSeq + payload.size) and 0xFFFFFFFFL
                    currentWin = computeAdvertisedWindowLocked()
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
                    updateInterestOpsLocked()
                    accepted = true
                } else {
                    // Buffer cap exceeded or beyond rightEdge: do not accept, do not advance theirSeq
                    currentWin = computeAdvertisedWindowLocked()
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
                }
            } else if (diff > 0x7FFFFFFFL) {
                // Retransmission: segSeq < theirSeq
                val alreadyReceived = ((theirSeq - segSeq) and 0xFFFFFFFFL).toInt()
                if (alreadyReceived >= payload.size) {
                    // Fully duplicate segment: re-ACK with current window
                    currentWin = computeAdvertisedWindowLocked()
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
                } else {
                    // Partially new segment
                    val newBytes = payload.copyOfRange(alreadyReceived, payload.size)
                    val canAccept = (uploadQueueBytes + newBytes.size <= HARD_BUFFER_CAP) &&
                            ((globalMemoryTracker?.get() ?: 0L) + newBytes.size <= MAX_GLOBAL_UPLOAD_BYTES) &&
                            isSeqBeforeOrEqual((theirSeq + newBytes.size) and 0xFFFFFFFFL, rightEdge)

                    if (canAccept) {
                        val buf = ByteBuffer.wrap(newBytes)
                        uploadQueue.addLast(buf)
                        uploadQueueBytes += newBytes.size
                        globalMemoryTracker?.addAndGet(newBytes.size.toLong())

                        theirSeq = (theirSeq + newBytes.size) and 0xFFFFFFFFL
                        currentWin = computeAdvertisedWindowLocked()
                        sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
                        updateInterestOpsLocked()
                        accepted = true
                    } else {
                        currentWin = computeAdvertisedWindowLocked()
                        sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
                    }
                }
            } else {
                // Out-of-order segment ahead of theirSeq: prompt retransmission with cumulative ACK
                currentWin = computeAdvertisedWindowLocked()
                sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = currentWin)
            }
        }

        if (accepted) {
            triggerUploadDrain()
        }
    }

    fun handleAck(tcp: PacketUtils.TcpHeader) {
        markActive()
    }

    fun handleFin(tcp: PacketUtils.TcpHeader, payload: ByteArray = ByteArray(0)) {
        synchronized(stateLock) {
            if (isDead) return
            markActive()

            val segSeq = tcp.seqNum
            val diff = (segSeq - theirSeq) and 0xFFFFFFFFL

            // If FIN arrived out of order (diff != 0L), prompt retransmission with cumulative ACK
            if (diff != 0L) {
                val win = computeAdvertisedWindowLocked()
                sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = win)
                return
            }

            // 1. Process payload if present in FIN segment
            if (payload.isNotEmpty()) {
                val canAccept = (uploadQueueBytes + payload.size <= HARD_BUFFER_CAP) &&
                        ((globalMemoryTracker?.get() ?: 0L) + payload.size <= MAX_GLOBAL_UPLOAD_BYTES) &&
                        isSeqBeforeOrEqual((theirSeq + payload.size) and 0xFFFFFFFFL, rightEdge)

                if (canAccept) {
                    val buf = ByteBuffer.wrap(payload.copyOf())
                    uploadQueue.addLast(buf)
                    uploadQueueBytes += payload.size
                    globalMemoryTracker?.addAndGet(payload.size.toLong())
                    theirSeq = (theirSeq + payload.size) and 0xFFFFFFFFL
                } else {
                    // Buffer cap or global limit exceeded: cannot accept payload or FIN
                    val win = computeAdvertisedWindowLocked()
                    sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = win)
                    return
                }
            }

            // 2. Consume FIN control bit (advances sequence space by 1)
            theirSeq = (theirSeq + 1) and 0xFFFFFFFFL
            clientFinReceived = true
            state = State.HALF_CLOSED_LOCAL
            val win = computeAdvertisedWindowLocked()
            sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = win)
            updateInterestOpsLocked()
        }
        triggerUploadDrain()
    }

    fun handleRst() {
        close()
    }

    // ─────────────────────────────────────────────────────────
    // Proxy connection & handshake
    // ─────────────────────────────────────────────────────────

    private fun connectAndHandshake() {
        val dstStr = PacketUtils.ipToString(dstIp)
        val channel: SocketChannel
        try {
            channel = SocketChannel.open()
            channel.configureBlocking(true)
        } catch (e: Exception) {
            sendRst()
            close()
            return
        }

        val sock = channel.socket()
        synchronized(stateLock) {
            if (isDead) {
                try { channel.close() } catch (_: Exception) {}
                return
            }
            proxyChannel = channel
            proxySocket = sock
        }

        try {
            AppLogger.i("Client", "Connecting to proxy $proxyIp:$proxyPort for $dstStr:$dstPort...")
            vpnService?.protect(sock)
            val underlyingNetwork = vpnService?.let { io.github.arttolst12.netion.NetworkUtils.getUnderlyingNetwork(it, proxyIp) }
            if (underlyingNetwork != null) {
                try {
                    underlyingNetwork.bindSocket(sock)
                    AppLogger.d("Client", "Socket bound to underlying network: $underlyingNetwork")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to bind socket to network $underlyingNetwork: ${e.message}")
                }
            }
            sock.connect(InetSocketAddress(proxyIp, proxyPort), 10_000)
            sock.soTimeout = 10_000
            sock.tcpNoDelay = true
            sock.keepAlive = true
            val out = sock.getOutputStream()
            val inp = sock.getInputStream()
            proxyOut = out
            proxyIn = inp
            AppLogger.s("Client", "Connected to $proxyIp:$proxyPort! Handshaking for $dstStr:$dstPort...")

            if (isSocks5) {
                // SOCKS5 Handshake
                // 1. Send greeting: VER 5, 1 auth method: 0 (No Auth)
                out.write(byteArrayOf(0x05, 0x01, 0x00))
                out.flush()

                val ver = inp.read()
                val method = inp.read()
                if (ver != 5 || method != 0) {
                    Log.w(TAG, "SOCKS5 greeting rejected: ver=$ver, method=$method")
                    AppLogger.e("Client", "SOCKS5 proxy $proxyIp:$proxyPort rejected auth method (ver=$ver, method=$method)")
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
                    AppLogger.e("Client", "SOCKS5 proxy rejected CONNECT to $dstStr:$dstPort (code=$respRep)")
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
                    AppLogger.e("Client", "HTTP proxy rejected CONNECT to $dstStr:$dstPort: $resp")
                    sendRst()
                    close()
                    return
                }
            }

            sock.soTimeout = 0
            synchronized(stateLock) {
                if (isDead) {
                    try { channel.close() } catch (_: Exception) {}
                    return
                }
                // Send SYN-ACK to client
                sendTcpPacketLocked(
                    PacketUtils.TCP_SYN or PacketUtils.TCP_ACK,
                    ByteArray(0),
                    includeMss = true,
                    overrideSeq = initialOurSeq,
                    windowSize = computeAdvertisedWindowLocked()
                )
                ourSeq = (initialOurSeq + 1) and 0xFFFFFFFFL  // SYN consumes 1 seq
                state = State.ESTABLISHED
                AppLogger.s("Client", "Tunnel open to $dstStr:$dstPort via $proxyIp:$proxyPort")
            }

            // Switch to non-blocking mode after handshake
            channel.configureBlocking(false)

            if (onEstablished != null) {
                onEstablished?.invoke(this, channel)
            } else {
                // Standalone mode: reader thread for unit tests or direct fallback
                val r = Thread({ proxyReaderLoop() }, "tcp-read-${key()}")
                r.isDaemon = true
                r.start()
            }

        } catch (e: Exception) {
            if (!isDead) {
                Log.e(TAG, "Proxy connect/handshake failed for [${key()}]: [${e.javaClass.simpleName}] ${e.message}", e)
                AppLogger.e("Client", "Failed to connect to proxy $proxyIp:$proxyPort for $dstStr:$dstPort: ${e.message}")
                sendRst()
            }
            close()
        }
    }

    /** Triggers upload draining; uses selector interest if attached, or coalesced worker */
    fun triggerUploadDrain() {
        if (selectionKey != null) {
            synchronized(stateLock) {
                updateInterestOpsLocked()
            }
            return
        }

        // Standalone coalesced drain
        if (isDraining.compareAndSet(false, true)) {
            try {
                sharedDrainPool.execute {
                    try {
                        drainUploadQueue()
                    } finally {
                        isDraining.set(false)
                        var hasMore = false
                        synchronized(stateLock) {
                            hasMore = uploadQueue.isNotEmpty() || (clientFinReceived && !proxyOutputShutdown)
                        }
                        if (hasMore) {
                            triggerUploadDrain()
                        }
                    }
                }
            } catch (_: Exception) {
                isDraining.set(false)
            }
        }
    }

    /**
     * Drains pending chunks from uploadQueue into proxy channel.
     * When client has sent FIN and all upload chunks are flushed, performs shutdownOutput().
     */
    fun drainUploadQueue() {
        synchronized(writeLock) {
            while (true) {
                var chunk: ByteBuffer? = null
                var shouldShutdown = false
                synchronized(stateLock) {
                    if (isDead) return
                    chunk = uploadQueue.peekFirst()
                    if (chunk == null) {
                        if (clientFinReceived && !proxyOutputShutdown) {
                            proxyOutputShutdown = true
                            shouldShutdown = true
                        }
                    }
                }

                if (chunk != null) {
                    val buf = chunk!!
                    val written: Int
                    try {
                        val channel = proxyChannel
                        if (channel != null && channel.isOpen) {
                            written = channel.write(buf)
                        } else {
                            val out = proxyOut ?: return
                            val rem = buf.remaining()
                            val bytes = ByteArray(rem)
                            buf.get(bytes)
                            out.write(bytes)
                            out.flush()
                            written = rem
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "Proxy write failed: ${e.message}")
                        sendRst()
                        close()
                        return
                    }

                    if (written > 0) {
                        var triggerWindowUpdate = false
                        var currentWin = 0
                        synchronized(stateLock) {
                            if (!isDead && state != State.CLOSED) {
                                uploadQueueBytes = (uploadQueueBytes - written).coerceAtLeast(0)
                                releaseMemoryLocked(written)
                                if (!buf.hasRemaining()) {
                                    uploadQueue.pollFirst()
                                }
                                updateInterestOpsLocked()
                                // Check if queue dropped below LOW_WATERMARK
                                if (uploadQueueBytes <= LOW_WATERMARK) {
                                    currentWin = computeAdvertisedWindowLocked()
                                    if (currentWin > 0) {
                                        triggerWindowUpdate = true
                                    }
                                }
                            }
                        }
                        if (triggerWindowUpdate) {
                            sendWindowUpdate(currentWin)
                        }
                    }

                    if (buf.hasRemaining()) {
                        // Socket channel write buffer full; OP_WRITE will re-trigger
                        break
                    }
                } else if (shouldShutdown) {
                    try {
                        proxyChannel?.shutdownOutput()
                        proxySocket?.shutdownOutput()
                    } catch (_: Exception) {}
                    synchronized(stateLock) {
                        updateInterestOpsLocked()
                    }
                    break
                } else {
                    synchronized(stateLock) {
                        updateInterestOpsLocked()
                    }
                    break
                }
            }
        }
    }

    private fun sendWindowUpdate(windowSize: Int) {
        synchronized(stateLock) {
            if (isDead) return
            sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0), windowSize = windowSize)
        }
    }

    /** Non-blocking selector callback when proxy channel is readable */
    fun onProxyReadable(buf: ByteBuffer = ByteBuffer.allocate(MSS)) {
        val channel = proxyChannel ?: return
        buf.clear()
        val n: Int
        try {
            n = channel.read(buf)
        } catch (e: Exception) {
            close()
            return
        }
        if (n == -1) {
            handleProxyEof()
            return
        }
        if (n > 0) {
            buf.flip()
            val chunk = ByteArray(n)
            buf.get(chunk)
            markActive()
            synchronized(stateLock) {
                if (isDead) return
                sendTcpPacketLocked(
                    PacketUtils.TCP_ACK or PacketUtils.TCP_PSH,
                    chunk,
                    windowSize = computeAdvertisedWindowLocked()
                )
                ourSeq = (ourSeq + n) and 0xFFFFFFFFL
            }
        }
    }

    /** Non-blocking selector callback when proxy channel is writable */
    fun onProxyWritable() {
        drainUploadQueue()
    }

    private fun handleProxyEof() {
        synchronized(stateLock) {
            if (!isDead) {
                // Remote proxy closed → send FIN to client
                sendTcpPacketLocked(
                    PacketUtils.TCP_FIN or PacketUtils.TCP_ACK,
                    ByteArray(0),
                    windowSize = computeAdvertisedWindowLocked()
                )
                ourSeq = (ourSeq + 1) and 0xFFFFFFFFL
            }
        }
        close()
    }

    /** Standalone loop for reading from upstream proxy when Selector is not attached */
    fun proxyReaderLoop() {
        val channel = proxyChannel
        val readBuf = ByteBuffer.allocate(MSS)
        try {
            while (!isDead) {
                readBuf.clear()
                val n: Int
                if (channel != null && channel.isOpen) {
                    n = channel.read(readBuf)
                } else {
                    val bytes = ByteArray(MSS)
                    n = proxyIn?.read(bytes) ?: break
                    if (n > 0) {
                        readBuf.put(bytes, 0, n)
                    }
                }
                if (n == -1) break
                if (n == 0) {
                    Thread.sleep(10)
                    continue
                }
                markActive()
                readBuf.flip()
                val chunk = ByteArray(n)
                readBuf.get(chunk)
                synchronized(stateLock) {
                    if (isDead) break
                    // Send data to client via TUN
                    sendTcpPacketLocked(
                        PacketUtils.TCP_ACK or PacketUtils.TCP_PSH,
                        chunk,
                        windowSize = computeAdvertisedWindowLocked()
                    )
                    ourSeq = (ourSeq + n) and 0xFFFFFFFFL
                }
            }
        } catch (_: Exception) {
            // Socket closed or error
        }
        handleProxyEof()
    }

    // ─────────────────────────────────────────────────────────
    // Packet sending
    // ─────────────────────────────────────────────────────────

    private fun sendTcpPacketLocked(
        flags: Int,
        payload: ByteArray,
        includeMss: Boolean = false,
        overrideSeq: Long? = null,
        windowSize: Int? = null
    ) {
        if (isDead && (flags and PacketUtils.TCP_RST) == 0) return
        val seq = overrideSeq ?: ourSeq
        val win = windowSize ?: computeAdvertisedWindowLocked()
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
                windowSize = win,
                includeMss = includeMss
            )
            tunWriter?.write(pkt)
        } catch (e: Exception) {
            Log.d(TAG, "TUN write failed: ${e.message}")
        }
    }

    internal fun sendRst() {
        synchronized(stateLock) {
            try {
                val pkt = PacketUtils.buildTcpPacket(
                    srcIp      = dstIp,
                    dstIp      = srcIp,
                    srcPort    = dstPort,
                    dstPort    = srcPort,
                    seqNum     = ourSeq,
                    ackNum     = theirSeq,
                    flags      = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
                    windowSize = 0
                )
                tunWriter?.write(pkt)
            } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────────────────
    // Cleanup
    // ─────────────────────────────────────────────────────────

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        var toRelease = 0
        synchronized(stateLock) {
            state = State.CLOSED
            toRelease = uploadQueueBytes
            uploadQueueBytes = 0
            uploadQueue.clear()
            try { selectionKey?.cancel() } catch (_: Exception) {}
            selectionKey = null
        }
        if (toRelease > 0) {
            releaseMemoryLocked(toRelease)
        }
        try { proxyChannel?.close() } catch (_: Exception) {}
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
            if (sb.length > 4096) break
        }
        return sb.toString().lines().firstOrNull() ?: ""
    }
}

/**
 * Thread-safe wrapper for writing packets to TUN FileOutputStream.
 */
class TunWriter(
    private val fd: java.io.OutputStream,
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
