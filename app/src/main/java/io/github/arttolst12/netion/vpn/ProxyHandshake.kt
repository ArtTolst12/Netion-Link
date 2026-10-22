package io.github.arttolst12.netion.vpn

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * Establishes an outgoing TCP tunnel through an HTTP CONNECT or SOCKS5 proxy.
 *
 * The caller must protect and, if required, bind [socket] to its underlying network before
 * invoking this helper. This helper deliberately does not call VpnService.protect(). On any
 * exception the caller owns closing the socket. A deadline guard closes the socket only when
 * the configured deadline expires, including while a blocking write is in progress.
 */
object ProxyHandshake {
    private const val MAX_HTTP_RESPONSE_BYTES = 16 * 1024

    private val deadlineScheduler = ScheduledThreadPoolExecutor(
        1,
        ThreadFactory { runnable ->
            Thread(runnable, "proxy-handshake-deadline").apply { isDaemon = true }
        }
    ).apply {
        // Completed handshakes must not leave cancelled timeout tasks queued indefinitely.
        setRemoveOnCancelPolicy(true)
    }

    /**
     * Connects [socket] to [proxyIp]:[proxyPort] and opens a tunnel to [targetIp]:[targetPort].
     * The single monotonic [timeoutMs] budget covers TCP connect and every handshake operation.
     */
    fun connect(
        socket: Socket,
        proxyIp: String,
        proxyPort: Int,
        targetIp: ByteArray,
        targetPort: Int,
        socks5: Boolean,
        timeoutMs: Int = 10_000,
        onPhase: (String) -> Unit = {}
    ) {
        require(proxyIp.isNotBlank()) { "Proxy IP must not be blank" }
        require(proxyPort in 1..65535) { "Invalid proxy port: $proxyPort" }
        require(targetPort in 1..65535) { "Invalid target port: $targetPort" }
        require(targetIp.size == 4 || targetIp.size == 16) {
            "Target address must be an IPv4 or IPv6 byte array"
        }
        require(timeoutMs > 0) { "Timeout must be positive" }

        val deadlineNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        // Exactly one path wins: successful completion disarms the guard before the caller can
        // use the socket, while timeout wins by closing a blocking connect/read/write.
        val handshakeActive = AtomicBoolean(true)
        val deadlineExpired = AtomicBoolean(false)
        val deadlineGuard = deadlineScheduler.schedule(
            {
                if (handshakeActive.compareAndSet(true, false)) {
                    deadlineExpired.set(true)
                    closeQuietly(socket)
                }
            },
            timeoutMs.toLong(),
            TimeUnit.MILLISECONDS
        )

        try {
            onPhase("proxy-tcp-connect")
            socket.connect(InetSocketAddress(proxyIp, proxyPort), remainingMillis(deadlineNano))
            ensureBeforeDeadline(deadlineNano)

            if (socks5) {
                performSocks5Handshake(socket, targetIp, targetPort, deadlineNano, onPhase)
            } else {
                performHttpConnectHandshake(socket, targetIp, targetPort, deadlineNano, onPhase)
            }

            ensureBeforeDeadline(deadlineNano)
            // Atomically disarm before returning: an already-running deadline task then cannot
            // close a successfully established tunnel after this method returns.
            if (!handshakeActive.compareAndSet(true, false)) {
                throw SocketTimeoutException("Proxy handshake deadline exceeded")
            }
            // The handshake deadline should not become an application data idle timeout.
            socket.soTimeout = 0
        } catch (t: Throwable) {
            val timedOut = deadlineExpired.get() || deadlineNano - System.nanoTime() <= 0L
            handshakeActive.compareAndSet(true, false)
            // The caller still owns cleanup, but close now to guarantee a timed-out/failing
            // handshake cannot retain a partially-open proxy connection.
            closeQuietly(socket)
            // A deadline-triggered close may surface as a generic IOException on Android/NIO.
            // Preserve the real timeout and its cause, but do not relabel an earlier peer reset.
            if (t is IOException && t !is SocketTimeoutException && timedOut) {
                throw SocketTimeoutException("Proxy handshake deadline exceeded").apply { initCause(t) }
            }
            throw t
        } finally {
            deadlineGuard.cancel(false)
        }
    }

    private fun performSocks5Handshake(
        socket: Socket,
        targetIp: ByteArray,
        targetPort: Int,
        deadlineNano: Long,
        onPhase: (String) -> Unit
    ) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        onPhase("socks5-greeting-write")
        writeWithDeadline(output, byteArrayOf(0x05, 0x01, 0x00), deadlineNano)
        onPhase("socks5-greeting-read")
        val greetingVersion = readByte(socket, input, deadlineNano)
        val greetingMethod = readByte(socket, input, deadlineNano)
        if (greetingVersion != 0x05 || greetingMethod != 0x00) {
            throw IOException("SOCKS5 proxy rejected no-auth greeting")
        }

        val atyp = if (targetIp.size == 4) 0x01 else 0x04
        val request = ByteArray(4 + targetIp.size + 2)
        request[0] = 0x05
        request[1] = 0x01 // CONNECT
        request[2] = 0x00 // RSV
        request[3] = atyp.toByte()
        System.arraycopy(targetIp, 0, request, 4, targetIp.size)
        request[request.size - 2] = (targetPort ushr 8).toByte()
        request[request.size - 1] = targetPort.toByte()
        onPhase("socks5-connect-write")
        writeWithDeadline(output, request, deadlineNano)
        onPhase("socks5-connect-read")

        val version = readByte(socket, input, deadlineNano)
        val reply = readByte(socket, input, deadlineNano)
        val reserved = readByte(socket, input, deadlineNano)
        val responseAtyp = readByte(socket, input, deadlineNano)
        if (version != 0x05) throw IOException("Invalid SOCKS5 response version: $version")
        if (reply != 0x00) throw IOException("SOCKS5 CONNECT failed with reply $reply")
        if (reserved != 0x00) throw IOException("Invalid SOCKS5 response reserved byte: $reserved")

        when (responseAtyp) {
            0x01 -> readFully(socket, input, ByteArray(4), deadlineNano)
            0x04 -> readFully(socket, input, ByteArray(16), deadlineNano)
            0x03 -> {
                val domainLength = readByte(socket, input, deadlineNano)
                if (domainLength == 0) throw IOException("Invalid zero-length SOCKS5 bound domain")
                readFully(socket, input, ByteArray(domainLength), deadlineNano)
            }
            else -> throw IOException("Invalid SOCKS5 response address type: $responseAtyp")
        }
        // Bound port is mandatory even though the caller does not use it.
        readFully(socket, input, ByteArray(2), deadlineNano)
    }

    private fun performHttpConnectHandshake(
        socket: Socket,
        targetIp: ByteArray,
        targetPort: Int,
        deadlineNano: Long,
        onPhase: (String) -> Unit
    ) {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val address = InetAddress.getByAddress(targetIp).hostAddress
            ?: throw IOException("Invalid target address")
        val authority = if (targetIp.size == 16) "[$address]:$targetPort" else "$address:$targetPort"
        val request = "CONNECT $authority HTTP/1.1\r\nHost: $authority\r\n\r\n"
            .toByteArray(Charsets.ISO_8859_1)
        onPhase("http-connect-write")
        writeWithDeadline(output, request, deadlineNano)
        onPhase("http-connect-read")
        readHttp200Response(socket, input, deadlineNano)
    }

    /** Requires a complete, bounded HTTP header block whose status code is exactly 200. */
    private fun readHttp200Response(socket: Socket, input: InputStream, deadlineNano: Long) {
        val response = ByteArrayOutputStream()
        var matchedTerminatorBytes = 0
        val terminator = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())

        while (response.size() < MAX_HTTP_RESPONSE_BYTES) {
            val next = readByte(socket, input, deadlineNano).toByte()
            response.write(next.toInt())
            if (next == terminator[matchedTerminatorBytes]) {
                matchedTerminatorBytes++
                if (matchedTerminatorBytes == terminator.size) break
            } else {
                matchedTerminatorBytes = if (next == terminator[0]) 1 else 0
            }
        }

        if (matchedTerminatorBytes != terminator.size) {
            throw IOException("HTTP CONNECT response exceeds $MAX_HTTP_RESPONSE_BYTES bytes or lacks CRLFCRLF")
        }

        val header = response.toString(Charsets.ISO_8859_1.name())
        val statusEnd = header.indexOf("\r\n")
        if (statusEnd <= 0) throw IOException("Malformed HTTP CONNECT status line")
        val statusLine = header.substring(0, statusEnd)
        val firstSpace = statusLine.indexOf(' ')
        if (firstSpace <= 0) throw IOException("Malformed HTTP CONNECT status line")
        val secondSpace = statusLine.indexOf(' ', firstSpace + 1)
        val version = statusLine.substring(0, firstSpace)
        val statusCode = if (secondSpace < 0) {
            statusLine.substring(firstSpace + 1)
        } else {
            statusLine.substring(firstSpace + 1, secondSpace)
        }
        if ((version != "HTTP/1.0" && version != "HTTP/1.1") || statusCode != "200") {
            throw IOException("HTTP CONNECT proxy returned '$statusLine'")
        }
    }

    private fun writeWithDeadline(output: OutputStream, bytes: ByteArray, deadlineNano: Long) {
        ensureBeforeDeadline(deadlineNano)
        output.write(bytes)
        output.flush()
        ensureBeforeDeadline(deadlineNano)
    }

    private fun readByte(socket: Socket, input: InputStream, deadlineNano: Long): Int {
        socket.soTimeout = remainingMillis(deadlineNano)
        return input.read().also {
            if (it < 0) throw EOFException("Premature EOF during proxy handshake")
        }
    }

    private fun readFully(socket: Socket, input: InputStream, buffer: ByteArray, deadlineNano: Long) {
        var offset = 0
        while (offset < buffer.size) {
            socket.soTimeout = remainingMillis(deadlineNano)
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count < 0) throw EOFException("Premature EOF during proxy handshake")
            if (count == 0) {
                ensureBeforeDeadline(deadlineNano)
                continue
            }
            offset += count
        }
    }

    private fun remainingMillis(deadlineNano: Long): Int {
        val remainingNanos = deadlineNano - System.nanoTime()
        if (remainingNanos <= 0L) throw SocketTimeoutException("Proxy handshake deadline exceeded")
        return ((remainingNanos + 999_999L) / 1_000_000L)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()
    }

    private fun ensureBeforeDeadline(deadlineNano: Long) {
        if (deadlineNano - System.nanoTime() <= 0L) {
            throw SocketTimeoutException("Proxy handshake deadline exceeded")
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
