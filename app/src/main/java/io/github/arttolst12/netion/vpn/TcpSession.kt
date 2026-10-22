package io.github.arttolst12.netion.vpn

import android.net.VpnService
import android.util.Log
import io.github.arttolst12.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.ArrayDeque
import java.util.concurrent.ExecutorService
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
    private val globalDownloadMemoryTracker: AtomicLong? = null,
    private val uploadBudgetBytes: Long = MAX_GLOBAL_UPLOAD_BYTES,
    private val nanoTime: () -> Long = System::nanoTime,
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
        const val MAX_GLOBAL_DOWNLOAD_BYTES = 16L * 1024 * 1024
        private val workerFactory = java.util.concurrent.ThreadFactory { task ->
            Thread(task, "tcp-worker").apply { isDaemon = true }
        }

        // Bounded shared handshake pool (max 16 concurrent handshakes, AbortPolicy on overflow)
        val defaultHandshakeExecutor: ExecutorService = ThreadPoolExecutor(
            2,
            16,
            60L, TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            workerFactory,
            ThreadPoolExecutor.AbortPolicy()
        )

        // Bounded shared executor for fallback upload draining when running outside NIO selector
        private val sharedDrainPool = ThreadPoolExecutor(
            2,
            4,
            60L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
            workerFactory,
            ThreadPoolExecutor.AbortPolicy()
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
    private val drainOwner = AtomicBoolean(false)
    private val readOwner = AtomicBoolean(false)

    // Sequence numbers (unsigned-32 in Long)
    private var initialOurSeq: Long = (nanoTime() and 0xFFFFFFFFL) // ISN
    private var ourSeq: Long = initialOurSeq
    var theirSeq: Long = 0L; private set  // set from SYN (RCV.NXT)
    var rightEdge: Long = 0L; private set // Monotonic advertised window right edge (RCV.NXT + RCV.WND)

    // Upload buffering & backpressure
    private val uploadQueue = ArrayDeque<ByteBuffer>()
    var uploadQueueBytes = 0; private set
    private var clientFinReceived = false
    private var proxyOutputShutdown = false
    private var proxyReadEof = false
    private var ourFinSent = false
    private var ourFinAcknowledged = false
    private var ourFinSequence = 0L
    private var sndUna = initialOurSeq
    private var peerWindow = 65535
    private var peerRightEdge = (initialOurSeq + 1 + peerWindow) and 0xFFFFFFFFL
    private var windowUpdateSeq: Long? = null
    private var windowUpdateAck = 0L
    private var lastProbeNs = nanoTime()
    private val pendingDownload = ArrayDeque<ByteBuffer>()
    var downloadQueueBytes = 0; private set

    private data class SentSegment(
        var sequence: Long,
        var payload: ByteArray,
        val flags: Int,
        var sentAtNs: Long,
        var retries: Int = 0
    ) {
        val sequenceLength: Int get() = payload.size +
            (if (flags and PacketUtils.TCP_SYN != 0) 1 else 0) +
            (if (flags and PacketUtils.TCP_FIN != 0) 1 else 0)
    }
    private val unacknowledged = ArrayDeque<SentSegment>()

    // NIO Selector attachment
    @Volatile var selectionKey: SelectionKey? = null
    @Volatile var selector: Selector? = null

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
    @Volatile var proxyChannel: SocketChannel? = null; private set
    @Volatile private var proxySocket: Socket? = null
    @Volatile private var proxyOut: OutputStream? = null
    @Volatile private var proxyIn: InputStream? = null

    // Track whether connectAndHandshake was already initiated to prevent duplicate threads on retransmitted SYNs
    private val isConnectStarted = AtomicBoolean(false)

    enum class State { CONNECTING, ESTABLISHED, HALF_CLOSED_LOCAL, CLOSED }

    internal fun markEstablishedForTesting(initialSeq: Long = 1000L, ourInitialSeq: Long? = null) {
        synchronized(stateLock) {
            if (ourInitialSeq != null) initialOurSeq = ourInitialSeq and 0xFFFFFFFFL
            ourSeq = (initialOurSeq + 1) and 0xFFFFFFFFL
            sndUna = ourSeq
            peerRightEdge = (ourSeq + peerWindow) and 0xFFFFFFFFL
            theirSeq = (initialSeq + 1) and 0xFFFFFFFFL
            rightEdge = (theirSeq + minOf(65535, HARD_BUFFER_CAP)) and 0xFFFFFFFFL
            state = State.ESTABLISHED
        }
    }

    internal val nextSendSequence: Long get() = synchronized(stateLock) { ourSeq }
    internal val acknowledgedSequence: Long get() = synchronized(stateLock) { sndUna }

    fun setInitialPeerWindow(window: Int) {
        synchronized(stateLock) {
            if (state != State.CONNECTING) return
            peerWindow = window.coerceIn(0, 65535)
            peerRightEdge = (initialOurSeq + 1 + peerWindow) and 0xFFFFFFFFL
        }
    }

    // ─────────────────────────────────────────────────────────
    // Window calculation (RFC 793) & Overcommit Protection
    // ─────────────────────────────────────────────────────────

    fun computeAdvertisedWindowLocked(): Int {
        val sessionSpace = (HARD_BUFFER_CAP - uploadQueueBytes).coerceAtLeast(0)
        val globalUsed = globalMemoryTracker?.get() ?: 0L
        val globalAvailable = (uploadBudgetBytes - globalUsed).coerceAtLeast(0L)
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
        if (bytes > 0) globalMemoryTracker?.addAndGet(-bytes.toLong())
    }

    private fun reserveMemory(tracker: AtomicLong?, bytes: Int, cap: Long): Boolean {
        if (tracker == null) return true
        while (true) {
            val used = tracker.get()
            if (used < 0 || bytes.toLong() > cap - used) return false
            if (tracker.compareAndSet(used, used + bytes)) return true
        }
    }

    private fun sendCreditLocked(): Int {
        val distance = (peerRightEdge - ourSeq) and 0xFFFFFFFFL
        return if (distance > 0x7FFFFFFFL) 0 else minOf(distance, 65535L).toInt()
    }

    private fun downloadSpaceLocked(): Int = minOf(
        (HARD_BUFFER_CAP - downloadQueueBytes).coerceAtLeast(0).toLong(),
        (MAX_GLOBAL_DOWNLOAD_BYTES - (globalDownloadMemoryTracker?.get() ?: 0L)).coerceAtLeast(0L)
    ).toInt()

    fun updateInterestOpsLocked() {
        val key = selectionKey ?: return
        if (!key.isValid) return
        try {
            val currentOps = key.interestOps()
            var newOps = 0
            if (!proxyReadEof && sendCreditLocked() > 0 && downloadSpaceLocked() > 0) {
                newOps = newOps or SelectionKey.OP_READ
            }
            if (uploadQueue.isNotEmpty() || (clientFinReceived && !proxyOutputShutdown)) {
                newOps = newOps or SelectionKey.OP_WRITE
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
            if (isConnectStarted.compareAndSet(false, true)) {
                theirSeq = (clientSeq + 1) and 0xFFFFFFFFL
                rightEdge = (theirSeq + minOf(65535, HARD_BUFFER_CAP)) and 0xFFFFFFFFL
                windowUpdateSeq = clientSeq
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

    /** Accept only contiguous, previously unseen bytes. */
    private fun acceptPayloadLocked(sequence: Long, payload: ByteArray): Boolean {
        val difference = (sequence - theirSeq) and 0xFFFFFFFFL
        if (difference in 1L..0x7FFFFFFFL) return false
        val skip = if (difference == 0L) 0 else
            minOf((theirSeq - sequence) and 0xFFFFFFFFL, payload.size.toLong()).toInt()
        if (skip == payload.size) return true
        if (clientFinReceived) return false
        val size = payload.size - skip
        val end = (theirSeq + size) and 0xFFFFFFFFL
        if (uploadQueueBytes + size > HARD_BUFFER_CAP || !isSeqBeforeOrEqual(end, rightEdge)) return false
        if (!reserveMemory(globalMemoryTracker, size, uploadBudgetBytes)) return false
        try {
            uploadQueue.addLast(ByteBuffer.wrap(payload.copyOfRange(skip, payload.size)))
        } catch (failure: Throwable) {
            releaseMemoryLocked(size)
            throw failure
        }
        uploadQueueBytes += size
        theirSeq = end
        return true
    }

    fun handleData(tcp: PacketUtils.TcpHeader, payload: ByteArray) {
        synchronized(stateLock) {
            if (isDead || state == State.CONNECTING) return
            if (!processAckLocked(tcp) || isDead) return
            if (payload.isNotEmpty()) {
                if (acceptPayloadLocked(tcp.seqNum, payload)) markActive()
                sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
                updateInterestOpsLocked()
            }
            maybeFinishLocked()
        }
        triggerUploadDrain()
    }

    /** Cumulative ACK and window updates apply to ACK, DATA and FIN segments alike. */
    private fun processAckLocked(tcp: PacketUtils.TcpHeader): Boolean {
        if (tcp.flags and PacketUtils.TCP_ACK == 0 || state == State.CONNECTING || isDead) return false
        val ack = tcp.ackNum
        val acknowledgedBytes = (ack - sndUna) and 0xFFFFFFFFL
        val outstandingBytes = (ourSeq - sndUna) and 0xFFFFFFFFL
        if (acknowledgedBytes > outstandingBytes) {
            // A stale ACK does not acknowledge anything new, but its in-order payload can
            // still be valid. An ACK beyond SND.NXT must reject the entire DATA/FIN segment.
            if (isSeqBefore(ack, sndUna)) return true
            sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
            return false
        }
        val previousSeq = windowUpdateSeq
        if (previousSeq == null || isSeqBefore(previousSeq, tcp.seqNum) ||
            (previousSeq == tcp.seqNum && isSeqBeforeOrEqual(windowUpdateAck, ack))) {
            peerWindow = tcp.windowSize.coerceIn(0, 65535)
            peerRightEdge = (ack + peerWindow) and 0xFFFFFFFFL
            windowUpdateSeq = tcp.seqNum
            windowUpdateAck = ack
        }
        if (ack != sndUna) {
            markActive()
            while (unacknowledged.isNotEmpty()) {
                val first = unacknowledged.peekFirst() ?: break
                val count = (ack - first.sequence) and 0xFFFFFFFFL
                if (count == 0L || count > 0x7FFFFFFFL) break
                if (count >= first.sequenceLength) {
                    unacknowledged.removeFirst()
                    downloadQueueBytes -= first.payload.size
                    if (first.payload.isNotEmpty()) globalDownloadMemoryTracker?.addAndGet(-first.payload.size.toLong())
                } else {
                    val bytes = minOf(count.toInt(), first.payload.size)
                    first.payload = first.payload.copyOfRange(bytes, first.payload.size)
                    first.sequence = ack
                    downloadQueueBytes -= bytes
                    if (bytes > 0) globalDownloadMemoryTracker?.addAndGet(-bytes.toLong())
                    break
                }
            }
            sndUna = ack
            if (ourFinSent && isSeqBeforeOrEqual((ourFinSequence + 1) and 0xFFFFFFFFL, ack)) ourFinAcknowledged = true
            unacknowledged.peekFirst()?.let { it.sentAtNs = nanoTime(); it.retries = 0 }
        }
        flushDownstreamLocked()
        updateInterestOpsLocked()
        return !isDead
    }

    fun handleAck(tcp: PacketUtils.TcpHeader) {
        synchronized(stateLock) {
            processAckLocked(tcp)
            maybeFinishLocked()
        }
    }

    fun handleFin(tcp: PacketUtils.TcpHeader, payload: ByteArray = ByteArray(0)) {
        synchronized(stateLock) {
            if (isDead || state == State.CONNECTING) return
            if (!processAckLocked(tcp) || isDead) return
            val finSequence = (tcp.seqNum + payload.size) and 0xFFFFFFFFL
            val hadFin = clientFinReceived
            if (!hadFin && acceptPayloadLocked(tcp.seqNum, payload) && finSequence == theirSeq &&
                computeAdvertisedWindowLocked() > 0) {
                theirSeq = (theirSeq + 1) and 0xFFFFFFFFL
                clientFinReceived = true
                state = State.HALF_CLOSED_LOCAL
                markActive()
            }
            sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
            updateInterestOpsLocked()
            maybeFinishLocked()
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
        val startedNanos = System.nanoTime()
        var phase = "socket-open"
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
            Log.i(TAG, "Connecting to proxy $proxyIp:$proxyPort for $dstStr:$dstPort...")
            AppLogger.i("Client", "Connecting to proxy $proxyIp:$proxyPort for $dstStr:$dstPort...")

            phase = "protect-and-bind"
            if (vpnService != null) {
                if (!VpnSocketSetup.prepare(sock, protect = { vpnService.protect(it) }, bindToNetwork = {
                        val network = io.github.arttolst12.netion.NetworkUtils.getUnderlyingNetwork(vpnService, proxyIp)
                            ?: throw java.io.IOException("No underlying network for proxy")
                        network.bindSocket(it)
                    })) throw java.io.IOException("VPN socket protection failed")
            }
            sock.tcpNoDelay = true
            sock.keepAlive = true
            phase = "proxy-handshake"
            ProxyHandshake.connect(sock, proxyIp, proxyPort, dstIp, dstPort, isSocks5, onPhase = { phase = it })
            phase = "session-registration"
            proxyOut = sock.getOutputStream()
            proxyIn = sock.getInputStream()

            sock.soTimeout = 0
            synchronized(stateLock) {
                if (isDead) {
                    try { channel.close() } catch (_: Exception) {}
                    return
                }
                state = State.ESTABLISHED
                if (!sendTrackedLocked(PacketUtils.TCP_SYN or PacketUtils.TCP_ACK, ByteArray(0))) return
                AppLogger.s("Client", "Tunnel open; session=${key()}; proxy=$proxyIp:$proxyPort; protocol=${if (isSocks5) "SOCKS5" else "HTTP_CONNECT"}; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}")
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
                AppLogger.e("Client", "Proxy connection failed; session=${key()}; proxy=$proxyIp:$proxyPort; protocol=${if (isSocks5) "SOCKS5" else "HTTP_CONNECT"}; phase=$phase; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}", e)
                sendRst()
            }
            close()
        }
    }

    /** Triggers upload draining; uses selector interest if attached, or coalesced worker */
    fun triggerUploadDrain() {
        if (isDead) return
        if (selectionKey != null || onEstablished != null) {
            updateInterestOps()
            return
        }
        if (proxyChannel == null && proxyOut == null) return
        if (isDraining.compareAndSet(false, true)) {
            try {
                sharedDrainPool.execute {
                    try { drainUploadQueue() } finally {
                        isDraining.set(false)
                        val retry = synchronized(stateLock) {
                            !isDead && proxyChannel == null && proxyOut != null &&
                                (uploadQueue.isNotEmpty() || (clientFinReceived && !proxyOutputShutdown))
                        }
                        if (retry) triggerUploadDrain()
                    }
                }
            } catch (_: RejectedExecutionException) {
                isDraining.set(false)
                sendRst()
                close()
            }
        }
    }

    /** The token covers buffer selection, partial I/O and accounting commit. */
    fun drainUploadQueue() {
        if (!drainOwner.compareAndSet(false, true)) return
        try {
            while (!isDead) {
                var shutdown = false
                val chunk = synchronized(stateLock) {
                    if (isDead) return
                    val head = uploadQueue.peekFirst()
                    if (head == null && clientFinReceived && !proxyOutputShutdown) shutdown = true
                    head
                }
                if (chunk == null) {
                    if (shutdown) {
                        val channel = proxyChannel
                        val socket = proxySocket
                        if (channel == null && socket == null) return
                        try {
                            if (channel != null) channel.shutdownOutput() else socket?.shutdownOutput()
                        } catch (_: Exception) {
                            if (!isDead) { sendRst(); close() }
                            return
                        }
                        synchronized(stateLock) {
                            if (!isDead) {
                                proxyOutputShutdown = true
                                updateInterestOpsLocked()
                                maybeFinishLocked()
                            }
                        }
                    } else updateInterestOps()
                    return
                }
                val written: Int
                try {
                    val channel = proxyChannel
                    if (channel != null) written = channel.write(chunk) else {
                        val output = proxyOut ?: return
                        val size = chunk.remaining()
                        val bytes = ByteArray(size)
                        chunk.duplicate().get(bytes)
                        output.write(bytes)
                        output.flush()
                        chunk.position(chunk.position() + size)
                        written = size
                    }
                } catch (_: Exception) {
                    if (!isDead) sendRst()
                    close()
                    return
                }
                synchronized(stateLock) {
                    if (isDead) return // close() owns all remaining reservations.
                    if (written > 0) {
                        uploadQueueBytes -= written
                        releaseMemoryLocked(written)
                        if (!chunk.hasRemaining()) uploadQueue.removeFirst()
                        markActive()
                        if (uploadQueueBytes <= LOW_WATERMARK) sendTcpPacketLocked(PacketUtils.TCP_ACK, ByteArray(0))
                    }
                    updateInterestOpsLocked()
                }
                if (written == 0) return
            }
        } finally {
            drainOwner.set(false)
        }
    }

    private fun sendTrackedLocked(flags: Int, payload: ByteArray): Boolean {
        val segment = SentSegment(ourSeq, payload, flags, nanoTime())
        unacknowledged.addLast(segment)
        if (!sendTcpPacketLocked(flags, payload, flags and PacketUtils.TCP_SYN != 0, segment.sequence)) return false
        ourSeq = (ourSeq + segment.sequenceLength) and 0xFFFFFFFFL
        return true
    }

    private fun flushDownstreamLocked() {
        if (isDead) return
        while (pendingDownload.isNotEmpty()) {
            val credit = sendCreditLocked()
            if (credit <= 0) break
            val pending = pendingDownload.peekFirst() ?: break
            val size = minOf(MSS, pending.remaining(), credit)
            val bytes = ByteArray(size)
            pending.get(bytes)
            if (!pending.hasRemaining()) pendingDownload.removeFirst()
            if (!sendTrackedLocked(PacketUtils.TCP_ACK or PacketUtils.TCP_PSH, bytes)) return
        }
        if (proxyReadEof && !ourFinSent && pendingDownload.isEmpty() && sendCreditLocked() > 0) {
            ourFinSequence = ourSeq
            ourFinSent = true
            sendTrackedLocked(PacketUtils.TCP_FIN or PacketUtils.TCP_ACK, ByteArray(0))
        }
    }

    /** Never read more downstream data than the peer window and bounded budgets allow. */
    fun onProxyReadable(buf: ByteBuffer = ByteBuffer.allocate(MSS)) {
        if (!readOwner.compareAndSet(false, true)) return
        try {
            val channel = proxyChannel ?: return
            val capacity = synchronized(stateLock) {
                if (isDead || proxyReadEof) return
                val count = minOf(MSS, buf.capacity(), sendCreditLocked(), downloadSpaceLocked())
                if (count <= 0 || !reserveMemory(globalDownloadMemoryTracker, count, MAX_GLOBAL_DOWNLOAD_BYTES)) {
                    updateInterestOpsLocked()
                    return
                }
                downloadQueueBytes += count
                count
            }
            buf.clear()
            buf.limit(capacity)
            val n = try { channel.read(buf) } catch (_: Exception) {
                sendRst()
                close()
                return
            }
            synchronized(stateLock) {
                if (isDead) return
                val unused = capacity - maxOf(0, n)
                downloadQueueBytes -= unused
                if (unused > 0) globalDownloadMemoryTracker?.addAndGet(-unused.toLong())
                if (n > 0) {
                    buf.flip()
                    val bytes = ByteArray(n)
                    buf.get(bytes)
                    pendingDownload.addLast(ByteBuffer.wrap(bytes))
                    markActive()
                } else if (n == -1) proxyReadEof = true
                flushDownstreamLocked()
                updateInterestOpsLocked()
                maybeFinishLocked()
            }
        } finally { readOwner.set(false) }
    }

    fun onProxyWritable() { drainUploadQueue() }

    private fun maybeFinishLocked() {
        if (clientFinReceived && proxyOutputShutdown && ourFinAcknowledged && uploadQueueBytes == 0) close()
    }

    /** Driven by the runtime even on idle selector ticks, so a lost packet cannot stall forever. */
    fun onTimer(nowNanos: Long = nanoTime()) {
        synchronized(stateLock) {
            if (isDead || state == State.CONNECTING) return
            val oldest = unacknowledged.peekFirst()
            if (oldest != null) {
                val rto = minOf(8_000_000_000L, 500_000_000L shl oldest.retries.coerceAtMost(4))
                if (nowNanos - oldest.sentAtNs >= rto) {
                    if (oldest.retries >= 6) {
                        AppLogger.w("TCP", "Retransmission limit reached; session=${key()}; retries=${oldest.retries}; unacked_bytes=${(ourSeq - sndUna) and 0xFFFFFFFFL}; peer_window=$peerWindow")
                        sendRst()
                        close()
                        return
                    }
                    if (!sendTcpPacketLocked(oldest.flags, oldest.payload,
                            oldest.flags and PacketUtils.TCP_SYN != 0, oldest.sequence)) return
                    oldest.sentAtNs = nowNanos
                    oldest.retries++
                }
            } else if (peerWindow == 0 && nowNanos - lastProbeNs >= 1_000_000_000L) {
                sendTcpPacketLocked(PacketUtils.TCP_ACK, byteArrayOf(0), overrideSeq = (ourSeq - 1) and 0xFFFFFFFFL)
                lastProbeNs = nowNanos
            }
            flushDownstreamLocked()
            updateInterestOpsLocked()
            maybeFinishLocked()
        }
    }

    /** Standalone mode uses the same non-blocking read/drain state machine as the selector. */
    fun proxyReaderLoop() {
        val buffer = ByteBuffer.allocate(MSS)
        try {
            while (!isDead) {
                onProxyReadable(buffer)
                triggerUploadDrain()
                onTimer()
                Thread.sleep(10)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (_: Exception) {
            if (!isDead) sendRst()
        } finally { close() }
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
    ): Boolean {
        if (isDead && (flags and PacketUtils.TCP_RST) == 0) return false
        val seq = overrideSeq ?: ourSeq
        val win = windowSize ?: computeAdvertisedWindowLocked()
        return try {
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
            true
        } catch (e: Exception) {
            Log.d(TAG, "TUN write failed: ${e.message}")
            close()
            false
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
        // Close I/O before taking stateLock; teardown never needs the drain token.
        try { proxyChannel?.close() } catch (_: Exception) {}
        try { proxySocket?.close() } catch (_: Exception) {}
        synchronized(stateLock) {
            state = State.CLOSED
            releaseMemoryLocked(uploadQueueBytes)
            if (downloadQueueBytes > 0) globalDownloadMemoryTracker?.addAndGet(-downloadQueueBytes.toLong())
            uploadQueueBytes = 0
            downloadQueueBytes = 0
            uploadQueue.clear()
            pendingDownload.clear()
            unacknowledged.clear()
            try { selectionKey?.cancel() } catch (_: Exception) {}
            selectionKey = null
        }
        selector?.wakeup()
    }

    fun key() = "$srcPort→${PacketUtils.ipToString(dstIp)}:$dstPort"

}
