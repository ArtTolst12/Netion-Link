package io.github.arttolst12.netion.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

class ProxyServerLifecycleTest {

    @Test
    fun startSynchronouslyBindsBothListenersAndCanRestartAfterStop() {
        val server = ProxyServer(requestedHttpPort = 0, requestedSocks5Port = 0)
        try {
            server.start()
            assertTrue("Both listener binds must complete before start returns", server.isRunning)
            assertTrue(server.httpPort > 0)
            assertTrue(server.socks5Port > 0)
            assertNotEquals(server.httpPort, server.socks5Port)
            Socket("127.0.0.1", server.httpPort).use { }
            Socket("127.0.0.1", server.socks5Port).use { }

            server.stop()
            assertFalse(server.isRunning)
            assertEquals(0, server.activeSocketsCount)
            assertEquals(0, server.activeClientConnectionsCount)

            server.start()
            assertTrue("A stopped server must use a fresh worker pool", server.isRunning)
            Socket("127.0.0.1", server.httpPort).use { }
        } finally {
            server.stop()
        }
    }

    @Test
    fun occupiedRequestedPortFallsBackThroughAnActualBind() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { occupied ->
            val server = ProxyServer(requestedHttpPort = occupied.localPort, requestedSocks5Port = 0)
            try {
                server.start()
                assertTrue(server.isRunning)
                assertTrue(server.httpPortReallocated)
                assertNotEquals("Fallback must not report the occupied requested port", occupied.localPort, server.httpPort)
                assertTrue(server.httpPort > 0)
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun malformedSocksReservedByteAndIncompleteHttpReleaseClientResources() {
        val server = ProxyServer(requestedHttpPort = 0, requestedSocks5Port = 0)
        try {
            server.start()

            Socket("127.0.0.1", server.httpPort).use { socksClient ->
                socksClient.soTimeout = 2_000
                val output = socksClient.getOutputStream()
                val input = socksClient.getInputStream()
                output.write(byteArrayOf(0x05, 0x01, 0x00))
                output.flush()
                assertEquals(0x05, input.read())
                assertEquals(0x00, input.read())
                // VER, CONNECT, invalid RSV, IPv4, address, port. The invalid RSV must close it.
                output.write(byteArrayOf(0x05, 0x01, 0x01, 0x01, 127, 0, 0, 1, 0, 80))
                output.flush()
                assertEquals(-1, input.read())
            }
            awaitNoClients(server)

            Socket("127.0.0.1", server.httpPort).use { httpClient ->
                httpClient.soTimeout = 2_000
                httpClient.getOutputStream().apply {
                    write("CONNECT 127.0.0.1:80 HTTP/1.1\r\n".toByteArray())
                    flush()
                }
                // EOF before the terminating blank header line is not a valid request.
                httpClient.shutdownOutput()
                assertEquals(-1, httpClient.getInputStream().read())
            }
            awaitNoClients(server)
        } finally {
            server.stop()
        }
    }

    private fun awaitNoClients(server: ProxyServer) {
        val deadline = System.currentTimeMillis() + 2_000
        while (
            (server.activeSocketsCount != 0 || server.activeClientConnectionsCount != 0) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertEquals(0, server.activeSocketsCount)
        assertEquals(0, server.activeClientConnectionsCount)
    }
}
