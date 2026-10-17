package com.example.netion

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.Locale

object NetworkUtils {

    const val DEFAULT_HOTSPOT_IP = "192.168.43.1"
    const val DEFAULT_USB_TETHER_IP = "192.168.42.129"

    /**
     * Returns the best candidate IP address for tethering / hotspot / USB / Wi-Fi sharing.
     * Strictly excludes cellular data interfaces (rmnet, ccmni, v4-*, wwan, radio, clat)
     * and external VPN tunnels (tun, tap, wg, wireguard, vpn, v2ray, sing, clash, tailscale),
     * ensuring clients receive a reachable local address (e.g. 192.168.43.1 or 192.168.x.x).
     */
    fun getServerIp(context: Context): String? {
        val candidates = getAllCandidateIps(context)
        return candidates.firstOrNull()
    }

    /**
     * Returns an ordered, deduplicated list of reachable server IP candidates.
     */
    fun getAllCandidateIps(context: Context): List<String> {
        val result = LinkedHashSet<String>()

        // 1. Identify VPN and Cellular interfaces via ConnectivityManager
        val vpnAndCellularIfaces = mutableSetOf<String>()
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null) {
            try {
                for (network in cm.allNetworks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    if (isVpn || isCellular) {
                        val lp = cm.getLinkProperties(network)
                        lp?.interfaceName?.let { vpnAndCellularIfaces.add(it.lowercase(Locale.ROOT)) }
                    }
                }
            } catch (_: Exception) {}
        }

        val allIfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        // 2. Tethering / Hotspot interfaces by name (rndis, swlan, ap, tether, hotspot, softap, usb)
        val tetheringKeywords = listOf("rndis", "swlan", "ap", "tether", "hotspot", "softap", "usb")
        for (iface in allIfaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (isForbiddenIface(name, vpnAndCellularIfaces)) continue
            if (tetheringKeywords.any { name.contains(it) }) {
                for (ip in getIfaceIpv4Addresses(iface)) {
                    if (isPrivateIpv4(ip)) {
                        result.add(ip)
                    }
                }
            }
        }

        // 3. Wi-Fi interfaces (wlan0, wlan1, etc. which many phones use for AP or Wi-Fi sharing)
        for (iface in allIfaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (isForbiddenIface(name, vpnAndCellularIfaces)) continue
            if (name.contains("wlan")) {
                for (ip in getIfaceIpv4Addresses(iface)) {
                    if (isPrivateIpv4(ip)) {
                        result.add(ip)
                    }
                }
            }
        }

        // 4. Other local LAN / Ethernet interfaces (e.g. eth0)
        for (iface in allIfaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (isForbiddenIface(name, vpnAndCellularIfaces)) continue
            for (ip in getIfaceIpv4Addresses(iface)) {
                if (isPrivateIpv4(ip)) {
                    result.add(ip)
                }
            }
        }

        // 5. USB tethering (RNDIS / usb)
        for (iface in allIfaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (name.contains("rndis") || name.contains("usb")) {
                for (ip in getIfaceIpv4Addresses(iface)) {
                    if (isPrivateIpv4(ip)) {
                        result.add(ip)
                    }
                }
            }
        }

        // 6. Explicit check for 192.168.43.x on any non-forbidden interface
        for (iface in allIfaces) {
            val name = iface.name.lowercase(Locale.ROOT)
            if (isForbiddenIface(name, vpnAndCellularIfaces)) continue
            for (ip in getIfaceIpv4Addresses(iface)) {
                if (ip.startsWith("192.168.43.")) {
                    result.add(ip)
                }
            }
        }

        // 7. Fallback: only if NO real interface IP was found, and Hotspot is reported on
        val isHotspotOn = isWifiApEnabled(context)
        if (result.isEmpty() && isHotspotOn) {
            result.add(DEFAULT_HOTSPOT_IP)
        }

        return result.toList()
    }

    /**
     * Checks whether Wi-Fi Hotspot (SoftAP) is enabled on Android via WifiManager.
     */
    fun isWifiApEnabled(context: Context): Boolean {
        return try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
            val method = wm.javaClass.getDeclaredMethod("isWifiApEnabled")
            method.isAccessible = true
            (method.invoke(wm) as? Boolean) ?: false
        } catch (_: Exception) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
                val method = wm.javaClass.getDeclaredMethod("getWifiApState")
                method.isAccessible = true
                val state = (method.invoke(wm) as? Int) ?: -1
                state == 12 || state == 13 // WIFI_AP_STATE_ENABLING = 12, WIFI_AP_STATE_ENABLED = 13
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun isForbiddenIface(name: String, vpnAndCellularIfaces: Set<String>): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        if (vpnAndCellularIfaces.contains(lower)) return true
        return lower.startsWith("tun") ||
               lower.startsWith("tap") ||
               lower.startsWith("ppp") ||
               lower.startsWith("p2p") ||
               lower.startsWith("dummy") ||
               lower.startsWith("rmnet") ||
               lower.startsWith("v4-") || // 464xlat CLAT interface for cellular IPv4
               lower.startsWith("ccmni") ||
               lower.startsWith("wwan") ||
               lower.startsWith("radio") ||
               lower.startsWith("clat") ||
               lower.startsWith("wg") || // WireGuard
               lower.startsWith("wireguard") ||
               lower.startsWith("vpn") ||
               lower.startsWith("ipsec") ||
               lower.startsWith("v2ray") ||
               lower.startsWith("sing") ||
               lower.startsWith("clash") ||
               lower.startsWith("tailscale") ||
               lower.startsWith("utun") ||
               lower.startsWith("pdp") ||
               lower.startsWith("qcom") ||
               lower.startsWith("ipa") ||
               lower.startsWith("seth") ||
               lower.startsWith("lo")
    }

    private fun isExcludedIp(ip: String): Boolean {
        return ip.startsWith("127.") || 
               ip.startsWith("169.254.") || 
               ip.startsWith("0.") ||
               ip == "255.255.255.255"
    }

    fun isPrivateIpv4(ip: String): Boolean {
        if (ip.startsWith("10.") || ip.startsWith("192.168.")) return true
        if (ip.startsWith("172.")) {
            val parts = ip.split(".")
            if (parts.size == 4) {
                val second = parts[1].toIntOrNull() ?: 0
                return second in 16..31
            }
        }
        return false
    }

    private fun getIfaceIpv4Addresses(iface: NetworkInterface): List<String> {
        return try {
            iface.inetAddresses.asSequence()
                .filter { !it.isLoopbackAddress && it is Inet4Address }
                .mapNotNull { it.hostAddress }
                .filterNot { isExcludedIp(it) }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Finds the default gateway IPv4 of the client's network (e.g. 192.168.43.1 when connected to Hotspot).
     * Prioritizes Wi-Fi (via DHCP info and LinkProperties) and Ethernet/USB tethering over Cellular.
     */
    fun getClientGatewayIp(context: Context): String? {
        // 1. Check WifiManager DHCP info (standard Android Wi-Fi gateway, very reliable for Hotspot)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            @Suppress("DEPRECATION")
            val dhcp = wm?.dhcpInfo
            if (dhcp != null && dhcp.gateway != 0) {
                val gw = String.format(
                    Locale.ROOT,
                    "%d.%d.%d.%d",
                    dhcp.gateway and 0xFF,
                    (dhcp.gateway shr 8) and 0xFF,
                    (dhcp.gateway shr 16) and 0xFF,
                    (dhcp.gateway shr 24) and 0xFF
                )
                if (gw != "0.0.0.0" && !isExcludedIp(gw)) {
                    return gw
                }
            }
        } catch (_: Exception) {}

        // 2. Search ConnectivityManager networks prioritizing Wi-Fi and Ethernet
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
            val networks = cm.allNetworks
            val prioritizedNetworks = networks.sortedByDescending { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@sortedByDescending 0
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 3
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 0
                }
            }

            for (network in prioritizedNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (route in lp.routes) {
                    if (route.gateway is Inet4Address) {
                        val gw = route.gateway?.hostAddress
                        if (gw != null && gw != "0.0.0.0" && !isExcludedIp(gw)) {
                            return gw
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    /**
     * Resolves the physical underlying network (e.g. Wi-Fi hotspot connection) to which outgoing
     * proxy/VPN sockets should be bound via [Network.bindSocket].
     *
     * This is critical when connected to an Android Hotspot without internet access:
     * Android leaves Cellular as the active default network, so normal sockets route over mobile data
     * (e.g. 10.x.x.x) and cannot reach 192.168.43.1. Binding to the Wi-Fi Network ensures traffic routes
     * directly across the local tethering interface.
     */
    fun getUnderlyingNetwork(context: Context, proxyIp: String? = null): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        return try {
            val networks = cm.allNetworks

            // 1. If proxyIp is specified, look for a network whose LinkProperties contains or routes to proxyIp
            if (!proxyIp.isNullOrEmpty()) {
                for (network in networks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                    val lp = cm.getLinkProperties(network) ?: continue

                    for (route in lp.routes) {
                        if (route.gateway?.hostAddress == proxyIp) {
                            return network
                        }
                    }
                    for (linkAddr in lp.linkAddresses) {
                        val ifaceIp = linkAddr.address.hostAddress ?: continue
                        if (areInSameSubnet(ifaceIp, proxyIp, linkAddr.prefixLength)) {
                            return network
                        }
                    }
                }
            }

            // 2. Look for Wi-Fi network (hotspot or local Wi-Fi)
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    return network
                }
            }

            // 3. Look for Ethernet / USB tethering network
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    return network
                }
            }

            // 4. Default active network (if not VPN)
            val active = cm.activeNetwork
            if (active != null) {
                val caps = cm.getNetworkCapabilities(active)
                if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return active
                }
            }

            // 5. Any network that is not a VPN
            for (network in networks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return network
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    private fun areInSameSubnet(ip1: String, ip2: String, prefixLength: Int): Boolean {
        return try {
            val b1 = java.net.InetAddress.getByName(ip1).address
            val b2 = java.net.InetAddress.getByName(ip2).address
            if (b1.size != b2.size || b1.size != 4) return false
            val mask = if (prefixLength <= 0) 0 else if (prefixLength >= 32) -1 else (-1 shl (32 - prefixLength))
            val i1 = ((b1[0].toInt() and 0xFF) shl 24) or ((b1[1].toInt() and 0xFF) shl 16) or ((b1[2].toInt() and 0xFF) shl 8) or (b1[3].toInt() and 0xFF)
            val i2 = ((b2[0].toInt() and 0xFF) shl 24) or ((b2[1].toInt() and 0xFF) shl 16) or ((b2[2].toInt() and 0xFF) shl 8) or (b2[3].toInt() and 0xFF)
            (i1 and mask) == (i2 and mask)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if WiFi hotspot or USB tethering is likely active.
     */
    fun isTethering(context: Context): Boolean = getServerIp(context) != null

    /**
     * Checks if a TCP port is currently free/bindable on all interfaces.
     */
    fun isPortAvailable(port: Int): Boolean {
        if (port !in 1024..65535) return false
        return try {
            val ss = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(port))
            }
            ss.close()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Finds the first available port starting from [preferredPort].
     * If [preferredPort] is available, returns it immediately.
     * Otherwise scans upward (preferredPort + 1 .. + 100).
     * If still not found, lets the OS pick an ephemeral free port.
     * Excludes any ports in [excludePorts].
     */
    fun findAvailablePort(preferredPort: Int, excludePorts: Set<Int> = emptySet()): Int {
        if (isPortAvailable(preferredPort) && preferredPort !in excludePorts) {
            return preferredPort
        }
        for (offset in 1..100) {
            val candidate = preferredPort + offset
            if (candidate in 1024..65535 && candidate !in excludePorts && isPortAvailable(candidate)) {
                return candidate
            }
        }
        // Fallback: ephemeral port assigned by OS
        return try {
            val ss = ServerSocket(0)
            val p = ss.localPort
            ss.close()
            p
        } catch (_: Exception) {
            preferredPort
        }
    }
}
