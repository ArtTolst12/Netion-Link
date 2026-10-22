package io.github.arttolst12.netion.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import io.github.arttolst12.netion.MainActivity
import io.github.arttolst12.netion.NotificationText

class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        const val CHANNEL_ID = "proxy_channel"
        const val NOTIF_ID = 1
        const val EXTRA_PORT = "extra_port"
        const val EXTRA_HTTP_PORT = "extra_http_port"
        const val EXTRA_SOCKS_PORT = "extra_socks_port"
        const val ACTION_STOP = "io.github.arttolst12.netion.STOP_PROXY"

        @Volatile var activeHttpPort: Int = 8080
        @Volatile var activeSocksPort: Int = 1080
        /** Set if requested ports were occupied and new ones were auto-allocated. */
        @Volatile var portReallocationNotice: String? = null
        /** The most recent startup failure, retained for UI presentation after service cleanup. */
        @Volatile var lastError: String? = null

        val serverBytesTx = java.util.concurrent.atomic.AtomicLong(0L)
        val serverBytesRx = java.util.concurrent.atomic.AtomicLong(0L)

        @Volatile var isRunning = false
        @Volatile var currentInstance: ProxyService? = null
    }

    var proxyServer: ProxyServer? = null
        private set
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        currentInstance = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val httpPort = intent?.getIntExtra(EXTRA_HTTP_PORT, intent.getIntExtra(EXTRA_PORT, 8080)) ?: 8080
        val socksPort = intent?.getIntExtra(EXTRA_SOCKS_PORT, 1080) ?: 1080
        val existing = proxyServer
        if (existing?.isRunning == true) {
            // A repeated start command keeps the existing, successfully bound endpoints.
            isRunning = true
            startAsForeground(buildNotification(existing.httpPort, existing.socks5Port))
            return START_STICKY
        }
        if (existing != null) {
            existing.stop()
            proxyServer = null
            isRunning = false
        }

        // Android requires foreground promotion before potentially lengthy startup work. This
        // intentionally reports only a starting state, never unbound requested ports as active.
        try {
            startAsForeground(buildStartingNotification())
        } catch (e: Exception) {
            handleStartupFailure(null, e, startId)
            return START_NOT_STICKY
        }

        lastError = null
        portReallocationNotice = null
        val startedNanos = System.nanoTime()
        io.github.arttolst12.netion.AppLogger.i("Server", "Proxy service starting; requested_HTTP=$httpPort; requested_SOCKS5=$socksPort")
        val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
        try {
            acquireLocks()
            server.clientFilter = { address ->
                io.github.arttolst12.netion.NetworkUtils.isClientAllowed(address)
            }

            // start() synchronously binds both listeners or throws after rolling back both.
            server.start()
            if (!server.isRunning) {
                throw IllegalStateException("Proxy startup was cancelled before both listeners became active")
            }

            // Publish all externally visible status only after both actual bind ports succeeded.
            proxyServer = server
            activeHttpPort = server.httpPort
            activeSocksPort = server.socks5Port
            serverBytesTx.set(0L)
            serverBytesRx.set(0L)
            if (server.httpPortReallocated || server.socksPortReallocated) {
                val noticeParts = mutableListOf<String>()
                if (server.httpPortReallocated) {
                    noticeParts.add("HTTP $httpPort → ${server.httpPort}")
                }
                if (server.socksPortReallocated) {
                    noticeParts.add("SOCKS5 $socksPort → ${server.socks5Port}")
                }
                portReallocationNotice = noticeParts.joinToString(", ")
            }
            isRunning = true
            lastError = null
            updateNotification(activeHttpPort, activeSocksPort)
            Log.i(TAG, "ProxyServer started on HTTP port $activeHttpPort and SOCKS5 port $activeSocksPort")
            io.github.arttolst12.netion.AppLogger.s(
                "Server",
                "Proxy service ready; HTTP=$activeHttpPort; SOCKS5=$activeSocksPort; ports_reallocated=${portReallocationNotice != null}; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}"
            )
            return START_STICKY
        } catch (e: Exception) {
            handleStartupFailure(server, e, startId)
            return START_NOT_STICKY
        }
    }

    private fun handleStartupFailure(server: ProxyServer?, error: Exception, startId: Int) {
        try {
            server?.stop()
        } catch (_: Exception) {
        }
        if (proxyServer === server) proxyServer = null
        isRunning = false
        portReallocationNotice = null
        lastError = error.message ?: error.javaClass.simpleName
        releaseLocks()
        removeForegroundNotification()
        Log.e(TAG, "Failed to start proxy server: $lastError", error)
        io.github.arttolst12.netion.AppLogger.e("Server", "Proxy service startup failed", error)
        stopSelfResult(startId)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun removeForegroundNotification() {
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Netion:ProxyWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(24 * 60 * 60 * 1000L) // 24h safety limit
            }

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "Netion:ProxyWifiLock")
            } else {
                @Suppress("DEPRECATION")
                wm?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Netion:ProxyWifiLock")
            }?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire WakeLock / WifiLock: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {
        }
        wifiLock = null
    }

    override fun onDestroy() {
        isRunning = false
        proxyServer?.let {
            io.github.arttolst12.netion.AppLogger.i("Server", "Proxy stopping; active_clients=${it.activeClientConnectionsCount}; active_sockets=${it.activeSocketsCount}; rx_bytes=${it.totalBytesRx.get()}; tx_bytes=${it.totalBytesTx.get()}")
            serverBytesTx.set(it.totalBytesTx.get())
            serverBytesRx.set(it.totalBytesRx.get())
            it.stop()
        }
        proxyServer = null
        currentInstance = null
        portReallocationNotice = null
        releaseLocks()
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────
    // Notification
    // ─────────────────────────────────────────────────

    private fun updateNotification(httpPort: Int, socksPort: Int) {
        val notifManager = getSystemService(NotificationManager::class.java)
        notifManager?.notify(NOTIF_ID, buildNotification(httpPort, socksPort))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val text = NotificationText.forContext(this)
            val channel = NotificationChannel(
                CHANNEL_ID,
                text.proxyChannelName,
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = text.proxyTitle }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildStartingNotification(): Notification =
        buildNotification(contentText = NotificationText.forContext(this).proxyTitle)

    private fun buildNotification(httpPort: Int, socksPort: Int): Notification =
        buildNotification(contentText = NotificationText.forContext(this).proxyBody(httpPort, socksPort))

    private fun buildNotification(contentText: String): Notification {
        val mainIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val text = NotificationText.forContext(this)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(text.proxyTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    text.stopAction,
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
