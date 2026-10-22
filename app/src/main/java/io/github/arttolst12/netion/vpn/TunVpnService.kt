package io.github.arttolst12.netion.vpn

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
import io.github.arttolst12.netion.MainActivity
import io.github.arttolst12.netion.NotificationText
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        const val ACTION_STOP      = "io.github.arttolst12.netion.STOP_VPN"
        const val MAX_ACTIVE_TCP_SESSIONS = 128

        @Volatile var isRunning = false
        /** Last error message (read by UI, then cleared) */
        @Volatile var lastError: String? = null

        val clientBytesRx = java.util.concurrent.atomic.AtomicLong(0L)
        val clientBytesTx = java.util.concurrent.atomic.AtomicLong(0L)
    }

    private val lifecycleLock = Any()
    /**
     * Monotonically invalidates all callbacks and threads from earlier VPN runs. Any cleanup
     * begins by advancing this value, before closing resources that could unblock old threads.
     */
    internal val tunGeneration = AtomicLong(0L)

    private class RuntimeContext(
        val generation: Long,
        val pfd: ParcelFileDescriptor,
        val resources: VpnRuntimeResources,
        val sessions: ConcurrentHashMap<String, TcpSession>,
        val uploadBytes: AtomicLong,
        val downloadBytes: AtomicLong,
        val stopped: AtomicBoolean = AtomicBoolean(false),
        val cleanupStarted: AtomicBoolean = AtomicBoolean(false)
    ) {
        val writer: TunWriter get() = resources.writer
        val dnsResolver: DnsResolver get() = resources.dnsResolver
        val selector: Selector get() = resources.selector

        @Volatile var tunThread: Thread? = null
        @Volatile var selectorThread: Thread? = null
        @Volatile var cleanupScheduler: ScheduledExecutorService? = null

        fun isCurrent(service: TunVpnService): Boolean =
            !stopped.get() && TunVpnService.isRunning && service.runtime === this &&
                service.tunGeneration.get() == generation
    }

    @Volatile private var runtime: RuntimeContext? = null

    // Compatibility mirrors only; packet/data-plane work snapshots [runtime] instead.
    private var pfd: ParcelFileDescriptor? = null
    private var tunThread: Thread? = null
    private var tunWriter: TunWriter? = null
    private var dnsResolver: DnsResolver? = null
    private var socksBridge: SocksBridge? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onRevoke() {
        val revokedGeneration = tunGeneration.get()
        postForGeneration(revokedGeneration) {
            Log.i(TAG, "VPN permission revoked by user or system")
            io.github.arttolst12.netion.AppLogger.w("VPN", "VPN permission revoked by user or system")
            stopVpn()
            stopSelf()
        }
        // VpnService.onRevoke() calls stopSelf() directly. Its unguarded binder-thread stop
        // would defeat the generation check, so perform that default action above on main.
    }

    internal fun postForGeneration(
        generation: Long,
        dispatch: (() -> Unit) -> Unit = { action ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { action() }
        },
        action: () -> Unit
    ) {
        dispatch {
            if (tunGeneration.get() == generation) action()
        }
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
            io.github.arttolst12.netion.AppLogger.e("VPN", "VPN startup failed; proxy=$proxyIp:$proxyPort; protocol=$proxyType", e)
            lastError = NotificationText.forContext(this).vpnStartError(e.message)
            isRunning = false
            stopVpn()
            stopSelf()
        }
        return START_NOT_STICKY
    }


    // ─────────────────────────────────────────────────
    // VPN setup
    // ─────────────────────────────────────────────────

    private fun startVpn(proxyIp: String, proxyPort: Int, proxyType: String) {
        // Tear down an earlier generation before allocating the replacement. No thread joins or
        // blocking stream closes occur under lifecycleLock.
        stopCurrentRuntime(removeForeground = false)

        val myGen = tunGeneration.incrementAndGet()
        val startedNanos = System.nanoTime()
        io.github.arttolst12.netion.AppLogger.i("VPN", "VPN starting; generation=$myGen; proxy=$proxyIp:$proxyPort; protocol=$proxyType; MTU=1400; session_limit=$MAX_ACTIVE_TCP_SESSIONS; direct_DNS_fallback=false")
        acquireLocks()
        val isSocks = proxyType.equals("SOCKS5", ignoreCase = true)
        val builder = Builder()
            .addAddress("10.0.0.2", 24)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("8.8.8.8")
            .addDnsServer("1.1.1.1")
            .setSession("Netion Link VPN")
            .setMtu(1400)
            .setBlocking(true)

        try {
            builder.addDisallowedApplication(packageName)
        } catch (_: Exception) {}

        val establishedPfd = builder.establish()
        if (establishedPfd == null) {
            Log.e(TAG, "VPN establish() returned null")
            lastError = NotificationText.forContext(this).vpnStartError("VPN establish() returned null")
            isRunning = false
            releaseLocks()
            removeForegroundNotification()
            stopSelf()
            return
        }

        val resources = VpnRuntimeResources.create(
            tun = establishedPfd,
            createOutput = { FileOutputStream(establishedPfd.fileDescriptor) },
            createWriter = { output ->
                TunWriter(
                    output = output,
                    queued = true,
                    maxQueuedBytes = 512 * 1024,
                    canWrite = { tunGeneration.get() == myGen },
                    onFailure = { error -> onTunWriterFailure(myGen, error) },
                    onBytesWritten = { bytesWritten ->
                        if (tunGeneration.get() == myGen) {
                            clientBytesRx.addAndGet(bytesWritten.toLong())
                        }
                    }
                )
            },
            createDnsResolver = { writer -> DnsResolver(this, writer, proxyIp, proxyPort, isSocks) }
        )
        val context = try {
            RuntimeContext(
                generation = myGen,
                pfd = establishedPfd,
                resources = resources,
                sessions = ConcurrentHashMap(),
                uploadBytes = AtomicLong(0L),
                downloadBytes = AtomicLong(0L)
            )
        } catch (failure: Throwable) {
            resources.close()
            throw failure
        }
        val sel = context.selector

        // Publish only after all per-generation resources exist. Every thread receives this
        // exact immutable resource bundle rather than rereading mutable service fields.
        val published = synchronized(lifecycleLock) {
            if (tunGeneration.get() != myGen) {
                false
            } else {
                runtime = context
                // Compatibility mirrors only; packet/data-plane work snapshots RuntimeContext.
                pfd = establishedPfd
                tunWriter = context.writer
                dnsResolver = context.dnsResolver
                clientBytesRx.set(0L)
                clientBytesTx.set(0L)
                isRunning = true
                lastError = null
                true
            }
        }
        if (!published) {
            closeRuntime(context)
            return
        }

        val underlyingNetwork = io.github.arttolst12.netion.NetworkUtils.getUnderlyingNetwork(this, proxyIp)
        if (underlyingNetwork != null) {
            try {
                setUnderlyingNetworks(arrayOf(underlyingNetwork))
                io.github.arttolst12.netion.AppLogger.d("VPN", "Underlying network set: $underlyingNetwork")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set underlying network: ${e.message}")
            }
        }

        val selectorWorker = Thread({ runSelectorLoop(sel, myGen) }, "vpn-selector-$myGen").also {
            it.isDaemon = true
            it.start()
        }
        context.selectorThread = selectorWorker

        // Own all session timers in this generation's scheduler. TcpSession.onTimer handles
        // retransmission/idle work at packet cadence instead of waiting for the old 10s sweep.
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "vpn-maintenance-$myGen").apply { isDaemon = true }
        }
        context.cleanupScheduler = scheduler
        scheduler.scheduleWithFixedDelay({
            if (!context.isCurrent(this)) return@scheduleWithFixedDelay
            val nowNanos = System.nanoTime()
            context.sessions.entries.removeIf { entry ->
                val session = entry.value
                try {
                    session.onTimer(nowNanos)
                } catch (e: Exception) {
                    Log.w(TAG, "TCP timer failed for ${entry.key}: ${e.message}")
                    session.close()
                }
                if (session.isDead || session.isExpired()) {
                    session.close()
                    true
                } else {
                    false
                }
            }
        }, 100L, 100L, TimeUnit.MILLISECONDS)

        val reader = Thread({ runTunLoop(context, proxyIp, proxyPort, isSocks) }, "tun-reader-$myGen").also {
            it.isDaemon = true
            it.start()
        }
        context.tunThread = reader
        tunThread = reader

        registerNetworkCallback(proxyIp, myGen)
        Log.i(TAG, "VPN started → proxy $proxyIp:$proxyPort ($proxyType)")
        io.github.arttolst12.netion.AppLogger.s("VPN", "VPN ready; generation=$myGen; proxy=$proxyIp:$proxyPort; protocol=$proxyType; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}")
    }

    private fun onTunWriterFailure(myGen: Long, cause: IOException) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (tunGeneration.get() != myGen || runtime?.generation != myGen || !isRunning) return@post
            Log.e(TAG, "TUN writer failed for generation $myGen: ${cause.message}", cause)
            io.github.arttolst12.netion.AppLogger.e("VPN", "TUN output failed; generation=$myGen", cause)
            lastError = NotificationText.forContext(this).vpnStartError("TUN write: ${cause.message}")
            if (stopCurrentRuntime(removeForeground = true, expectedGeneration = myGen)) stopSelf()
        }
    }

    private fun removeForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /** Invalidates and detaches the active runtime, but performs no potentially blocking I/O. */
    private fun detachRuntime(expectedGeneration: Long? = null): RuntimeContext? {
        synchronized(lifecycleLock) {
            val current = runtime
            // A guarded stop must never touch a newer run, including when its runtime is being
            // published concurrently. Compare the generation as well as the context identity.
            if (expectedGeneration != null &&
                (current?.generation != expectedGeneration || tunGeneration.get() != expectedGeneration)
            ) return null
            if (current == null) return null

            // Invalidate first. All delayed callbacks observe this before resources close.
            tunGeneration.incrementAndGet()
            isRunning = false
            runtime = null
            pfd = null
            tunWriter = null
            dnsResolver = null
            tunThread = null
            current.stopped.set(true)
            return current
        }
    }

    /**
     * Stops exactly the active requested generation. Returns false for a stale lifecycle event;
     * callers must not remove foreground state or stop the service in that case.
     */
    private fun stopCurrentRuntime(removeForeground: Boolean, expectedGeneration: Long? = null): Boolean {
        val old = detachRuntime(expectedGeneration) ?: return false
        if (removeForeground) {
            try { removeForegroundNotification() } catch (_: Throwable) {}
        }
        io.github.arttolst12.netion.AppLogger.i("VPN", "VPN stopping; generation=${old.generation}; sessions=${old.sessions.size}; upload_buffer_bytes=${old.uploadBytes.get()}; download_buffer_bytes=${old.downloadBytes.get()}; rx_bytes=${clientBytesRx.get()}; tx_bytes=${clientBytesTx.get()}")
        closeRuntime(old)
        unregisterNetworkCallback()
        releaseLocks()
        socksBridge?.stop()
        socksBridge = null
        return true
    }

    /** Idempotent close order: unblock descriptor/network I/O, then cancel threads; never join. */
    private fun closeRuntime(context: RuntimeContext) {
        if (!context.cleanupStarted.compareAndSet(false, true)) return
        context.stopped.set(true)
        // First close the ParcelFileDescriptor, which is the sole owner of the shared raw TUN
        // descriptor; then cancel the writer without waiting for its lock. FileInputStream in
        // the reader deliberately never closes the same descriptor in its finally block.
        context.resources.close()

        context.sessions.values.forEach { it.close() }
        context.sessions.clear()
        // TcpSession may be concurrently releasing reserved bytes. Do not reset shared counters:
        // its CAS/accounting owns their eventual return to zero without a stale-generation race.

        context.cleanupScheduler?.shutdownNow()
        context.tunThread?.interrupt()
        context.selectorThread?.interrupt()
        // Do not join: lifecycle paths may be called on main and workers may hold session locks.
    }

    private fun stopVpn() {
        val wasRunning = isRunning
        val stopped = stopCurrentRuntime(removeForeground = true)
        if (!stopped) {
            try { removeForegroundNotification() } catch (_: Throwable) {}
            unregisterNetworkCallback()
            releaseLocks()
        }
        if (stopped && wasRunning) {
            io.github.arttolst12.netion.AppLogger.i("VPN", "Netion Link VPN stopped")
        }
    }

    internal fun onFatalSelectorError(myGen: Long, cause: Throwable) {
        val active = synchronized(lifecycleLock) {
            if (tunGeneration.get() != myGen || !isRunning) return
            if (runtime == null) {
                // A partially initialized run still needs to invalidate delayed workers.
                isRunning = false
                tunGeneration.incrementAndGet()
                return
            }
            runtime?.generation == myGen
        }
        if (!active) return
        // Lifecycle intents and asynchronous failures must serialize on main, including global
        // notification/lock cleanup and stopSelf(), not just the runtime detach operation.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (tunGeneration.get() != myGen || runtime?.generation != myGen || !isRunning) return@post
            Log.e(TAG, "Fatal selector error for generation $myGen, stopping VPN: ${cause.message}", cause)
            io.github.arttolst12.netion.AppLogger.e("VPN", "NIO selector failed; generation=$myGen; stopping VPN", cause)
            lastError = NotificationText.forContext(this).vpnStartError("Selector: ${cause.message}")
            if (stopCurrentRuntime(removeForeground = true, expectedGeneration = myGen)) stopSelf()
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
                    // Short select timeout also drives timer maintenance on an idle VPN.
                    count = sel.select(100)
                } catch (e: Exception) {
                    if (isRunning && tunGeneration.get() == myGen && sel.isOpen) {
                        Log.e(TAG, "Selector.select() failed: ${e.message}", e)
                        fatalError = e
                    }
                    break
                }

                val context = runtime
                if (context?.generation == myGen && context.isCurrent(this)) {
                    val nowNanos = System.nanoTime()
                    context.sessions.entries.removeIf { entry ->
                        val session = entry.value
                        try {
                            session.onTimer(nowNanos)
                        } catch (e: Exception) {
                            Log.w(TAG, "TCP selector timer failed for ${entry.key}: ${e.message}")
                            session.close()
                        }
                        session.isDead
                    }
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
        context: RuntimeContext,
        proxyIp: String,
        proxyPort: Int,
        isSocks: Boolean
    ) {
        val generation = context.generation

        Log.i(TAG, "TUN read loop started (generation $generation)")
        try {
            // Stream construction can fail too; keep it within the generation's final cleanup.
            val input = FileInputStream(context.pfd.fileDescriptor)
            val buf = ByteArray(32767)
            while (context.isCurrent(this) && !Thread.currentThread().isInterrupted) {
                val len = input.read(buf)
                if (len < 0) break
                if (len == 0) {
                    Thread.sleep(10)
                    continue
                }
                // read() can unblock into a stale generation. Do not parse/account/publish it.
                if (!context.isCurrent(this)) break
                clientBytesTx.addAndGet(len.toLong())

                // Own each parsed packet before any asynchronous session/DNS work sees it.
                val data = buf.copyOf(len)
                val ipHeader = PacketUtils.parseIpHeader(data, len) ?: continue
                if (!context.isCurrent(this)) break

                when (ipHeader.protocol) {
                    PacketUtils.PROTO_TCP -> handleTcpPacket(context, data, ipHeader, proxyIp, proxyPort, isSocks)
                    PacketUtils.PROTO_UDP -> handleUdpPacket(context, data, ipHeader)
                }
            }
        } catch (_: InterruptedException) {
            // Expected after descriptor close/interrupt during generation teardown.
        } catch (e: Exception) {
            if (context.isCurrent(this)) {
                Log.e(TAG, "TUN loop error: ${e.message}", e)
                io.github.arttolst12.netion.AppLogger.e("VPN", "TUN input failed; generation=$generation", e)
                lastError = NotificationText.forContext(this).vpnStartError(e.message)
            }
        } finally {
            Log.i(TAG, "TUN read loop stopped (generation $generation)")
            // The ParcelFileDescriptor is the owner and is closed by closeRuntime() before a
            // replacement generation establishes a new TUN. Do not close this FileInputStream:
            // it wraps that same raw descriptor and a delayed double-close could affect a reused
            // fd number after restart.
            if (context.isCurrent(this)) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    if (!context.isCurrent(this)) return@post
                    if (stopCurrentRuntime(removeForeground = true, expectedGeneration = generation)) stopSelf()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────
    // TCP handling
    // ─────────────────────────────────────────────────

    private fun handleTcpPacket(
        context: RuntimeContext,
        data: ByteArray,
        ipHeader: PacketUtils.IpHeader,
        proxyIp: String,
        proxyPort: Int,
        isSocks: Boolean
    ) {
        if (!context.isCurrent(this)) return
        val tcpHeader = PacketUtils.parseTcpHeader(data, ipHeader.ihl, data.size) ?: return
        val flags = tcpHeader.flags
        val sessions = context.sessions
        val key = "${tcpHeader.srcPort}>${PacketUtils.ipToString(ipHeader.dstIp)}:${tcpHeader.dstPort}"
        val dstIpStr = PacketUtils.ipToString(ipHeader.dstIp)

        // Do not intercept packets directly targeted at the proxy server IP or loopback.
        if (dstIpStr == proxyIp || dstIpStr == "127.0.0.1") return

        if ((flags and PacketUtils.TCP_SYN) != 0 && (flags and PacketUtils.TCP_ACK) == 0) {
            sessions[key]?.let { old ->
                if (old.isDead) {
                    sessions.remove(key, old)
                } else {
                    old.setInitialPeerWindow(tcpHeader.windowSize)
                    old.handleSyn(tcpHeader.seqNum)
                    return
                }
            }

            if (sessions.size >= MAX_ACTIVE_TCP_SESSIONS) {
                Log.w(TAG, "Max active TCP sessions ($MAX_ACTIVE_TCP_SESSIONS) reached. Rejecting SYN for $key")
                writeRstForRejectedSyn(context, ipHeader, tcpHeader)
                return
            }

            val session = TcpSession(
                srcIp = ipHeader.srcIp,
                srcPort = tcpHeader.srcPort,
                dstIp = ipHeader.dstIp,
                dstPort = tcpHeader.dstPort,
                proxyIp = proxyIp,
                proxyPort = proxyPort,
                isSocks5 = isSocks,
                vpnService = this,
                tunWriter = context.writer,
                globalMemoryTracker = context.uploadBytes,
                globalDownloadMemoryTracker = context.downloadBytes,
                onEstablished = { sess, channel ->
                    // Snapshot this generation's selector and validate again before registration.
                    if (!context.isCurrent(this) || !context.selector.isOpen) {
                        try { sess.sendRst() } catch (_: Exception) {}
                        sess.close()
                    } else {
                        try {
                            val registration = channel.register(context.selector, SelectionKey.OP_READ, sess)
                            sess.selectionKey = registration
                            sess.selector = context.selector
                            if (!context.isCurrent(this)) {
                                registration.cancel()
                                sess.close()
                            } else {
                                sess.updateInterestOps()
                                context.selector.wakeup()
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Selector registration failed for ${sess.key()}: ${e.message}", e)
                            try { sess.sendRst() } catch (_: Exception) {}
                            sess.close()
                        }
                    }
                }
            )
            if (!context.isCurrent(this)) {
                session.close()
                return
            }
            val existing = sessions.putIfAbsent(key, session)
            if (existing != null) {
                session.close()
                if (!existing.isDead) {
                    existing.setInitialPeerWindow(tcpHeader.windowSize)
                    existing.handleSyn(tcpHeader.seqNum)
                }
                return
            }
            session.setInitialPeerWindow(tcpHeader.windowSize)
            session.handleSyn(tcpHeader.seqNum)
            return
        }

        val session = sessions[key] ?: return
        if (session.isDead) {
            sessions.remove(key, session)
            return
        }
        if (!context.isCurrent(this)) return

        val payload = PacketUtils.tcpPayload(data, ipHeader, tcpHeader)
        if ((flags and PacketUtils.TCP_RST) != 0) {
            session.handleRst()
            sessions.remove(key, session)
            return
        }

        // TcpSession handles the ACK bit internally for DATA and FIN. Dispatch handleAck only
        // for a pure ACK to avoid double acknowledgement bookkeeping.
        if ((flags and PacketUtils.TCP_FIN) != 0) {
            session.handleFin(tcpHeader, payload)
        } else if (payload.isNotEmpty()) {
            session.handleData(tcpHeader, payload)
        } else if ((flags and PacketUtils.TCP_ACK) != 0) {
            session.handleAck(tcpHeader)
        }
        if (session.isDead) {
            sessions.remove(key, session)
        }
    }

    private fun writeRstForRejectedSyn(
        context: RuntimeContext,
        ipHeader: PacketUtils.IpHeader,
        tcpHeader: PacketUtils.TcpHeader
    ) {
        if (!context.isCurrent(this)) return
        try {
            context.writer.write(PacketUtils.buildTcpPacket(
                srcIp = ipHeader.dstIp,
                dstIp = ipHeader.srcIp,
                srcPort = tcpHeader.dstPort,
                dstPort = tcpHeader.srcPort,
                seqNum = 0L,
                ackNum = (tcpHeader.seqNum + 1) and 0xFFFFFFFFL,
                flags = PacketUtils.TCP_RST or PacketUtils.TCP_ACK,
                windowSize = 0
            ))
        } catch (e: IOException) {
            if (context.isCurrent(this)) Log.w(TAG, "Unable to reject SYN: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────
    // UDP handling (DNS)
    // ─────────────────────────────────────────────────

    private fun handleUdpPacket(context: RuntimeContext, data: ByteArray, ipHeader: PacketUtils.IpHeader) {
        if (!context.isCurrent(this)) return
        val udpHeader = PacketUtils.parseUdpHeader(data, ipHeader.ihl, data.size) ?: return
        if (udpHeader.dstPort != 53) return
        val payload = PacketUtils.udpPayload(data, ipHeader)
        if (context.isCurrent(this)) {
            context.dnsResolver.handleQuery(ipHeader, udpHeader, payload)
        }
    }

    // ─────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val text = NotificationText.forContext(this)
            val ch = NotificationChannel(CHANNEL_ID, text.vpnChannelName, NotificationManager.IMPORTANCE_LOW)
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
        val text = NotificationText.forContext(this)
        return builder
            .setContentTitle(text.vpnTitle(proxyType))
            .setContentText(text.vpnBody(ip, port))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, text.stopAction, stopIntent).build())
            .setOngoing(true)
            .build()
    }

    private fun registerNetworkCallback(proxyIp: String, generation: Long) {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val active = runtime
                    if (active?.generation != generation || !active.isCurrent(this@TunVpnService)) return
                    val underlying = io.github.arttolst12.netion.NetworkUtils.getUnderlyingNetwork(this@TunVpnService, proxyIp)
                    // A callback runs on a connectivity thread. Serialize publication with
                    // stop/start on main, not merely a check followed by an unguarded API call.
                    postForGeneration(generation) {
                        if (runtime !== active || !active.isCurrent(this@TunVpnService)) return@postForGeneration
                        if (underlying != null) {
                            try {
                                setUnderlyingNetworks(arrayOf(underlying))
                                io.github.arttolst12.netion.AppLogger.d("VPN", "Network switched to: $underlying")
                            } catch (_: Exception) {}
                        }
                    }
                }

                override fun onLost(network: Network) {
                    postForGeneration(generation) {
                        val active = runtime
                        if (active?.generation != generation || !active.isCurrent(this@TunVpnService)) return@postForGeneration
                        io.github.arttolst12.netion.AppLogger.w("VPN", "Network connection lost: $network")
                    }
                }
            }
            synchronized(lifecycleLock) {
                if (runtime?.generation != generation || tunGeneration.get() != generation) return
                networkCallback = cb
            }
            cm?.registerDefaultNetworkCallback(cb)
            if (runtime?.generation != generation || tunGeneration.get() != generation) {
                try { cm?.unregisterNetworkCallback(cb) } catch (_: Exception) {}
                synchronized(lifecycleLock) {
                    if (networkCallback === cb) networkCallback = null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "NetworkCallback registration failed: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = synchronized(lifecycleLock) {
            networkCallback.also { networkCallback = null }
        } ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {}
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