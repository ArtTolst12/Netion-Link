package com.example.netion.proxy

import android.util.Log
import com.example.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Multi-protocol proxy server supporting HTTP CONNECT, plain HTTP, and SOCKS5 (RFC 1928).
 * Listens on [httpPort] (default 8080) and [socks5Port] (default 1080).
 * Both ports auto-detect protocol: if the first byte is 0x05, SOCKS5 is used;
 * otherwise, HTTP / HTTPS CONNECT is used.
 */
class ProxyServer(
    val requestedHttpPort: Int = 8080,
    val requestedSocks5Port: Int = 1080
) {

    companion object {
        private const val TAG = "ProxyServer"
        private const val BUFFER = 16384
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 120_000 // 2 minutes idle read timeout
        private const val MAX_LINE_LENGTH = 8192 // Limit HTTP line length to protect against OOM
        private const val MAX_HEADERS_COUNT = 100 // Limit number of HTTP header lines to protect against slowloris
        private const val HANDSHAKE_DEADLINE_MS = 10_000L // Overall deadline for HTTP headers / SOCKS5 handshake
        private const val MAX_CONCURRENT_CONNECTIONS = 256
    }

    var httpPort: Int = requestedHttpPort
        private set
    var socks5Port: Int = requestedSocks5Port
        private set
    var httpPortReallocated: Boolean = false
        private set
    var socksPortReallocated: Boolean = false
        private set

    var onPortReallocated: ((httpPort: Int, socksPort: Int) -> Unit)? = null
    var clientFilter: ((InetAddress) -> Boolean)? = null
    var handshakeDeadlineMs: Long = HANDSHAKE_DEADLINE_MS
    var maxConcurrentConnections: Int = MAX_CONCURRENT_CONNECTIONS

    private var httpServerSocket: ServerSocket? = null
    private var socksServerSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)

    // Bounded thread pool for accept workers and tunnel directions
    private val pool: ExecutorService = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_CONNECTIONS * 2 + 8,
        60L, TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        ThreadPoolExecutor.AbortPolicy()
    )

    // Atomic connection slot reservation
    private val activeConnectionSlots = AtomicInteger(0)
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    val activeSocketsCount: Int get() = activeSockets.size
    val activeClientConnectionsCount: Int get() = activeConnectionSlots.get()

    /**
     * Single-use lease guaranteeing atomic acquisition and strictly once-only release
     * of connection slots across all phases (handshake, errors, and bidirectional data piping).
     */
    private class ConnectionSlotLease(private val counter: AtomicInteger) {
        private val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                counter.updateAndGet { if (it > 0) it - 1 else 0 }
            }
        }
    }

    val totalBytesTx = java.util.concurrent.atomic.AtomicLong(0L)
    val totalBytesRx = java.util.concurrent.atomic.AtomicLong(0L)

    fun start() {
        if (running.getAndSet(true)) return

        // 1. HTTP ServerSocket (independent listener)
        pool.execute {
            try {
                var targetHttpPort = requestedHttpPort
                if (!com.example.netion.NetworkUtils.isPortAvailable(targetHttpPort)) {
                    targetHttpPort = com.example.netion.NetworkUtils.findAvailablePort(
                        requestedHttpPort,
                        setOf(requestedSocks5Port)
                    )
                    httpPortReallocated = true
                    Log.w(TAG, "HTTP port $requestedHttpPort was busy. Auto-allocated $targetHttpPort")
                }
                httpPort = targetHttpPort

                httpServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(httpPort))
                }
                Log.i(TAG, "Proxy HTTP listener started on port $httpPort")
                AppLogger.s("Server", "HTTP listener started on port $httpPort")

                if (httpPortReallocated || socksPortReallocated) {
                    onPortReallocated?.invoke(httpPort, socks5Port)
                }

                while (running.get()) {
                    val client: Socket
                    try {
                        client = httpServerSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "HTTP accept error: ${e.message}")
                        continue
                    }

                    if (!running.get()) {
                        try { client.close() } catch (_: Exception) {}
                        break
                    }

                    if (activeConnectionSlots.incrementAndGet() > maxConcurrentConnections) {
                        activeConnectionSlots.decrementAndGet()
                        Log.w(TAG, "Connection limit ($maxConcurrentConnections) reached. Rejecting HTTP client from ${client.remoteSocketAddress}")
                        AppLogger.w("Server", "Connection limit ($maxConcurrentConnections) reached. Rejecting HTTP client.")
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }

                    val lease = ConnectionSlotLease(activeConnectionSlots)
                    try {
                        pool.execute {
                            handleClient(client, lease)
                        }
                    } catch (e: RejectedExecutionException) {
                        lease.release()
                        Log.w(TAG, "Worker pool saturated. Rejecting HTTP client from ${client.remoteSocketAddress}")
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind HTTP port $httpPort: ${e.message}")
                AppLogger.e("Server", "Failed to bind HTTP port $httpPort: ${e.message}")
            }
        }

        // 2. SOCKS5 ServerSocket (independent parallel listener)
        pool.execute {
            try {
                var targetSocksPort = requestedSocks5Port
                if (targetSocksPort == httpPort || !com.example.netion.NetworkUtils.isPortAvailable(targetSocksPort)) {
                    targetSocksPort = com.example.netion.NetworkUtils.findAvailablePort(
                        requestedSocks5Port,
                        setOf(httpPort)
                    )
                    socksPortReallocated = true
                    Log.w(TAG, "SOCKS5 port $requestedSocks5Port was busy. Auto-allocated $targetSocksPort")
                }
                socks5Port = targetSocksPort

                socksServerSocket = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(socks5Port))
                }
                Log.i(TAG, "Proxy SOCKS5 listener started on port $socks5Port")
                AppLogger.s("Server", "SOCKS5 listener started on port $socks5Port")

                if (httpPortReallocated || socksPortReallocated) {
                    onPortReallocated?.invoke(httpPort, socks5Port)
                }

                while (running.get()) {
                    val client: Socket
                    try {
                        client = socksServerSocket?.accept() ?: break
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "SOCKS5 accept error: ${e.message}")
                        continue
                    }

                    if (!running.get()) {
                        try { client.close() } catch (_: Exception) {}
                        break
                    }

                    if (activeConnectionSlots.incrementAndGet() > maxConcurrentConnections) {
                        activeConnectionSlots.decrementAndGet()
                        Log.w(TAG, "Connection limit ($maxConcurrentConnections) reached. Rejecting SOCKS5 client from ${client.remoteSocketAddress}")
                        AppLogger.w("Server", "Connection limit ($maxConcurrentConnections) reached. Rejecting SOCKS5 client.")
                        try { client.close() } catch (_: Exception) {}
                        continue
                    }

                    val lease = ConnectionSlotLease(activeConnectionSlots)
                    try {
                        pool.execute {
                            handleClient(client, lease)
                        }
                    } catch (e: RejectedExecutionException) {
                        lease.release()
                        Log.w(TAG, "Worker pool saturated. Rejecting SOCKS5 client from ${client.remoteSocketAddress}")
                        try { client.close() } catch (_: Exception) {}
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind SOCKS5 port $socks5Port: ${e.message}")
                AppLogger.e("Server", "Failed to bind SOCKS5 port $socks5Port: ${e.message}")
            }
        }
    }

    fun stop() {
        running.set(false)
        try { httpServerSocket?.close() } catch (_: Exception) {}
        try { socksServerSocket?.close() } catch (_: Exception) {}
        httpServerSocket = null
        socksServerSocket = null

        // Immediately close all active client & remote sockets
        for (s in activeSockets) {
            try { s.close() } catch (_: Exception) {}
        }
        activeSockets.clear()
        activeConnectionSlots.set(0)

        pool.shutdownNow()
        Log.i(TAG, "Proxy stopped")
    }

    val isRunning: Boolean get() = running.get()

    // ──────────────────────────────────────────────────────────────
    // Client handler & protocol detection
    // ──────────────────────────────────────────────────────────────

    private fun handleClient(client: Socket, lease: ConnectionSlotLease) {
        if (!running.get()) {
            try { client.close() } catch (_: Exception) {}
            lease.release()
            return
        }

        val remoteAddr = client.inetAddress
        if (remoteAddr != null && clientFilter?.invoke(remoteAddr) == false) {
            Log.w(TAG, "Rejected client from unauthorized network address: $remoteAddr")
            AppLogger.w("Server", "Rejected unauthorized client from $remoteAddr")
            try { client.close() } catch (_: Exception) {}
            lease.release()
            return
        }

        activeSockets.add(client)
        if (!running.get()) {
            closeQuietly(client)
            lease.release()
            return
        }

        try {
            val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(handshakeDeadlineMs)
            updateSoTimeoutWithDeadline(client, deadlineNano)
            client.tcpNoDelay = true
            client.keepAlive = true
            val input  = client.getInputStream()
            val output = client.getOutputStream()

            // Peek first byte to distinguish SOCKS5 (0x05) from HTTP (ASCII letters like 'C', 'G', 'P')
            val firstByte = input.read()
            if (firstByte == -1) {
                closeQuietly(client)
                lease.release()
                return
            }

            if (firstByte == 5) {
                // SOCKS5 protocol
                handleSocks5(client, input, output, deadlineNano, lease)
            } else {
                // HTTP / CONNECT protocol
                handleHttpOrConnect(firstByte, client, input, output, deadlineNano, lease)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            closeQuietly(client)
            lease.release()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SOCKS5 Handler (RFC 1928)
    // ──────────────────────────────────────────────────────────────

    private fun handleSocks5(
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease
    ) {
        try {
            // 1. Authentication Negotiation
            // Version 0x05 was already read. Read number of methods.
            updateSoTimeoutWithDeadline(client, deadlineNano)
            val nmethods = input.read()
            if (nmethods <= 0) {
                closeQuietly(client)
                lease.release()
                return
            }
            val methods = ByteArray(nmethods)
            readFully(client, input, methods, deadlineNano)

            // RFC 1928: Verify if method 0x00 (NO AUTHENTICATION REQUIRED) was offered
            val supportsNoAuth = methods.any { it == 0x00.toByte() }
            if (!supportsNoAuth) {
                Log.w(TAG, "SOCKS5 client did not offer NO AUTHENTICATION REQUIRED (0x00)")
                try {
                    // 0xFF = NO ACCEPTABLE METHODS
                    output.write(byteArrayOf(0x05, 0xFF.toByte()))
                    output.flush()
                } catch (_: Exception) {}
                closeQuietly(client)
                lease.release()
                return
            }

            // Reply: Version 5, Method 0x00 (NO AUTHENTICATION REQUIRED)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. SOCKS5 Request
            updateSoTimeoutWithDeadline(client, deadlineNano)
            val ver = input.read()
            if (ver != 5) {
                closeQuietly(client)
                lease.release()
                return
            }
            updateSoTimeoutWithDeadline(client, deadlineNano)
            val cmd = input.read()
            updateSoTimeoutWithDeadline(client, deadlineNano)
            input.read() // RSV (reserved, 0x00)
            updateSoTimeoutWithDeadline(client, deadlineNano)
            val atyp = input.read()

            // Only CMD 0x01 (CONNECT) is supported for TCP proxying
            if (cmd != 1) {
                // 0x07 = Command not supported
                try {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {}
                closeQuietly(client)
                lease.release()
                return
            }

            val targetHost: String
            when (atyp) {
                1 -> { // IPv4 (4 bytes)
                    val ip = ByteArray(4)
                    readFully(client, input, ip, deadlineNano)
                    targetHost = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                                 "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
                }
                3 -> { // Domain name (1 byte length + ASCII bytes)
                    updateSoTimeoutWithDeadline(client, deadlineNano)
                    val len = input.read()
                    if (len <= 0) {
                        closeQuietly(client)
                        lease.release()
                        return
                    }
                    val dom = ByteArray(len)
                    readFully(client, input, dom, deadlineNano)
                    targetHost = String(dom, Charsets.UTF_8)
                }
                4 -> { // IPv6 (16 bytes)
                    val ip6 = ByteArray(16)
                    readFully(client, input, ip6, deadlineNano)
                    targetHost = InetAddress.getByAddress(ip6).hostAddress ?: run {
                        closeQuietly(client)
                        lease.release()
                        return
                    }
                }
                else -> {
                    // 0x08 = Address type not supported
                    try {
                        output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                        output.flush()
                    } catch (_: Exception) {}
                    closeQuietly(client)
                    lease.release()
                    return
                }
            }

            val pBuf = ByteArray(2)
            readFully(client, input, pBuf, deadlineNano)
            val p1 = pBuf[0].toInt() and 0xFF
            val p2 = pBuf[1].toInt() and 0xFF
            val targetPort = (p1 shl 8) or p2

            // 3. Connect to destination
            var remote: Socket? = null
            try {
                Log.i(TAG, "SOCKS5 connecting upstream to $targetHost:$targetPort...")
                AppLogger.i("Server", "SOCKS5 request to $targetHost:$targetPort (from ${client.remoteSocketAddress})")
                remote = Socket()
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
                Log.i(TAG, "SOCKS5 upstream connected successfully to $targetHost:$targetPort")
                AppLogger.s("Server", "Connected to $targetHost:$targetPort via upstream VPN")

                // 4. Send success response: VER=5, REP=0, RSV=0, ATYP=1, BND.ADDR=0.0.0.0, BND.PORT=0
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                // 5. Bidirectional tunnel
                tunnelAndClose(client, remote, lease)
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 connect failed for $targetHost:$targetPort: [${e.javaClass.simpleName}] ${e.message}", e)
                AppLogger.e("Server", "Connection error to $targetHost:$targetPort: ${e.message}")
                try {
                    // 0x05 = Connection refused
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {}
                closeQuietly(remote)
                closeQuietly(client)
                lease.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 handshake error: [${e.javaClass.simpleName}] ${e.message}", e)
            AppLogger.e("Server", "SOCKS5 handshake error: ${e.message}")
            closeQuietly(client)
            lease.release()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // HTTP / CONNECT Handler
    // ──────────────────────────────────────────────────────────────

    private fun handleHttpOrConnect(
        firstByte: Int,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease
    ) {
        val restOfLine = readLine(client, input, deadlineNano) ?: run {
            closeQuietly(client)
            lease.release()
            return
        }
        val firstLine = "${firstByte.toChar()}$restOfLine"
        val parts = firstLine.trim().split(" ")
        if (parts.size < 3) {
            closeQuietly(client)
            lease.release()
            return
        }

        val method = parts[0].uppercase()
        if (method == "CONNECT") {
            handleConnect(parts[1], client, input, output, deadlineNano, lease)
        } else {
            handleHttp(firstLine, method, client, input, output, deadlineNano, lease)
        }
    }

    /** HTTPS tunnel via CONNECT */
    private fun handleConnect(
        hostPort: String,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease
    ) {
        val (host, port) = parseHostPort(hostPort, 443)

        // Consume remaining headers within deadline and count limits
        if (!drainHeaders(client, input, deadlineNano)) {
            closeQuietly(client)
            lease.release()
            return
        }

        var remote: Socket? = null
        try {
            Log.i(TAG, "HTTP CONNECT connecting upstream to $host:$port...")
            AppLogger.i("Server", "HTTP CONNECT request to $host:$port (from ${client.remoteSocketAddress})")
            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            Log.i(TAG, "HTTP CONNECT upstream connected successfully to $host:$port")
            AppLogger.s("Server", "Connected to $host:$port via upstream VPN")

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            tunnelAndClose(client, remote, lease)
        } catch (e: Exception) {
            Log.e(TAG, "CONNECT failed for $host:$port: [${e.javaClass.simpleName}] ${e.message}", e)
            AppLogger.e("Server", "HTTP CONNECT error for $host:$port: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            closeQuietly(remote)
            closeQuietly(client)
            lease.release()
        }
    }

    /** Plain HTTP forwarding */
    private fun handleHttp(
        firstLine: String,
        method: String,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease
    ) {
        val headers = StringBuilder(firstLine).append("\r\n")
        var host = ""
        var lineCount = 1
        while (true) {
            val line = readLine(client, input, deadlineNano) ?: run {
                closeQuietly(client)
                lease.release()
                return
            }
            if (line.isEmpty()) break
            lineCount++
            if (lineCount > MAX_HEADERS_COUNT) {
                closeQuietly(client)
                lease.release()
                return
            }
            headers.append(line).append("\r\n")
            if (line.lowercase().startsWith("host:")) {
                host = line.substringAfter(":").trim()
            }
        }
        headers.append("\r\n")

        if (host.isEmpty()) {
            closeQuietly(client)
            lease.release()
            return
        }

        val (remoteHost, remotePort) = parseHostPort(host, 80)
        var remote: Socket? = null
        try {
            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(remoteHost, remotePort), CONNECT_TIMEOUT_MS)

            remote.getOutputStream().write(headers.toString().toByteArray())
            remote.getOutputStream().flush()

            tunnelAndClose(client, remote, lease)
        } catch (e: Exception) {
            Log.w(TAG, "HTTP forward failed for $remoteHost:$remotePort: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            closeQuietly(remote)
            closeQuietly(client)
            lease.release()
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────

    private fun tunnelAndClose(client: Socket, remote: Socket, lease: ConnectionSlotLease) {
        activeSockets.add(client)
        activeSockets.add(remote)

        // Guard against stop() race window immediately after socket registration
        if (!running.get()) {
            closeQuietly(client)
            closeQuietly(remote)
            lease.release()
            return
        }

        // Set idle timeout and enable TCP KeepAlive
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            client.keepAlive = true
            remote.soTimeout = IDLE_TIMEOUT_MS
            remote.keepAlive = true
        } catch (_: Exception) {}

        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val remoteIn = remote.getInputStream()
        val remoteOut = remote.getOutputStream()

        val closedBoth = AtomicBoolean(false)
        val activeDirections = AtomicInteger(2)

        fun closeBoth() {
            if (closedBoth.compareAndSet(false, true)) {
                closeQuietly(client)
                closeQuietly(remote)
                lease.release()
            }
        }

        fun runDirection(
            src: Socket,
            dst: Socket,
            srcIn: InputStream,
            dstOut: OutputStream,
            isTx: Boolean
        ) {
            try {
                pipe(srcIn, dstOut, isTx)
                // Normal EOF reached: half-close destination output if open
                try {
                    if (!dst.isOutputShutdown && !dst.isClosed) {
                        dst.shutdownOutput()
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                // Real error or abort: close both sides
                closeBoth()
            } finally {
                if (activeDirections.decrementAndGet() == 0) {
                    closeBoth()
                }
            }
        }

        try {
            // Forward remote -> client (TX from server point of view to client) via managed executor
            pool.execute {
                runDirection(remote, client, remoteIn, clientOut, isTx = true)
            }

            // Forward client -> remote (RX into server from client) on current thread (also in pool)
            runDirection(client, remote, clientIn, remoteOut, isTx = false)
        } catch (e: Exception) {
            closeBoth()
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        try { socket.close() } catch (_: Exception) {}
        activeSockets.remove(socket)
    }

    private fun pipe(src: InputStream, dst: OutputStream, isTx: Boolean = false) {
        val buf = ByteArray(BUFFER)
        var n: Int
        while (src.read(buf).also { n = it } != -1) {
            dst.write(buf, 0, n)
            dst.flush()
            if (isTx) {
                totalBytesTx.addAndGet(n.toLong())
            } else {
                totalBytesRx.addAndGet(n.toLong())
            }
        }
    }

    private fun updateSoTimeoutWithDeadline(socket: Socket, deadlineNano: Long) {
        val remainingNanos = deadlineNano - System.nanoTime()
        if (remainingNanos <= 0) {
            throw SocketTimeoutException("Handshake deadline exceeded")
        }
        val remainingMs = (remainingNanos / 1_000_000L).coerceIn(1L, 15_000L).toInt()
        socket.soTimeout = remainingMs
    }

    private fun readFully(socket: Socket, input: InputStream, b: ByteArray, deadlineNano: Long) {
        var total = 0
        while (total < b.size) {
            updateSoTimeoutWithDeadline(socket, deadlineNano)
            val count = input.read(b, total, b.size - total)
            if (count < 0) throw java.io.EOFException("Premature EOF")
            total += count
        }
    }

    private fun readLine(socket: Socket, input: InputStream, deadlineNano: Long): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            updateSoTimeoutWithDeadline(socket, deadlineNano)
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code && prev == '\r'.code) {
                return sb.dropLast(1).toString()
            }
            if (sb.length >= MAX_LINE_LENGTH) {
                return null // Guard against unbounded memory allocation
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    private fun drainHeaders(socket: Socket, input: InputStream, deadlineNano: Long): Boolean {
        var count = 0
        while (true) {
            val line = readLine(socket, input, deadlineNano) ?: return false
            if (line.isEmpty()) return true
            count++
            if (count > MAX_HEADERS_COUNT) return false
        }
    }

    private fun parseHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        val trimmed = hostPort.trim()
        return if (trimmed.startsWith('[')) {
            val closeBracket = trimmed.indexOf(']')
            if (closeBracket != -1) {
                val h = trimmed.substring(1, closeBracket)
                val p = if (closeBracket + 1 < trimmed.length && trimmed[closeBracket + 1] == ':') {
                    trimmed.substring(closeBracket + 2).toIntOrNull() ?: defaultPort
                } else {
                    defaultPort
                }
                Pair(h, p)
            } else {
                Pair(trimmed.trim('[', ']'), defaultPort)
            }
        } else if (trimmed.contains(':')) {
            val idx = trimmed.lastIndexOf(':')
            val h = trimmed.substring(0, idx)
            val p = trimmed.substring(idx + 1).toIntOrNull() ?: defaultPort
            Pair(h, p)
        } else {
            Pair(trimmed, defaultPort)
        }
    }
}
