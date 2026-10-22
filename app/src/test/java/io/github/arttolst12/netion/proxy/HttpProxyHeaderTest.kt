package io.github.arttolst12.netion.proxy

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpProxyHeaderTest {
    @Test
    fun proxyCredentialsAreNotForwardedToOrigin() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { origin ->
            origin.soTimeout = 3000
            val pool = Executors.newSingleThreadExecutor()
            val proxy = ProxyServer(requestedHttpPort = 0, requestedSocks5Port = 0)
            val body = "Proxy-Authorization: this is body data"
            try {
                proxy.start()
                val forwarded = pool.submit<String> {
                    origin.accept().use { connection ->
                        connection.soTimeout = 3000
                        val input = connection.getInputStream()
                        val headers = readHeaders(input)
                        val receivedBody = ByteArray(body.length)
                        var offset = 0
                        while (offset < receivedBody.size) {
                            val count = input.read(receivedBody, offset, receivedBody.size - offset)
                            check(count > 0) { "EOF before request body" }
                            offset += count
                        }
                        assertEquals(body, String(receivedBody, Charsets.ISO_8859_1))
                        connection.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK".toByteArray())
                            flush()
                        }
                        headers
                    }
                }
                Socket("127.0.0.1", proxy.httpPort).use { client ->
                    client.soTimeout = 3000
                    val request = "POST http://127.0.0.1:${origin.localPort}/check HTTP/1.1\r\n" +
                        "Host: 127.0.0.1:${origin.localPort}\r\n" +
                        "pRoXy-AuThOrIzAtIoN: Basic synthetic-proxy-credential\r\n" +
                        "Proxy-Connection: keep-alive\r\n" +
                        "Authorization: Bearer synthetic-origin-credential\r\n" +
                        "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    client.getOutputStream().apply {
                        write(request.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }
                    val response = readHeaders(client.getInputStream())
                    assertTrue(response.startsWith("HTTP/1.1 200"))
                    val headers = forwarded.get(3, TimeUnit.SECONDS)
                    assertFalse("Proxy credentials must not reach an origin server", headers.contains("Proxy-Authorization", ignoreCase = true))
                    assertFalse("Proxy-only connection header must be consumed", headers.contains("Proxy-Connection", ignoreCase = true))
                    assertTrue("Origin credentials must be preserved", headers.contains("Authorization: Bearer synthetic-origin-credential"))
                    assertTrue(headers.contains("Content-Length: ${body.length}"))
                }
            } finally {
                proxy.stop()
                origin.close()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun foldedProxyCredentialsAreRejectedBeforeConnectingToOrigin() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { origin ->
            origin.soTimeout = 200
            val proxy = ProxyServer(requestedHttpPort = 0, requestedSocks5Port = 0)
            try {
                proxy.start()
                Socket("127.0.0.1", proxy.httpPort).use { client ->
                    client.soTimeout = 2000
                    client.getOutputStream().apply {
                        write(("GET / HTTP/1.1\r\nHost: 127.0.0.1:${origin.localPort}\r\n" +
                            "Proxy-Authorization: Basic\r\n synthetic-folded-secret\r\n\r\n").toByteArray())
                        flush()
                    }
                    assertEquals(-1, client.getInputStream().read())
                }
                try {
                    origin.accept().use { fail("malformed headers must not connect to origin") }
                } catch (_: java.net.SocketTimeoutException) {
                }
            } finally {
                proxy.stop()
            }
        }
    }

    private fun readHeaders(input: InputStream): String {
        val output = ByteArrayOutputStream()
        while (output.size() < 8192) {
            val next = input.read()
            check(next >= 0) { "EOF before headers" }
            output.write(next)
            val text = output.toString("ISO-8859-1")
            if (text.endsWith("\r\n\r\n")) return text
        }
        error("Headers too large")
    }
}
