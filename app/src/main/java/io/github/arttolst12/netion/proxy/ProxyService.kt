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

class ProxyService : Service() {

    companion object {
        private const val TAG = "ProxyService"
        const val CHANNEL_ID        = "proxy_channel"
        const val NOTIF_ID          = 1
        const val EXTRA_PORT        = "extra_port"
        const val EXTRA_HTTP_PORT   = "extra_http_port"
        const val EXTRA_SOCKS_PORT  = "extra_socks_port"
        const val ACTION_STOP       = "io.github.arttolst12.netion.STOP_PROXY"

        @Volatile var activeHttpPort: Int = 8080
        @Volatile var activeSocksPort: Int = 1080
        /** Set if requested ports were occupied and new ones were auto-allocated */
        @Volatile var portReallocationNotice: String? = null

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

        if (proxyServer == null) {
            portReallocationNotice = null
            serverBytesTx.set(0L)
            serverBytesRx.set(0L)
            activeHttpPort = httpPort
            activeSocksPort = socksPort

            acquireLocks()

            val server = ProxyServer(requestedHttpPort = httpPort, requestedSocks5Port = socksPort)
            server.clientFilter = { addr -> io.github.arttolst12.netion.NetworkUtils.isClientAllowed(addr) }
            server.onPortReallocated = { newHttp, newSocks ->
                activeHttpPort = newHttp
                activeSocksPort = newSocks
                val noticeParts = mutableListOf<String>()
                if (server.httpPortReallocated) {
                    noticeParts.add("HTTP $httpPort → $newHttp")
                }
                if (server.socksPortReallocated) {
                    noticeParts.add("SOCKS5 $socksPort → $newSocks")
                }
                portReallocationNotice = noticeParts.joinToString(", ")
                updateNotification(newHttp, newSocks)
            }
            proxyServer = server
            server.start()
            activeHttpPort = server.httpPort
            activeSocksPort = server.socks5Port
            isRunning = true
            Log.i(TAG, "ProxyServer started on HTTP port ${server.httpPort} and SOCKS5 port ${server.socks5Port}")
            io.github.arttolst12.netion.AppLogger.s("Server", "Proxy service started. HTTP=$activeHttpPort, SOCKS5=$activeSocksPort")
        }

        val notification = buildNotification(activeHttpPort, activeSocksPort)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        return START_STICKY
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
        } catch (_: Exception) {}
        wakeLock = null

        try {
            wifiLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wifiLock = null
    }

    override fun onDestroy() {
        isRunning = false
        proxyServer?.let {
            serverBytesTx.set(it.totalBytesTx.get())
            serverBytesRx.set(it.totalBytesRx.get())
        }
        proxyServer?.stop()
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
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Netion Link Proxy Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Netion Link прокси-сервер активен" }
            getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(httpPort: Int, socksPort: Int): Notification {
        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("Netion Link — Раздача (Сервер)")
            .setContentText("HTTP: $httpPort | SOCKS5: $socksPort")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainIntent)
            .addAction(
                Notification.Action.Builder(
                    null,
                    "Остановить",
                    stopIntent
                ).build()
            )
            .setOngoing(true)
            .build()
    }
}
