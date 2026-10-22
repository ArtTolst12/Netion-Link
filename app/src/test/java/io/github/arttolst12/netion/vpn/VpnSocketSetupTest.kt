package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

class VpnSocketSetupTest {
    @Test
    fun createsSocketBeforeProtectAndBindsNetworkOnlyAfterProtection() {
        Socket().use { socket ->
            val order = mutableListOf<String>()
            assertTrue(VpnSocketSetup.prepare(socket, protect = {
                assertSame(socket, it)
                assertTrue(it.isBound)
                assertTrue(it.localPort > 0)
                assertTrue(it.localAddress.isAnyLocalAddress)
                assertFalse(it.isConnected)
                order += "protect"
                true
            }, bindToNetwork = {
                assertSame(socket, it)
                assertFalse(it.isConnected)
                order += "network"
            }))
            assertEquals(listOf("protect", "network"), order)
            assertFalse(socket.isClosed)
        }
    }

    @Test
    fun protectionRefusalSkipsNetworkBinding() {
        Socket().use { socket ->
            var boundToNetwork = false
            assertFalse(VpnSocketSetup.prepare(socket, protect = { false }, bindToNetwork = {
                boundToNetwork = true
            }))
            assertFalse(boundToNetwork)
            assertFalse(socket.isConnected)
        }
    }

    @Test
    fun existingLocalBindIsPreserved() {
        Socket().use { socket ->
            socket.bind(InetSocketAddress(0))
            val originalPort = socket.localPort
            assertTrue(VpnSocketSetup.prepare(socket, protect = { true }))
            assertEquals(originalPort, socket.localPort)
        }
    }

    @Test
    fun networkBindingFailurePropagatesWithoutConnecting() {
        Socket().use { socket ->
            val failure = IOException("network disappeared")
            try {
                VpnSocketSetup.prepare(socket, protect = { true }, bindToNetwork = { throw failure })
                fail("network binding failure must not be ignored")
            } catch (e: IOException) {
                assertSame(failure, e)
            }
            assertFalse(socket.isConnected)
        }
    }

    @Test
    fun closedSocketCannotBeProtected() {
        val socket = Socket()
        socket.close()
        try {
            VpnSocketSetup.prepare(socket, protect = { fail("must not protect closed socket"); true })
            fail("expected closed socket rejection")
        } catch (_: SocketException) {
        }
    }

    @Test
    fun connectedSocketIsRejectedBeforeProtection() {
        ServerSocket(0, 1, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { server ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", server.localPort), 1000)
                try {
                    VpnSocketSetup.prepare(socket, protect = { fail("protection would be too late"); true })
                    fail("expected connected socket rejection")
                } catch (_: SocketException) {
                }
            }
        }
    }
}
