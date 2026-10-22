package io.github.arttolst12.netion.vpn

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException

/** Prepares an outbound socket without sending traffic. The caller owns cleanup on every exit. */
internal object VpnSocketSetup {
    fun prepare(
        socket: Socket,
        protect: (Socket) -> Boolean,
        bindToNetwork: ((Socket) -> Unit)? = null
    ): Boolean {
        if (socket.isClosed) throw SocketException("VPN socket is closed")
        if (socket.isConnected) throw SocketException("VPN socket must be protected before connecting")
        // Android may allocate a plain Socket's FD lazily. VpnService.protect(Socket) needs
        // that FD, so create it with a local wildcard bind, not a network connect or write.
        if (!socket.isBound) socket.bind(InetSocketAddress(0))
        if (!protect(socket)) return false
        // A failed physical-network bind must propagate, never fall back to default routing.
        bindToNetwork?.invoke(socket)
        return true
    }
}
