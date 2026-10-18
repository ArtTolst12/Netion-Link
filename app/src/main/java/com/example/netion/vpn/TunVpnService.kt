package com.example.netion.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.netion.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * VPN client: intercepts all device traffic via TUN interface,
 * forwards TCP through the upstream proxy (HTTP CONNECT or SOCKS5),
 * and forwards DNS via protected DatagramSocket.
 */
class TunVpnService : VpnService() {

    companion object {
        const val TAG              = "TunVpnService"
        const val CHANNEL_ID       = "vpn_channel"
        const val NOTIF_ID         = 2
        const val EXTRA_PROXY_IP   = "proxy_ip"
        const val EXTRA_PROXY_PORT = "proxy_port"
        const val EXTRA_PROXY_TYPE = "proxy_type"
        const val ACTION_STOP      = "com.example.netion.STOP_VPN"
        const val MAX_ACTIVE_TCP_SESSIONS = 128

        @Volatile var isRunning = false
        /** Last error message (read by UI, then cleared) */
        @Volatile var lastError: String? = null

        val clientBytesRx = java.util.concurrent.atomic.AtomicLong(0L)
        val clientBytesTx = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var tunThread: Thread? = null
    private var tunWriter: TunWriter? = null
    private var dnsResolver: DnsResolver? = null
    private var socksBridge: SocksBridge? = null
    private val sessions = ConcurrentHashMap<String, TcpSession>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val globalUploadBytes = AtomicLong(0L)
    private var selector: Selector? = null
    private var selectorThread: Thread? = null
    private var cleanupScheduler: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN permission revoked by user or system")
        com.example.netion.AppLogger.w("VPN", "VPN permission revoked by user or system")
        stopVpn()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Log.i(TAG, "Stopping VPN service via ACTION_STOP")
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
        }

        val proxyIp   = intent?.getStringExtra(EXTRA_PROXY_IP)   ?: return START_NOT_STICKY
        val proxyPort = intent.getIntExtra(EXTRA_PROXY_PORT, 8080)
        val proxyType = intent.getStringExtra(EXTRA_PROXY_TYPE)
            ?: if (proxyPort == 1080) "SOCKS5" else "HTTP"

        try {
            val notification = buildNotification(proxyIp, proxyPort, proxyType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            startVpn(proxyIp, proxyPort, proxyType)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            lastError = "Не удалось запустить VPN: ${e.message}"
            isRunning = false
            stopVpn()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private val lifecycleLock = Any()
    internal val tunGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    // ─────────────────────────────────────────────────
    // VPN setup
    // ─────────────────────────────────────────────────

    private fun startVpn(proxyIp: String, proxyPort: Int, proxyType: String) {
        synchronized(lifecycleLock) {
            // Clean up previous connection instance WITHOUT dropping foreground notification
            cleanupConnectionsLocked()
            val myGen = tunGeneration.incrementAndGet()
            acquireLocks()

            val isSocks = (proxyType.uppercase() == "SOCKS5" || proxyPort == 1080)

            val builder = Builder()
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("8.8.8.8")
                .addDnsServer("1.1.1.1")
                .setSession("Netion Link VPN")
                .setMtu(1400)
                .setBlocking(true)

            // Exclude our own app from VPN to prevent loops
            try {
                builder.addDisallowedApplication(packageName)
            } catch (_: Exception) {}

            pfd = builder.establish()

            if (pfd == null) {
                Log.e(TAG, "VPN establish() returned null")
                lastError = "VPN не установлен — проверьте разрешения"
                cleanupConnectionsLocked()
                isRunning = false
                removeForegroundNotification()
                stopSelf()
                return
            }

            val fd = pfd!!

            val underlyingNetwork = com.example.netion.NetworkUtils.getUnderlyingNetwork(this, proxyIp)
            if (underlyingNetwork != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                try {
                    setUnderlyingNetworks(arrayOf(underlyingNetwork))
                    com.example.netion.AppLogger.d("VPN", "Underlying network set: $underlyingNetwork")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set underlying network: ${e.message}")
                }
            }

            val writer = TunWriter(FileOutputStream(fd.fileDescriptor)) { bytesWritten ->
                clientBytesRx.addAndGet(bytesWritten.toLong())
            }
            tunWriter = writer
            dnsResolver = DnsResolver(this, writer, proxyIp, proxyPort, isSocks)

            isRunning = true
            lastError = null
            clientBytesRx.set(0L)
            clientBytesTx.set(0L)

            val sel = Selector.open()
            selector = sel
            val sThread = Thread({
                runSelectorLoop(sel, myGen)
            }, "vpn-selector-$myGen").also {
                it.isDaemon = true
                it.start()
            }
            selectorThread = sThread

            val scheduler = Executors.newSingleThreadScheduledExecutor()
            cleanupScheduler = scheduler
            scheduler.scheduleAtFixedRate({
                val now = System.currentTimeMillis()
                sessions.entries.removeIf { entry ->
                    val session = entry.value
                    if (session.isDead) {
                        true
                    } else if (session.isExpired(now)) {
                        Log.d(TAG, "Cleaning up expired/stale TCP session: ${entry.key}")
                        session.close()
                        true
                    } else {
                        false
                    }
                }
            }, 10L, 10L, TimeUnit.SECONDS)

            tunThread = Thread({ runTunLoop(proxyIp, proxyPort, isSocks, fd, myGen) }, "tun-reader-$myGen").also {
                it.isDaemon = true
                it.start()
            }

            registerNetworkCallback(proxyIp)
            Log.i(TAG, "VPN started → proxy $proxyIp:$proxyPort ($proxyType)")
            com.example.netion.AppLogger.s("VPN", "Netion Link VPN started → Proxy: $proxyIp:$proxyPort ($proxyType)")
        }
    }

    private fun removeForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun cleanupConnectionsLocked() {
        unregisterNetworkCallback()
        releaseLocks()

        cleanupScheduler?.shutdownNow()
        cleanupScheduler = null

        // 1. Close all sessions first so they return memory to globalUploadBytes
        sessions.values.forEach { it.close() }
        sessions.clear()

        // 2. Close selector and stop thread
        try { selector?.close() } catch (_: Exception) {}
        selector = null
        selectorThread?.interrupt()
        selectorThread = null

        // 3. Reset global memory tracker cleanly to 0
        globalUploadBytes.set(0L)

        dnsResolver?.close()
        dnsResolver = null

        socksBridge?.stop()
        socksBridge = null

        tunWriter = null

        tunThread?.interrupt()
        tunThread = null

        try { pfd?.close() } catch (_: Exception) {}
        pfd = null
    }

    private fun stopVpn() {
        synchronized(lifecycleLock) {
            val wasRunning = isRunning
            isRunning = false
            tunGeneration.incrementAndGet()

            removeForegroundNotification()
            cleanupConnectionsLocked()

            if (wasRunning) {
                com.example.netion.AppLogger.i("VPN", "Netion Link VPN stopped")
            }
        }
    }

    internal fun onFatalSelectorError(myGen: Long, cause: Throwable) {
        synchronized(lifecycleLock) {
            if (tunGeneration.get() != myGen || !isRunning) return
            Log.e(TAG, "Fatal selector error for generation $myGen, stopping VPN: ${cause.message}", cause)
            com.example.netion.AppLogger.e("VPN", "NIO selector failed: ${cause.message}. Stopping VPN service.")
            isRunning = false
            tunGeneration.incrementAndGet()
            try { removeForegroundNotification() } catch (_: Throwable) {}
            cleanupConnectionsLocked()
            try { stopSelf() } catch (_: Throwable) {}
        }
    }

    // ─────────────────────────────────────────────────
    // NIO Selector loop
    // ─────────────────────────────────────────────────

    internal fun runSelectorLoop(sel: Selector, myGen: Long) {
        // MTU is 1400. IP (20) + TCP (20) headers = 40. Maximum safe read payload is TcpSession.MSS (1360).
        val readBuf = ByteBuffer.allocate(TcpSession.MSS)
        var fatalError: Throwable? = null
        try {
            while (isRunning && !Thread.currentThread().isInterrupted && tunGeneration.get() == myGen) {
                val count: Int
                try {
                    count = sel.select(1000)
                } catch (e: Exception) {
                    if (isRunning && tunGeneration.get() == myGen && sel.isOpen) {
                        Log.e(TAG, "Selector.select() failed: ${e.message}", e)
                        fatalError = e
                    }
                    break
                }
                if (count == 0) continue
                val iter = sel.selectedKeys().iterator()
                while (iter.hasNext()) {
                    val key = iter.next()
                    iter.remove()
                    if (!key.isValid) continue
                    val session = key.attachment() as? TcpSession ?: continue
                    try {
                        if (key.isValid && key.isReadable) {
                            session.onProxyReadable(readBuf)
                        }
                        if (key.isValid && key.isWritable) {
                            session.onProxyWritable()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error servicing key for session ${session.key()}: ${e.message}", e)
                        try { session.sendRst() } catch (_: Exception) {}
                        session.close()
                        try { key.cancel() } catch (_: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning && tunGeneration.get() == myGen) {
                Log.e(TAG, "Fatal error in selector loop: ${e.message}", e)
                fatalError = e
            }
        } finally {
            if (fatalError != null && isRunning && tunGeneration.get() == myGen) {
                onFatalSelectorError(myGen, fatalError)
            }
        }
    }

    // ─────────────────────────────────────────────────
    // TUN packet loop
    // ─────────────────────────────────────────────────

    private fun runTunLoop(
        proxyIp: String,
        proxyPort: Int,
        isSocks: Boolean,
        fd: ParcelFileDescriptor,
        generation: Long
    ) {
        val input = FileInputStream(fd.fileDescriptor)
        val buf = ByteArray(32767)

        Log.i(TAG, "TUN read loop started (generation $generation)")

        var lastCleanupTime = System.currentTimeMillis()

        try {
            while (isRunning && !Thread.currentThread().isInterrupted && tunGeneration.get() == generation) {
                val len = input.read(buf)
                if (len <= 0) {
                    Thread.sleep(10)
                    continue
                }

                clientBytesTx.addAndGet(len.toLong())

                val data = buf.copyOf(len)
                val ipHeader = PacketUtils.parseIpHeader(data, len) ?: continue

                when (ipHeader.protocol) {
                    PacketUtils.PROTO_TCP -> handleTcpPacket(data, ipHeader, proxyIp, proxyPort, isSocks, generation)
                    PacketUtils.PROTO_UDP -> handleUdpPacket(data, ipHeader)
                }

                // Periodic cleanup of dead or expired/abandoned sessions (every 5 seconds)
                val now = System.currentTimeMillis()
                if (now - lastCleanupTime > 5000L) {
                    lastCleanupTime = now
                    sessions.entries.removeIf { entry ->
                        val session = entry.value
                        if (session.isDead) {
                            true
                        } else if (session.isExpired(now)) {
                            Log.d(TAG, "Cleaning up expired/stale TCP session: ${entry.key}")
                            session.close()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
        } catch (e: InterruptedException) {
            // Normal shutdown
        } catch (e: Exception) {
            if (isRunning && tunGeneration.get() == generation) {
                Log.e(TAG, "TUN loop error: ${e.message}")
                lastError = "VPN ошибка: ${e.message}"
            }
        } finally {
            Log.i(TAG, "TUN read loop stopped (generation $generation)")
            try { input.close() } catch (_: Exception) {}
            try { fd.close() } catch (_: Exception) {}
            synchronized(lifecycleLock) {
                if (isRunning && tunGeneration.get() == generation) {
                    stopVpn()
                    stopSelf()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────
    // TCP handling
    // ─────────────────────────────────────────────────

    private fun handleTcpPacket(
        data: ByteArray,
        ipHeader: PacketUtils.IpHeader,
        proxyIp: String,
        proxyPort: Int,
        isSocks: Boolean,
        generation: Long
    ) {
        val tcpHeader = PacketUtils.parseTcpHeader(data, ipHeader.ihl) ?: return
        val flags = tcpHeader.flags

        val key = "${tcpHeader.srcPort}>${PacketUtils.ipToString(ipHeader.dstIp)}:${tcpHeader.dstPort}"
        val dstIpStr = PacketUtils.ipToString(ipHeader.dstIp)

        // Do not intercept packets directly targeted at the proxy server IP or loopback (prevents self-loop)
        if (dstIpStr == proxyIp || dstIpStr == "127.0.0.1") return

        // SYN — new connection
        if ((flags and PacketUtils.TCP_SYN) != 0 && (flags and PacketUtils.TCP_ACK) == 0) {
            sessions[key]?.let { old ->
                if (old.isDead) {
                    sessions.remove(key)
                } else {
                    old.handleSyn(tcpHeader.seqNum)
                    return
                }
            }

            if (sessions.size >= MAX_ACTIVE_TCP_SESSIONS) {
                Log.w(TAG, "Max active TCP sessions ($MAX_ACTIVE_TCP_SESSIONS) reached. Rejecting SYN for $key")
                tunWriter?.write(PacketUtils.buildTcpPacket(
                    srcIp = ipHeader.dstIp,
                    dstIp = ipHeader.srcIp,
                    srcPort = tcpHeader.dstPort,
                    dstPort = tcpHeader.srcPort,
                    seqNum = 0L,
                    ackNum = (tcpHeader.seqNum + 1) and 0xFFFFFFFFL,
                    flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
                    windowSize = 0
                ))
                return
            }

            val currentSel = selector
            val session = TcpSession(
                srcIp               = ipHeader.srcIp,
                srcPort             = tcpHeader.srcPort,
                dstIp               = ipHeader.dstIp,
                dstPort             = tcpHeader.dstPort,
                proxyIp             = proxyIp,
                proxyPort           = proxyPort,
                isSocks5            = isSocks,
                vpnService          = this,
                tunWriter           = tunWriter ?: return,
                globalMemoryTracker = globalUploadBytes,
                onEstablished       = { sess, channel ->
                    val sel = currentSel
                    if (sel != null && sel.isOpen && isRunning && tunGeneration.get() == generation) {
                        try {
                            val k = channel.register(sel, SelectionKey.OP_READ, sess)
                            sess.selectionKey = k
                            sess.selector = sel
                            sess.updateInterestOps()
                            sel.wakeup()
                        } catch (e: Exception) {
                            Log.e(TAG, "Selector registration failed for ${sess.key()}: ${e.message}", e)
                            sess.sendRst()
                            sess.close()
                        }
                    } else {
                        Log.e(TAG, "Selector closed or stale generation during registration for ${sess.key()}")
                        sess.sendRst()
                        sess.close()
                    }
                }
            )
            sessions[key] = session
            session.handleSyn(tcpHeader.seqNum)
            return
        }

        val session = sessions[key] ?: return

        if (session.isDead) {
            sessions.remove(key)
            return
        }

        val payload = PacketUtils.tcpPayload(data, ipHeader, tcpHeader)

        // RST
        if ((flags and PacketUtils.TCP_RST) != 0) {
            session.handleRst()
            sessions.remove(key)
            return
        }

        // FIN (may carry payload)
        if ((flags and PacketUtils.TCP_FIN) != 0) {
            session.handleFin(tcpHeader, payload)
            if (session.isDead) {
                sessions.remove(key)
            }
            return
        }

        // DATA or plain ACK
        if (payload.isNotEmpty()) {
            session.handleData(tcpHeader, payload)
        } else {
            session.handleAck(tcpHeader)
        }
        if (session.isDead) {
            sessions.remove(key)
        }
    }

    // ─────────────────────────────────────────────────
    // UDP handling (DNS)
    // ─────────────────────────────────────────────────

    private fun handleUdpPacket(data: ByteArray, ipHeader: PacketUtils.IpHeader) {
        val udpHeader = PacketUtils.parseUdpHeader(data, ipHeader.ihl) ?: return
        if (udpHeader.dstPort != 53) return

        val payload = PacketUtils.udpPayload(data, ipHeader)
        dnsResolver?.handleQuery(ipHeader, udpHeader, payload)
    }

    // ─────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "VPN Клиент", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(ip: String, port: Int, proxyType: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TunVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Netion Link — VPN Клиент ($proxyType)")
            .setContentText("Подключено через $ip:$port")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Отключить", stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun registerNetworkCallback(proxyIp: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                val cb = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        if (!isRunning) return
                        val underlying = com.example.netion.NetworkUtils.getUnderlyingNetwork(this@TunVpnService, proxyIp)
                        if (underlying != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            try {
                                setUnderlyingNetworks(arrayOf(underlying))
                                com.example.netion.AppLogger.d("VPN", "Network switched to: $underlying")
                            } catch (_: Exception) {}
                        }
                    }

                    override fun onLost(network: Network) {
                        if (!isRunning) return
                        com.example.netion.AppLogger.w("VPN", "Network connection lost: $network")
                    }
                }
                networkCallback = cb
                cm?.registerDefaultNetworkCallback(cb)
            } catch (e: Exception) {
                Log.w(TAG, "NetworkCallback registration failed: ${e.message}")
            }
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (_: Exception) {}
            networkCallback = null
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Netion:VpnWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Netion:VpnWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Netion:VpnWifiLock")
            }?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire VPN WakeLock/WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        try { wifiLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wifiLock = null
    }
}