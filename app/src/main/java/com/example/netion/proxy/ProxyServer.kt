package com.example.netion.proxy

import android.util.Log
import com.example.netion.AppLogger
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

    private var httpServerSocket: ServerSocket? = null
    private var socksServerSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool()
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    val activeSocketsCount: Int get() = activeSockets.size

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
                AppLogger.s("Server", "HTTP слушатель запущен на порту $httpPort")

                if (httpPortReallocated || socksPortReallocated) {
                    onPortReallocated?.invoke(httpPort, socks5Port)
                }

                while (running.get()) {
                    try {
                        val client = httpServerSocket?.accept() ?: break
                        AppLogger.i("Server", "Входящее HTTP соединение от ${client.remoteSocketAddress}")
                        pool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "HTTP accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind HTTP port $httpPort: ${e.message}")
                AppLogger.e("Server", "Ошибка привязки HTTP порта $httpPort: ${e.message}")
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
                AppLogger.s("Server", "SOCKS5 слушатель запущен на порту $socks5Port")

                if (httpPortReallocated || socksPortReallocated) {
                    onPortReallocated?.invoke(httpPort, socks5Port)
                }

                while (running.get()) {
                    try {
                        val client = socksServerSocket?.accept() ?: break
                        AppLogger.i("Server", "Входящее SOCKS5 соединение от ${client.remoteSocketAddress}")
                        pool.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "SOCKS5 accept error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to bind SOCKS5 port $socks5Port: ${e.message}")
                AppLogger.e("Server", "Ошибка привязки SOCKS5 порта $socks5Port: ${e.message}")
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

        pool.shutdownNow()
        Log.i(TAG, "Proxy stopped")
    }

    val isRunning: Boolean get() = running.get()

    // ──────────────────────────────────────────────────────────────
    // Client handler & protocol detection
    // ──────────────────────────────────────────────────────────────

    private fun handleClient(client: Socket) {
        if (!running.get() || activeSockets.size >= MAX_CONCURRENT_CONNECTIONS) {
            try { client.close() } catch (_: Exception) {}
            return
        }
        activeSockets.add(client)
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            client.keepAlive = true
            val input  = client.getInputStream()
            val output = client.getOutputStream()

            // Peek first byte to distinguish SOCKS5 (0x05) from HTTP (ASCII letters like 'C', 'G', 'P')
            val firstByte = input.read()
            if (firstByte == -1) {
                closeQuietly(client)
                return
            }

            if (firstByte == 5) {
                // SOCKS5 protocol
                handleSocks5(client, input, output)
            } else {
                // HTTP / CONNECT protocol
                handleHttpOrConnect(firstByte, client, input, output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            closeQuietly(client)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // SOCKS5 Handler (RFC 1928)
    // ──────────────────────────────────────────────────────────────

    private fun handleSocks5(client: Socket, input: InputStream, output: OutputStream) {
        try {
            // 1. Authentication Negotiation
            // Version 0x05 was already read. Read number of methods.
            val nmethods = input.read()
            if (nmethods <= 0) {
                closeQuietly(client)
                return
            }
            val methods = ByteArray(nmethods)
            readFully(input, methods)

            // Reply: Version 5, Method 0x00 (NO AUTHENTICATION REQUIRED)
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // 2. SOCKS5 Request
            val ver = input.read()
            if (ver != 5) {
                closeQuietly(client)
                return
            }
            val cmd = input.read()
            input.read() // RSV (reserved, 0x00)
            val atyp = input.read()

            // Only CMD 0x01 (CONNECT) is supported for TCP proxying
            if (cmd != 1) {
                // 0x07 = Command not supported
                try {
                    output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {}
                closeQuietly(client)
                return
            }

            val targetHost: String
            when (atyp) {
                1 -> { // IPv4 (4 bytes)
                    val ip = ByteArray(4)
                    readFully(input, ip)
                    targetHost = "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
                                 "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
                }
                3 -> { // Domain name (1 byte length + ASCII bytes)
                    val len = input.read()
                    if (len <= 0) {
                        closeQuietly(client)
                        return
                    }
                    val dom = ByteArray(len)
                    readFully(input, dom)
                    targetHost = String(dom, Charsets.UTF_8)
                }
                4 -> { // IPv6 (16 bytes)
                    val ip6 = ByteArray(16)
                    readFully(input, ip6)
                    targetHost = InetAddress.getByAddress(ip6).hostAddress ?: run {
                        closeQuietly(client)
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
                    return
                }
            }

            val p1 = input.read()
            val p2 = input.read()
            if (p1 < 0 || p2 < 0) {
                closeQuietly(client)
                return
            }
            val targetPort = ((p1 and 0xFF) shl 8) or (p2 and 0xFF)

            // 3. Connect to destination
            var remote: Socket? = null
            try {
                Log.i(TAG, "SOCKS5 connecting upstream to $targetHost:$targetPort...")
                AppLogger.i("Server", "SOCKS5 запрос к $targetHost:$targetPort (от ${client.remoteSocketAddress})")
                remote = Socket()
                remote.tcpNoDelay = true
                remote.connect(InetSocketAddress(targetHost, targetPort), CONNECT_TIMEOUT_MS)
                Log.i(TAG, "SOCKS5 upstream connected successfully to $targetHost:$targetPort")
                AppLogger.s("Server", "Успешное соединение с $targetHost:$targetPort через внешний VPN")

                // 4. Send success response: VER=5, REP=0, RSV=0, ATYP=1, BND.ADDR=0.0.0.0, BND.PORT=0
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                output.flush()

                // 5. Bidirectional tunnel
                tunnelAndClose(client, remote)
            } catch (e: Exception) {
                Log.e(TAG, "SOCKS5 connect failed for $targetHost:$targetPort: [${e.javaClass.simpleName}] ${e.message}", e)
                AppLogger.e("Server", "Ошибка подключения к $targetHost:$targetPort: ${e.message}")
                try {
                    // 0x05 = Connection refused
                    output.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
                    output.flush()
                } catch (_: Exception) {}
                closeQuietly(remote)
                closeQuietly(client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SOCKS5 handshake error: [${e.javaClass.simpleName}] ${e.message}", e)
            AppLogger.e("Server", "SOCKS5 ошибка handshake: ${e.message}")
            closeQuietly(client)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // HTTP / CONNECT Handler
    // ──────────────────────────────────────────────────────────────

    private fun handleHttpOrConnect(firstByte: Int, client: Socket, input: InputStream, output: OutputStream) {
        val restOfLine = readLine(input) ?: run {
            closeQuietly(client)
            return
        }
        val firstLine = "${firstByte.toChar()}$restOfLine"
        val parts = firstLine.trim().split(" ")
        if (parts.size < 3) {
            closeQuietly(client)
            return
        }

        val method = parts[0].uppercase()
        if (method == "CONNECT") {
            handleConnect(parts[1], client, input, output)
        } else {
            handleHttp(firstLine, method, client, input, output)
        }
    }

    /** HTTPS tunnel via CONNECT */
    private fun handleConnect(hostPort: String, client: Socket, input: InputStream, output: OutputStream) {
        val (host, port) = parseHostPort(hostPort, 443)

        // Consume remaining headers
        drainHeaders(input)

        var remote: Socket? = null
        try {
            Log.i(TAG, "HTTP CONNECT connecting upstream to $host:$port...")
            AppLogger.i("Server", "HTTP CONNECT запрос к $host:$port (от ${client.remoteSocketAddress})")
            remote = Socket()
            remote.tcpNoDelay = true
            remote.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            Log.i(TAG, "HTTP CONNECT upstream connected successfully to $host:$port")
            AppLogger.s("Server", "Успешное соединение с $host:$port через внешний VPN")

            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            tunnelAndClose(client, remote)
        } catch (e: Exception) {
            Log.e(TAG, "CONNECT failed for $host:$port: [${e.javaClass.simpleName}] ${e.message}", e)
            AppLogger.e("Server", "HTTP CONNECT ошибка для $host:$port: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            closeQuietly(remote)
            closeQuietly(client)
        }
    }

    /** Plain HTTP forwarding */
    private fun handleHttp(firstLine: String, method: String, client: Socket, input: InputStream, output: OutputStream) {
        val headers = StringBuilder(firstLine).append("\r\n")
        var host = ""
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            headers.append(line).append("\r\n")
            if (line.lowercase().startsWith("host:")) {
                host = line.substringAfter(":").trim()
            }
        }
        headers.append("\r\n")

        if (host.isEmpty()) {
            closeQuietly(client)
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

            tunnelAndClose(client, remote)
        } catch (e: Exception) {
            Log.w(TAG, "HTTP forward failed for $remoteHost:$remotePort: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            closeQuietly(remote)
            closeQuietly(client)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Utilities
    // ──────────────────────────────────────────────────────────────

    private fun tunnelAndClose(client: Socket, remote: Socket) {
        activeSockets.add(client)
        activeSockets.add(remote)

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

    private fun readFully(input: InputStream, b: ByteArray) {
        var total = 0
        while (total < b.size) {
            val count = input.read(b, total, b.size - total)
            if (count < 0) throw java.io.EOFException("Premature EOF")
            total += count
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
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

    private fun drainHeaders(input: InputStream) {
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
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
