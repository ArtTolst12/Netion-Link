package com.example.netion.vpn

import android.net.VpnService
import android.util.Log
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
 * Local HTTP-to-SOCKS5 bridge.
 * Listens on 127.0.0.1:[localPort] for HTTP CONNECT tunnels from Android system/apps,
 * translates them into upstream SOCKS5 handshakes (RFC 1928), and protects the upstream
 * socket via [vpnService.protect] so it routes directly over Wi-Fi/Tethering.
 */
class SocksBridge(
    private val vpnService: VpnService,
    private val upstreamIp: String,
    private val upstreamPort: Int
) {

    companion object {
        private const val TAG = "SocksBridge"
        private const val BUFFER = 16384
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val IDLE_TIMEOUT_MS = 120_000
        private const val MAX_LINE_LENGTH = 8192
    }

    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool: ExecutorService = Executors.newCachedThreadPool()

    var localPort: Int = 0
        private set

    fun start() {
        if (running.getAndSet(true)) return

        val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).apply {
            reuseAddress = true
        }
        serverSocket = ss
        localPort = ss.localPort
        Log.i(TAG, "SocksBridge started on 127.0.0.1:$localPort -> upstream $upstreamIp:$upstreamPort")

        pool.execute {
            while (running.get()) {
                try {
                    val client = serverSocket?.accept() ?: break
                    pool.execute { handleLocalClient(client) }
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
                }
            }
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
        Log.i(TAG, "SocksBridge stopped")
    }

    private fun handleLocalClient(client: Socket) {
        try {
            client.soTimeout = 15_000
            client.tcpNoDelay = true
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val firstLine = readLine(input) ?: run {
                try { client.close() } catch (_: Exception) {}
                return
            }

            val parts = firstLine.trim().split(" ")
            if (parts.size < 3) {
                try { client.close() } catch (_: Exception) {}
                return
            }

            val method = parts[0].uppercase()
            if (method == "CONNECT") {
                handleConnect(parts[1], client, input, output)
            } else {
                handlePlainHttp(firstLine, client, input, output)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Client error: ${e.message}")
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handleConnect(hostPort: String, client: Socket, input: InputStream, output: OutputStream) {
        val (targetHost, targetPort) = parseHostPort(hostPort, 443)
        drainHeaders(input)

        var upstream: Socket? = null
        try {
            upstream = Socket().apply { tcpNoDelay = true }
            vpnService.protect(upstream)
            val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(vpnService, upstreamIp)
            if (underlyingNetwork != null) {
                try {
                    underlyingNetwork.bindSocket(upstream)
                } catch (_: Exception) {}
            }
            upstream.connect(InetSocketAddress(upstreamIp, upstreamPort), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = 0
            client.soTimeout = 0

            val uIn = upstream.getInputStream()
            val uOut = upstream.getOutputStream()

            // 1. SOCKS5 Greeting: VER=5, 1 Method, Method=0 (No Auth)
            uOut.write(byteArrayOf(0x05, 0x01, 0x00))
            uOut.flush()

            val gVer = uIn.read()
            val gMethod = uIn.read()
            if (gVer != 5 || gMethod != 0) {
                throw IllegalStateException("Upstream rejected SOCKS5 auth: ver=$gVer, method=$gMethod")
            }

            // 2. SOCKS5 CONNECT Request
            sendSocks5Connect(uOut, targetHost, targetPort)

            // 3. SOCKS5 Response
            val rVer = uIn.read()
            val rRep = uIn.read()
            uIn.read() // RSV
            val rAtyp = uIn.read()

            if (rVer != 5 || rRep != 0) {
                throw IllegalStateException("Upstream SOCKS5 connect failed: rep=$rRep")
            }

            // Drain bound address and port
            when (rAtyp) {
                1 -> { val b = ByteArray(4 + 2); readFully(uIn, b) }
                3 -> { val len = uIn.read(); val b = ByteArray(len + 2); readFully(uIn, b) }
                4 -> { val b = ByteArray(16 + 2); readFully(uIn, b) }
            }

            // 4. Respond to client that connection is established
            output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
            output.flush()

            tunnelAndClose(client, upstream)
        } catch (e: Exception) {
            Log.w(TAG, "CONNECT to $targetHost:$targetPort failed: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun handlePlainHttp(firstLine: String, client: Socket, input: InputStream, output: OutputStream) {
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
            try { client.close() } catch (_: Exception) {}
            return
        }

        val (targetHost, targetPort) = parseHostPort(host, 80)
        var upstream: Socket? = null
        try {
            upstream = Socket().apply { tcpNoDelay = true }
            vpnService.protect(upstream)
            val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(vpnService, upstreamIp)
            if (underlyingNetwork != null) {
                try {
                    underlyingNetwork.bindSocket(upstream)
                } catch (_: Exception) {}
            }
            upstream.connect(InetSocketAddress(upstreamIp, upstreamPort), CONNECT_TIMEOUT_MS)
            upstream.soTimeout = 0
            client.soTimeout = 0

            val uIn = upstream.getInputStream()
            val uOut = upstream.getOutputStream()

            // SOCKS5 greeting
            uOut.write(byteArrayOf(0x05, 0x01, 0x00))
            uOut.flush()

            val gVer = uIn.read()
            val gMethod = uIn.read()
            if (gVer != 5 || gMethod != 0) {
                throw IllegalStateException("Upstream rejected SOCKS5: ver=$gVer, method=$gMethod")
            }

            // SOCKS5 connect
            sendSocks5Connect(uOut, targetHost, targetPort)

            val rVer = uIn.read()
            val rRep = uIn.read()
            uIn.read()
            val rAtyp = uIn.read()
            if (rVer != 5 || rRep != 0) {
                throw IllegalStateException("Upstream connect failed: rep=$rRep")
            }
            when (rAtyp) {
                1 -> { val b = ByteArray(6); readFully(uIn, b) }
                3 -> { val len = uIn.read(); val b = ByteArray(len + 2); readFully(uIn, b) }
                4 -> { val b = ByteArray(18); readFully(uIn, b) }
            }

            // Write HTTP request to remote host via SOCKS5 tunnel
            uOut.write(headers.toString().toByteArray())
            uOut.flush()

            tunnelAndClose(client, upstream)
        } catch (e: Exception) {
            Log.w(TAG, "Plain HTTP forward to $targetHost:$targetPort failed: ${e.message}")
            try {
                output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
                output.flush()
            } catch (_: Exception) {}
            try { upstream?.close() } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
        }
    }

    private fun sendSocks5Connect(out: OutputStream, host: String, port: Int) {
        val isIpv4 = host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))
        if (isIpv4) {
            val parts = host.split(".")
            val req = ByteArray(10)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00 // RSV
            req[3] = 0x01 // IPv4
            for (i in 0..3) req[4 + i] = parts[i].toInt().toByte()
            req[8] = (port shr 8).toByte()
            req[9] = (port and 0xFF).toByte()
            out.write(req)
        } else {
            val hostBytes = host.toByteArray(Charsets.UTF_8)
            val req = ByteArray(7 + hostBytes.size)
            req[0] = 0x05
            req[1] = 0x01 // CONNECT
            req[2] = 0x00 // RSV
            req[3] = 0x03 // Domain name
            req[4] = hostBytes.size.toByte()
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
            req[5 + hostBytes.size] = (port shr 8).toByte()
            req[6 + hostBytes.size] = (port and 0xFF).toByte()
            out.write(req)
        }
        out.flush()
    }

    private fun tunnelAndClose(client: Socket, upstream: Socket) {
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            client.keepAlive = true
            upstream.soTimeout = IDLE_TIMEOUT_MS
            upstream.keepAlive = true
        } catch (_: Exception) {}

        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val upstreamIn = upstream.getInputStream()
        val upstreamOut = upstream.getOutputStream()

        val closedBoth = AtomicBoolean(false)
        val activeDirections = AtomicInteger(2)

        fun closeBoth() {
            if (closedBoth.compareAndSet(false, true)) {
                try { client.close() } catch (_: Exception) {}
                try { upstream.close() } catch (_: Exception) {}
            }
        }

        fun runDirection(
            src: Socket,
            dst: Socket,
            srcIn: InputStream,
            dstOut: OutputStream
        ) {
            try {
                pipe(srcIn, dstOut)
                // Normal EOF reached: half-close destination output if open
                try {
                    if (!dst.isOutputShutdown && !dst.isClosed) {
                        dst.shutdownOutput()
                    }
                } catch (_: Exception) {}
            } catch (e: Exception) {
                // Real I/O error or abort: close both sides
                closeBoth()
            } finally {
                if (activeDirections.decrementAndGet() == 0) {
                    closeBoth()
                }
            }
        }

        try {
            // Forward upstream -> client via managed executor
            pool.execute {
                runDirection(upstream, client, upstreamIn, clientOut)
            }

            // Forward client -> upstream on current thread
            runDirection(client, upstream, clientIn, upstreamOut)
        } catch (e: Exception) {
            closeBoth()
        }
    }

    private fun pipe(src: InputStream, dst: OutputStream) {
        val buf = ByteArray(BUFFER)
        var n: Int
        while (src.read(buf).also { n = it } != -1) {
            dst.write(buf, 0, n)
            dst.flush()
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
