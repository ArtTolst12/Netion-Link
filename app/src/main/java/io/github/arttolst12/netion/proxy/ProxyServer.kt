package io.github.arttolst12.netion.proxy

import android.util.Log
import io.github.arttolst12.netion.AppLogger
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
        private const val MAX_HEADERS_SIZE = 64 * 1024 // Finite aggregate request-header budget
        private const val HANDSHAKE_DEADLINE_MS = 10_000L // Overall deadline for HTTP headers / SOCKS5 handshake
        private const val MAX_CONCURRENT_CONNECTIONS = 256
    }

    @Volatile
    var httpPort: Int = requestedHttpPort
        private set
    @Volatile
    var socks5Port: Int = requestedSocks5Port
        private set
    @Volatile
    var httpPortReallocated: Boolean = false
        private set
    @Volatile
    var socksPortReallocated: Boolean = false
        private set

    var onPortReallocated: ((httpPort: Int, socksPort: Int) -> Unit)? = null
    var clientFilter: ((InetAddress) -> Boolean)? = null
    var handshakeDeadlineMs: Long = HANDSHAKE_DEADLINE_MS
    var maxConcurrentConnections: Int = MAX_CONCURRENT_CONNECTIONS

    private val lifecycleLock = Any()
    private val lifecycleEpoch = AtomicLong(0L)
    private val running = AtomicBoolean(false)

    @Volatile
    private var starting = false
    @Volatile
    private var httpServerSocket: ServerSocket? = null
    @Volatile
    private var socksServerSocket: ServerSocket? = null
    @Volatile
    private var workerPool: ThreadPoolExecutor? = null

    // Atomic connection slot reservation and socket/lease registries used by stop().
    private val activeConnectionSlots = AtomicInteger(0)
    private val activeSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val activeLeases = ConcurrentHashMap.newKeySet<ConnectionSlotLease>()
    val activeSocketsCount: Int get() = activeSockets.size
    val activeClientConnectionsCount: Int get() = activeConnectionSlots.get()

    /**
     * A single-use lease that owns one client connection slot. It can be released by
     * stop(), handshake failure, or tunnel completion without decrementing a newer run.
     */
    private class ConnectionSlotLease(private val onRelease: () -> Unit) {
        private val released = AtomicBoolean(false)

        fun release() {
            if (released.compareAndSet(false, true)) {
                onRelease()
            }
        }
    }

    val totalBytesTx = AtomicLong(0L)
    val totalBytesRx = AtomicLong(0L)

    /**
     * Synchronously binds both listeners. A listener is never advertised as running until
     * both binds and both accept workers have succeeded. If a requested port is occupied,
     * the fallback is selected by binding an ephemeral port directly (not by a racy probe).
     */
    fun start() {
        val epoch: Long
        val executor: ThreadPoolExecutor
        synchronized(lifecycleLock) {
            if (running.get() || starting) return

            starting = true
            epoch = lifecycleEpoch.incrementAndGet()
            executor = createWorkerPool()
            workerPool = executor
        }

        var boundHttp: ServerSocket? = null
        var boundSocks: ServerSocket? = null
        try {
            val httpBinding = bindListener(requestedHttpPort)
            val httpListener = httpBinding.socket
            boundHttp = httpListener
            val socksBinding = bindListener(requestedSocks5Port, avoidPort = httpListener.localPort)
            val socksListener = socksBinding.socket
            boundSocks = socksListener

            val published = synchronized(lifecycleLock) {
                if (!isStartingEpochLocked(epoch)) {
                    false
                } else {
                    httpServerSocket = httpListener
                    socksServerSocket = socksListener
                    httpPort = httpListener.localPort
                    socks5Port = socksListener.localPort
                    httpPortReallocated = httpBinding.reallocated
                    socksPortReallocated = socksBinding.reallocated
                    starting = false
                    running.set(true)
                    true
                }
            }

            if (!published) {
                closeServerSocket(boundHttp)
                closeServerSocket(boundSocks)
                return
            }

            try {
                executor.execute { acceptLoop(httpListener, epoch, "HTTP", executor) }
                executor.execute { acceptLoop(socksListener, epoch, "SOCKS5", executor) }
            } catch (e: RejectedExecutionException) {
                rollbackStartup(epoch, boundHttp, boundSocks, executor)
                throw IOException("Unable to start proxy accept workers", e)
            }

            Log.i(TAG, "Proxy listeners started on HTTP $httpPort and SOCKS5 $socks5Port")
            AppLogger.s("Server", "HTTP listener started on port $httpPort")
            AppLogger.s("Server", "SOCKS5 listener started on port $socks5Port")

            if ((httpBinding.reallocated || socksBinding.reallocated) && isGenerationActive(epoch)) {
                try {
                    onPortReallocated?.invoke(httpPort, socks5Port)
                } catch (e: Exception) {
                    Log.w(TAG, "Port reallocation callback failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            rollbackStartup(epoch, boundHttp, boundSocks, executor)
            Log.e(TAG, "Failed to start proxy listeners: ${e.message}", e)
            AppLogger.e("Server", "Failed to start proxy listeners: ${e.message}")
            throw e
        }
    }

    /** Invalidates any concurrent startup and releases every resource owned by this server. */
    fun stop() {
        val http: ServerSocket?
        val socks: ServerSocket?
        val executor: ThreadPoolExecutor?
        val leases: List<ConnectionSlotLease>
        val sockets: List<Socket>

        synchronized(lifecycleLock) {
            // A start() currently binding outside the lock observes this changed epoch and
            // closes its local sockets instead of publishing them.
            lifecycleEpoch.incrementAndGet()
            starting = false
            running.set(false)

            http = httpServerSocket
            socks = socksServerSocket
            httpServerSocket = null
            socksServerSocket = null

            executor = workerPool
            workerPool = null

            leases = activeLeases.toList()
            activeLeases.clear()
            activeConnectionSlots.set(0)
            sockets = activeSockets.toList()
            activeSockets.clear()
        }

        closeServerSocket(http)
        closeServerSocket(socks)
        sockets.forEach(::closeQuietly)
        // Leases are idempotent; their old epoch prevents them touching a later run.
        leases.forEach { it.release() }
        executor?.shutdownNow()
        Log.i(TAG, "Proxy stopped")
    }

    val isRunning: Boolean get() = running.get()

    // ──────────────────────────────────────────────────────────────
    // Listener lifecycle
    // ──────────────────────────────────────────────────────────────

    private data class ListenerBinding(val socket: ServerSocket, val reallocated: Boolean)

    private fun createWorkerPool(): ThreadPoolExecutor = ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_CONNECTIONS * 2 + 8,
        60L,
        TimeUnit.SECONDS,
        SynchronousQueue<Runnable>(),
        ThreadPoolExecutor.AbortPolicy()
    )

    private fun bindListener(requestedPort: Int, avoidPort: Int? = null): ListenerBinding {
        require(requestedPort in 0..65535) { "Invalid TCP port: $requestedPort" }
        val requested = try {
            bindOnPort(requestedPort)
        } catch (e: BindException) {
            null
        }
        if (requested != null && (avoidPort == null || requested.localPort != avoidPort)) {
            return ListenerBinding(requested, reallocated = false)
        }
        closeServerSocket(requested)

        val fallback = bindFallbackPort(avoidPort)
        Log.w(TAG, "Port $requestedPort was unavailable. Auto-allocated ${fallback.localPort}")
        return ListenerBinding(fallback, reallocated = true)
    }

    private fun bindFallbackPort(avoidPort: Int?): ServerSocket {
        // Binding is the reservation. If an ephemeral allocation happens to equal the other
        // listener, close it and retry rather than probing availability beforehand.
        repeat(16) {
            val socket = bindOnPort(0)
            if (socket.localPort != avoidPort) return socket
            closeServerSocket(socket)
        }
        throw BindException("Could not allocate a distinct fallback listener port")
    }

    private fun bindOnPort(port: Int): ServerSocket {
        val socket = ServerSocket()
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(port))
            return socket
        } catch (e: Exception) {
            closeServerSocket(socket)
            throw e
        }
    }

    private fun rollbackStartup(
        epoch: Long,
        localHttp: ServerSocket?,
        localSocks: ServerSocket?,
        localExecutor: ThreadPoolExecutor
    ) {
        var ownsGeneration = false
        val sockets: List<Socket>
        val leases: List<ConnectionSlotLease>

        synchronized(lifecycleLock) {
            if (lifecycleEpoch.get() == epoch) {
                ownsGeneration = true
                lifecycleEpoch.incrementAndGet()
                starting = false
                running.set(false)
                if (httpServerSocket === localHttp) httpServerSocket = null
                if (socksServerSocket === localSocks) socksServerSocket = null
                if (workerPool === localExecutor) workerPool = null
                sockets = activeSockets.toList()
                activeSockets.clear()
                leases = activeLeases.toList()
                activeLeases.clear()
                activeConnectionSlots.set(0)
            } else {
                sockets = emptyList()
                leases = emptyList()
            }
        }

        closeServerSocket(localHttp)
        closeServerSocket(localSocks)
        if (ownsGeneration) {
            sockets.forEach(::closeQuietly)
            leases.forEach { it.release() }
            localExecutor.shutdownNow()
        }
    }

    private fun acceptLoop(
        listener: ServerSocket,
        epoch: Long,
        listenerName: String,
        executor: ThreadPoolExecutor
    ) {
        while (isGenerationActive(epoch)) {
            val client = try {
                listener.accept()
            } catch (e: Exception) {
                if (isGenerationActive(epoch) && !listener.isClosed) {
                    Log.w(TAG, "$listenerName accept error: ${e.message}")
                    continue
                }
                break
            }

            if (!isGenerationActive(epoch)) {
                closeQuietly(client)
                break
            }

            if (!registerSocket(client, epoch)) {
                closeQuietly(client)
                break
            }

            val lease = reserveConnectionSlot(epoch)
            if (lease == null) {
                Log.w(TAG, "Connection limit ($maxConcurrentConnections) reached. Rejecting $listenerName client from ${client.remoteSocketAddress}")
                AppLogger.w("Server", "Client rejected; reason=connection-limit; listener=$listenerName; client=${client.remoteSocketAddress}; active_clients=${activeConnectionSlots.get()}; limit=$maxConcurrentConnections")
                closeQuietly(client)
                continue
            }

            try {
                executor.execute { handleClient(client, lease, epoch) }
            } catch (e: RejectedExecutionException) {
                Log.w(TAG, "Worker pool saturated. Rejecting $listenerName client from ${client.remoteSocketAddress}")
                AppLogger.w("Server", "Client rejected; reason=worker-limit; listener=$listenerName; client=${client.remoteSocketAddress}; active_clients=${activeConnectionSlots.get()}")
                closeQuietly(client)
                lease.release()
            }
        }
    }

    private fun reserveConnectionSlot(epoch: Long): ConnectionSlotLease? = synchronized(lifecycleLock) {
        if (!isGenerationActiveLocked(epoch)) return@synchronized null
        val limit = maxConcurrentConnections
        if (limit <= 0 || activeConnectionSlots.get() >= limit) return@synchronized null

        activeConnectionSlots.incrementAndGet()
        lateinit var lease: ConnectionSlotLease
        lease = ConnectionSlotLease { releaseConnectionSlot(lease, epoch) }
        activeLeases.add(lease)
        lease
    }

    private fun releaseConnectionSlot(lease: ConnectionSlotLease, epoch: Long) {
        synchronized(lifecycleLock) {
            activeLeases.remove(lease)
            if (isGenerationActiveLocked(epoch)) {
                activeConnectionSlots.updateAndGet { if (it > 0) it - 1 else 0 }
            }
        }
    }

    private fun registerSocket(socket: Socket, epoch: Long): Boolean {
        val registered = synchronized(lifecycleLock) {
            if (!isGenerationActiveLocked(epoch)) {
                false
            } else {
                activeSockets.add(socket)
                true
            }
        }
        if (!registered) closeQuietly(socket)
        return registered
    }

    private fun isStartingEpochLocked(epoch: Long): Boolean =
        starting && !running.get() && lifecycleEpoch.get() == epoch

    private fun isGenerationActiveLocked(epoch: Long): Boolean =
        running.get() && lifecycleEpoch.get() == epoch

    private fun isGenerationActive(epoch: Long): Boolean =
        running.get() && lifecycleEpoch.get() == epoch

    // ──────────────────────────────────────────────────────────────
    // Client handler & protocol detection
    // ──────────────────────────────────────────────────────────────

    private fun handleClient(client: Socket, lease: ConnectionSlotLease, epoch: Long) {
        if (!isGenerationActive(epoch)) {
            closeQuietly(client)
            lease.release()
            return
        }

        try {
            val remoteAddr = client.inetAddress
            if (remoteAddr != null && clientFilter?.invoke(remoteAddr) == false) {
                Log.w(TAG, "Rejected client from unauthorized network address: $remoteAddr")
                AppLogger.w("Server", "Rejected unauthorized client from $remoteAddr")
                closeQuietly(client)
                lease.release()
                return
            }

            // Client was already registered by the accept loop. This retains a hard socket
            // cap even if maxConcurrentConnections is reduced while clients are connected.
            val socketLimit = maxConcurrentConnections.coerceAtLeast(1) * 2
            if (activeSockets.size > socketLimit) {
                Log.w(TAG, "Active socket limit exceeded (${activeSockets.size}), rejecting client from ${client.remoteSocketAddress}")
                AppLogger.w("Server", "Socket limit exceeded, rejecting client")
                closeQuietly(client)
                lease.release()
                return
            }

            val deadlineNano = deadlineFromNow(handshakeDeadlineMs)
            updateSoTimeoutWithDeadline(client, deadlineNano)
            client.tcpNoDelay = true
            client.keepAlive = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // Peek first byte to distinguish SOCKS5 (0x05) from HTTP (ASCII letters like 'C', 'G', 'P').
            val firstByte = readByte(client, input, deadlineNano)
            if (firstByte == 5) {
                handleSocks5(client, input, output, deadlineNano, lease, epoch)
            } else {
                handleHttpOrConnect(firstByte, client, input, output, deadlineNano, lease, epoch)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            if (isGenerationActive(epoch)) {
                AppLogger.e("Server", "Client handshake failed; client=${client.remoteSocketAddress}", e)
            }
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
        lease: ConnectionSlotLease,
        epoch: Long
    ) {
        var remote: Socket? = null
        var shouldSendConnectionFailure = false
        try {
            // Version 0x05 was already read. Read number of methods and all advertised methods.
            val nmethods = readByte(client, input, deadlineNano)
            if (nmethods == 0) throw IOException("SOCKS5 greeting has no methods")
            val methods = ByteArray(nmethods)
            readFully(client, input, methods, deadlineNano)

            // RFC 1928: Verify if method 0x00 (NO AUTHENTICATION REQUIRED) was offered.
            if (!methods.any { it == 0x00.toByte() }) {
                Log.w(TAG, "SOCKS5 client did not offer NO AUTHENTICATION REQUIRED (0x00)")
                output.write(byteArrayOf(0x05, 0xFF.toByte()))
                output.flush()
                closeQuietly(client)
                lease.release()
                return
            }
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // Request: VER, CMD, RSV, ATYP. Every field, including RSV, is validated.
            if (readByte(client, input, deadlineNano) != 5) {
                throw IOException("Invalid SOCKS5 request version")
            }
            val cmd = readByte(client, input, deadlineNano)
            if (readByte(client, input, deadlineNano) != 0) {
                throw IOException("Invalid SOCKS5 reserved byte")
            }
            val atyp = readByte(client, input, deadlineNano)

            if (cmd != 1) {
                writeSocksReply(output, 0x07) // Command not supported
                closeQuietly(client)
                lease.release()
                return
            }

            val targetHost = when (atyp) {
                1 -> { // IPv4
                    val ip = ByteArray(4)
                    readFully(client, input, ip, deadlineNano)
                    "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                        "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
                }
                3 -> { // Domain name
                    val length = readByte(client, input, deadlineNano)
                    if (length == 0) throw IOException("Empty SOCKS5 domain")
                    val domain = ByteArray(length)
                    readFully(client, input, domain, deadlineNano)
                    String(domain, Charsets.UTF_8)
                }
                4 -> { // IPv6
                    val ip = ByteArray(16)
                    readFully(client, input, ip, deadlineNano)
                    InetAddress.getByAddress(ip).hostAddress
                        ?: throw IOException("Invalid SOCKS5 IPv6 address")
                }
                else -> {
                    writeSocksReply(output, 0x08) // Address type not supported
                    closeQuietly(client)
                    lease.release()
                    return
                }
            }

            val targetPort = (readByte(client, input, deadlineNano) shl 8) or
                readByte(client, input, deadlineNano)
            if (targetPort == 0) throw IOException("Invalid SOCKS5 target port")

            Log.i(TAG, "SOCKS5 connecting upstream to $targetHost:$targetPort...")
            AppLogger.i("Server", "SOCKS5 request to $targetHost:$targetPort (from ${client.remoteSocketAddress})")
            shouldSendConnectionFailure = true
            val connectedRemote = connectRemote(targetHost, targetPort, epoch)
            remote = connectedRemote
            shouldSendConnectionFailure = false
            Log.i(TAG, "SOCKS5 upstream connected successfully to $targetHost:$targetPort")
            AppLogger.s("Server", "Connected to $targetHost:$targetPort via upstream VPN")

            writeSocksReply(output, 0x00)
            tunnelAndClose(client, connectedRemote, lease, epoch)
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 handshake/connect error: [${e.javaClass.simpleName}] ${e.message}", e)
            if (isGenerationActive(epoch)) AppLogger.e("Server", "SOCKS5 request failed; client=${client.remoteSocketAddress}; phase=${if (shouldSendConnectionFailure) "upstream-connect" else "handshake-or-tunnel"}", e)
            if (shouldSendConnectionFailure && isGenerationActive(epoch)) {
                try {
                    writeSocksReply(output, 0x05) // Connection refused / failed
                } catch (_: Exception) {
                }
            }
            closeQuietly(remote)
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
        lease: ConnectionSlotLease,
        epoch: Long
    ) {
        val restOfLine = readLine(client, input, deadlineNano) ?: run {
            closeQuietly(client)
            lease.release()
            return
        }
        val firstLine = "${firstByte.toChar()}$restOfLine"
        val parts = firstLine.trim().split(Regex("\\s+"))
        if (parts.size < 3) {
            closeQuietly(client)
            lease.release()
            return
        }

        val initialHeaderBytes = firstLine.length + 2
        if (initialHeaderBytes > MAX_HEADERS_SIZE) {
            closeQuietly(client)
            lease.release()
            return
        }

        val method = parts[0].uppercase()
        if (method == "CONNECT") {
            handleConnect(parts[1], initialHeaderBytes, client, input, output, deadlineNano, lease, epoch)
        } else {
            handleHttp(firstLine, method, initialHeaderBytes, client, input, output, deadlineNano, lease, epoch)
        }
    }

    /** HTTPS tunnel via CONNECT. */
    private fun handleConnect(
        hostPort: String,
        initialHeaderBytes: Int,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease,
        epoch: Long
    ) {
        val (host, port) = parseHostPort(hostPort, 443)
        if (port !in 1..65535 || host.isEmpty()) {
            closeQuietly(client)
            lease.release()
            return
        }

        // A CONNECT request is valid only after the complete CRLFCRLF-terminated header block.
        if (!drainHeaders(client, input, deadlineNano, initialHeaderBytes)) {
            closeQuietly(client)
            lease.release()
            return
        }

        var remote: Socket? = null
        var responseSent = false
        try {
            Log.i(TAG, "HTTP CONNECT connecting upstream to $host:$port...")
            AppLogger.i("Server", "HTTP CONNECT request to $host:$port (from ${client.remoteSocketAddress})")
            val connectedRemote = connectRemote(host, port, epoch)
            remote = connectedRemote
            Log.i(TAG, "HTTP CONNECT upstream connected successfully to $host:$port")
            AppLogger.s("Server", "Connected to $host:$port via upstream VPN")

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
            responseSent = true
            tunnelAndClose(client, connectedRemote, lease, epoch)
        } catch (e: Exception) {
            Log.e(TAG, "CONNECT failed for $host:$port: [${e.javaClass.simpleName}] ${e.message}", e)
            if (isGenerationActive(epoch)) AppLogger.e("Server", "HTTP CONNECT failed; target=$host:$port; client=${client.remoteSocketAddress}; phase=${if (responseSent) "tunnel" else "upstream-connect-or-reply"}; response_sent=$responseSent", e)
            // Never append a 502 after a successful 200 tunnel response or after stop().
            if (!responseSent && isGenerationActive(epoch)) {
                try {
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                } catch (_: Exception) {
                }
            }
            closeQuietly(remote)
            closeQuietly(client)
            lease.release()
        }
    }

    /** Plain HTTP forwarding. */
    private fun handleHttp(
        firstLine: String,
        @Suppress("UNUSED_PARAMETER") method: String,
        initialHeaderBytes: Int,
        client: Socket,
        input: InputStream,
        output: OutputStream,
        deadlineNano: Long,
        lease: ConnectionSlotLease,
        epoch: Long
    ) {
        val headers = StringBuilder(firstLine).append("\r\n")
        var host = ""
        var lineCount = 1
        var headerBytes = initialHeaderBytes
        while (true) {
            val line = readLine(client, input, deadlineNano) ?: run {
                closeQuietly(client)
                lease.release()
                return
            }
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADERS_SIZE) {
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
            val separator = line.indexOf(':')
            val name = if (separator > 0) line.substring(0, separator) else ""
            val validName = name.isNotEmpty() && name.all {
                it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' ||
                    it in "!#%&'*+-.^_`|~" || it == '$'
            }
            if (!validName || line.any { (it.code < 32 && it != '\t') || it.code == 127 }) {
                // Reject folded/malformed fields rather than leaking a continuation after a
                // stripped proxy credential or forwarding an ambiguous HTTP header block.
                closeQuietly(client)
                lease.release()
                return
            }
            if (name.equals("Proxy-Authorization", ignoreCase = true) ||
                name.equals("Proxy-Connection", ignoreCase = true)) continue
            headers.append(line).append("\r\n")
            if (name.equals("Host", ignoreCase = true)) {
                host = line.substring(separator + 1).trim()
            }
        }
        headers.append("\r\n")

        if (host.isEmpty()) {
            closeQuietly(client)
            lease.release()
            return
        }

        val (remoteHost, remotePort) = parseHostPort(host, 80)
        if (remoteHost.isEmpty() || remotePort !in 1..65535) {
            closeQuietly(client)
            lease.release()
            return
        }

        var remote: Socket? = null
        var responseSent = false
        try {
            val connectedRemote = connectRemote(remoteHost, remotePort, epoch)
            remote = connectedRemote
            val remoteOutput = connectedRemote.getOutputStream()
            remoteOutput.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            remoteOutput.flush()
            responseSent = true

            tunnelAndClose(client, connectedRemote, lease, epoch)
        } catch (e: Exception) {
            Log.w(TAG, "HTTP forward failed for $remoteHost:$remotePort: ${e.message}")
            if (!responseSent && isGenerationActive(epoch)) {
                try {
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                    output.flush()
                } catch (_: Exception) {
                }
            }
            closeQuietly(remote)
            closeQuietly(client)
            lease.release()
        }
    }

    /** Creates, registers, and connects an upstream socket. Registration precedes connect(). */
    private fun connectRemote(host: String, port: Int, epoch: Long): Socket {
        val remote = Socket()
        if (!registerSocket(remote, epoch)) {
            throw IOException("Proxy stopped before upstream connection")
        }
        try {
            remote.tcpNoDelay = true
            if (!isGenerationActive(epoch)) throw IOException("Proxy stopped before upstream connection")
            remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            if (!isGenerationActive(epoch)) throw IOException("Proxy stopped during upstream connection")
            return remote
        } catch (e: Exception) {
            closeQuietly(remote)
            throw e
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Tunnel and utilities
    // ──────────────────────────────────────────────────────────────

    private fun tunnelAndClose(client: Socket, remote: Socket, lease: ConnectionSlotLease, epoch: Long) {
        if (!isGenerationActive(epoch)) {
            closeQuietly(client)
            closeQuietly(remote)
            lease.release()
            return
        }

        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            client.keepAlive = true
            remote.soTimeout = IDLE_TIMEOUT_MS
            remote.keepAlive = true
        } catch (_: Exception) {
        }

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
                // Normal EOF reached: half-close destination output if open.
                try {
                    if (!dst.isOutputShutdown && !dst.isClosed) {
                        dst.shutdownOutput()
                    }
                } catch (_: Exception) {
                }
            } catch (_: Exception) {
                // Real I/O error or abort: close both sides.
                closeBoth()
            } finally {
                if (activeDirections.decrementAndGet() == 0) {
                    closeBoth()
                }
            }
        }

        val executor = synchronized(lifecycleLock) {
            if (isGenerationActiveLocked(epoch)) workerPool else null
        }
        if (executor == null) {
            closeBoth()
            return
        }

        try {
            // Forward remote -> client via the same generation's managed executor.
            executor.execute {
                runDirection(remote, client, remoteIn, clientOut, isTx = true)
            }
            // Forward client -> remote on the current worker.
            runDirection(client, remote, clientIn, remoteOut, isTx = false)
        } catch (_: RejectedExecutionException) {
            closeBoth()
        } catch (_: Exception) {
            closeBoth()
        }
    }

    private fun writeSocksReply(output: OutputStream, reply: Int) {
        output.write(byteArrayOf(0x05, reply.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
    }

    private fun closeServerSocket(socket: ServerSocket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(socket: Socket?) {
        if (socket == null) return
        try {
            socket.close()
        } catch (_: Exception) {
        }
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

    private fun deadlineFromNow(timeoutMs: Long): Long {
        val duration = timeoutMs.coerceAtLeast(1L)
        val nanos = if (duration >= Long.MAX_VALUE / 1_000_000L) {
            Long.MAX_VALUE
        } else {
            TimeUnit.MILLISECONDS.toNanos(duration)
        }
        val now = System.nanoTime()
        return if (nanos >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + nanos
    }

    private fun updateSoTimeoutWithDeadline(socket: Socket, deadlineNano: Long) {
        val remainingNanos = deadlineNano - System.nanoTime()
        if (remainingNanos <= 0) {
            throw SocketTimeoutException("Handshake deadline exceeded")
        }
        val remainingMs = ((remainingNanos + 999_999L) / 1_000_000L).coerceIn(1L, 15_000L).toInt()
        socket.soTimeout = remainingMs
    }

    private fun readByte(socket: Socket, input: InputStream, deadlineNano: Long): Int {
        updateSoTimeoutWithDeadline(socket, deadlineNano)
        return input.read().also {
            if (it < 0) throw EOFException("Premature EOF")
        }
    }

    private fun readFully(socket: Socket, input: InputStream, buffer: ByteArray, deadlineNano: Long) {
        var total = 0
        while (total < buffer.size) {
            updateSoTimeoutWithDeadline(socket, deadlineNano)
            val count = input.read(buffer, total, buffer.size - total)
            if (count < 0) throw EOFException("Premature EOF")
            total += count
        }
    }

    /** Returns null unless the line is fully CRLF-terminated; partial requests are rejected. */
    private fun readLine(socket: Socket, input: InputStream, deadlineNano: Long): String? {
        val sb = StringBuilder()
        var previous = -1
        while (true) {
            val b = try {
                readByte(socket, input, deadlineNano)
            } catch (_: EOFException) {
                return null
            }
            if (b == '\n'.code && previous == '\r'.code) {
                return sb.dropLast(1).toString()
            }
            if (sb.length >= MAX_LINE_LENGTH) {
                return null
            }
            sb.append(b.toChar())
            previous = b
        }
    }

    private fun drainHeaders(
        socket: Socket,
        input: InputStream,
        deadlineNano: Long,
        initialHeaderBytes: Int
    ): Boolean {
        var count = 0
        var headerBytes = initialHeaderBytes
        while (true) {
            val line = readLine(socket, input, deadlineNano) ?: return false
            headerBytes += line.length + 2
            if (headerBytes > MAX_HEADERS_SIZE) return false
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
                val host = trimmed.substring(1, closeBracket)
                val port = if (closeBracket + 1 < trimmed.length && trimmed[closeBracket + 1] == ':') {
                    trimmed.substring(closeBracket + 2).toIntOrNull() ?: defaultPort
                } else {
                    defaultPort
                }
                Pair(host, port)
            } else {
                Pair(trimmed.trim('[', ']'), defaultPort)
            }
        } else if (trimmed.contains(':')) {
            val index = trimmed.lastIndexOf(':')
            val host = trimmed.substring(0, index)
            val port = trimmed.substring(index + 1).toIntOrNull() ?: defaultPort
            Pair(host, port)
        } else {
            Pair(trimmed, defaultPort)
        }
    }
}
