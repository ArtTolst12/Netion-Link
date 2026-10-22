package io.github.arttolst12.netion

import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import io.github.arttolst12.netion.proxy.ProxyService
import io.github.arttolst12.netion.vpn.TunVpnService
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

object AppLogger {
    const val MAX_ENTRIES = 120
    const val MAX_TEXT_LENGTH = 4_096

    private const val MAX_PENDING_ENTRIES = 256
    private const val DRAIN_BATCH_SIZE = 24
    private const val MAX_TAG_LENGTH = 128
    private const val TRUNCATION_SUFFIX = "… [truncated]"

    private val nextId = AtomicLong(1L)
    private val startedNanos = System.nanoTime()
    private val droppedEntries = AtomicLong()
    private val evictedEntries = AtomicLong()

    enum class Level { DEBUG, INFO, SUCCESS, WARN, ERROR }

    data class LogItem(
        val id: Long = AppLogger.nextLogId(),
        val time: String,
        val tag: String,
        val message: String,
        val isError: Boolean = false,
        val isSuccess: Boolean = false,
        val level: Level = when {
            isSuccess -> Level.SUCCESS
            isError -> Level.ERROR
            else -> Level.INFO
        },
        val elapsedMs: Long = 0L,
        val thread: String = ""
    )

    /** Compose state; this object mutates it only from the main Handler. */
    val logs = mutableStateListOf<LogItem>()

    private data class PendingLog(
        val time: String,
        val tag: String,
        val message: String,
        val level: Level,
        val elapsedMs: Long,
        val thread: String
    )

    private data class VisibleLog(val epoch: Long, val item: LogItem)

    private val pendingLogs = BoundedLogQueue<PendingLog>(MAX_PENDING_ENTRIES)
    private val stateLock = Any()
    private val visibleLogs = ArrayDeque<VisibleLog>() // Main thread only.
    private val snapshotLock = Any()
    private val textSnapshot = ArrayList<LogItem>(MAX_ENTRIES)

    // Local JVM tests may load this object without an Android main looper.
    private val mainHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private val drainRunnable = Runnable { drainPendingLogs() }
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun d(tag: String, msg: String) = record(tag, msg, Level.DEBUG)
    fun i(tag: String, msg: String) = record(tag, msg, Level.INFO)
    fun s(tag: String, msg: String) = record(tag, msg, Level.SUCCESS)
    fun w(tag: String, msg: String) = record(tag, msg, Level.WARN)

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        val text = if (tr == null) msg else "$msg; ${DiagnosticText.failure(tr)}"
        record(tag, text, Level.ERROR, tr)
    }

    private fun record(tag: String, msg: String, level: Level, error: Throwable? = null) {
        val safeTag = limitText(tag, MAX_TAG_LENGTH)
        val safeMessage = limitText(msg)
        when (level) {
            Level.DEBUG -> android.util.Log.d(safeTag, safeMessage)
            Level.INFO, Level.SUCCESS -> android.util.Log.i(safeTag, safeMessage)
            Level.WARN -> android.util.Log.w(safeTag, safeMessage)
            Level.ERROR -> android.util.Log.e(safeTag, safeMessage, error)
        }
        val pending = PendingLog(
            time = synchronized(timeFormat) { timeFormat.format(Date()) },
            tag = safeTag,
            message = safeMessage,
            level = level,
            elapsedMs = DiagnosticText.elapsedMillis(startedNanos),
            thread = limitText(Thread.currentThread().name, MAX_TAG_LENGTH)
        )
        if (pendingLogs.offer(pending)) mainHandler.post(drainRunnable)
    }

    private fun drainPendingLogs() {
        val batch = pendingLogs.drain(DRAIN_BATCH_SIZE)
        synchronized(stateLock) {
            val activeEpoch = pendingLogs.currentEpoch()
            val entries = batch.entries.filter { it.epoch == activeEpoch }
            val skippedCount = if (entries.isNotEmpty()) batch.skippedCount else 0
            if (skippedCount > 0) {
                droppedEntries.addAndGet(skippedCount.toLong())
                appendOnMain(activeEpoch, LogItem(
                    time = synchronized(timeFormat) { timeFormat.format(Date()) },
                    tag = "AppLogger",
                    message = "Skipped $skippedCount log entries due to backlog; pending_limit=$MAX_PENDING_ENTRIES",
                    isError = true,
                    level = Level.WARN,
                    elapsedMs = DiagnosticText.elapsedMillis(startedNanos),
                    thread = Thread.currentThread().name
                ))
            }
            entries.forEach { entry ->
                val pending = entry.value
                appendOnMain(entry.epoch, LogItem(
                    time = pending.time,
                    tag = pending.tag,
                    message = pending.message,
                    isError = pending.level == Level.WARN || pending.level == Level.ERROR,
                    isSuccess = pending.level == Level.SUCCESS,
                    level = pending.level,
                    elapsedMs = pending.elapsedMs,
                    thread = pending.thread
                ))
            }
        }
        if (batch.hasMore) mainHandler.post(drainRunnable)
    }

    /** Invalidates already-drained entries before clearing the main-thread view. */
    fun clear() {
        val clearedEpoch = synchronized(stateLock) {
            val epoch = pendingLogs.clear()
            droppedEntries.set(0L)
            evictedEntries.set(0L)
            synchronized(snapshotLock) { textSnapshot.clear() }
            epoch
        }
        mainHandler.post { clearOnMain(clearedEpoch) }
    }

    private fun clearOnMain(clearedEpoch: Long) {
        val minimumEpoch = maxOf(clearedEpoch, pendingLogs.currentEpoch())
        if (visibleLogs.none { it.epoch < minimumEpoch }) return
        val retained = ArrayList<VisibleLog>(visibleLogs.size)
        while (visibleLogs.isNotEmpty()) {
            val entry = visibleLogs.removeFirst()
            if (entry.epoch >= minimumEpoch) retained += entry
        }
        retained.forEach(visibleLogs::addLast)
        logs.clear()
        logs.addAll(retained.map(VisibleLog::item))
        synchronized(snapshotLock) {
            textSnapshot.clear()
            retained.forEach { textSnapshot += it.item }
        }
    }

    private fun appendOnMain(epoch: Long, item: LogItem) {
        if (visibleLogs.size >= MAX_ENTRIES) {
            if (visibleLogs.removeFirst().epoch == epoch) evictedEntries.incrementAndGet()
        }
        if (logs.size >= MAX_ENTRIES) logs.removeAt(0)
        visibleLogs.addLast(VisibleLog(epoch, item))
        logs.add(item)
        synchronized(snapshotLock) {
            if (textSnapshot.size >= MAX_ENTRIES) textSnapshot.removeAt(0)
            textSnapshot += item
        }
    }

    fun getAllText(): String {
        val snapshot = synchronized(snapshotLock) { textSnapshot.toList() }
        return snapshot.joinToString("\n") {
            DiagnosticText.entry(it.time, it.level.name, it.tag, it.message, it.elapsedMs, it.thread)
        }
    }

    /** Built only for an explicit copy action; no networking or disk writes. */
    fun getDiagnosticReport(): String {
        val proxy = ProxyService.currentInstance?.proxyServer
        val entries = getAllText()
        val displayed = synchronized(snapshotLock) { textSnapshot.size }
        return DiagnosticText.report(listOf(
            "Generated" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.ROOT).format(Date()),
            "App" to "Netion Link ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); debug=${BuildConfig.DEBUG}",
            "Android" to "${Build.VERSION.RELEASE}; API=${Build.VERSION.SDK_INT}",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Logger uptime_ms" to DiagnosticText.elapsedMillis(startedNanos).toString(),
            "Buffer" to "displayed=$displayed/$MAX_ENTRIES; evicted=${evictedEntries.get()}; backlog_dropped=${droppedEntries.get()}",
            "VPN" to "running=${TunVpnService.isRunning}; rx_bytes=${TunVpnService.clientBytesRx.get()}; tx_bytes=${TunVpnService.clientBytesTx.get()}",
            "Proxy" to "running=${ProxyService.isRunning}; active_clients=${proxy?.activeClientConnectionsCount ?: 0}; active_sockets=${proxy?.activeSocketsCount ?: 0}",
            "Proxy ports" to if (ProxyService.isRunning) "HTTP=${ProxyService.activeHttpPort}; SOCKS5=${ProxyService.activeSocksPort}" else "inactive",
            "Proxy traffic" to "rx_bytes=${proxy?.totalBytesRx?.get() ?: ProxyService.serverBytesRx.get()}; tx_bytes=${proxy?.totalBytesTx?.get() ?: ProxyService.serverBytesTx.get()}"
        ), entries)
    }

    fun shutdown() {
        synchronized(stateLock) { pendingLogs.clear() }
    }

    private fun limitText(value: String, maxLength: Int = MAX_TEXT_LENGTH): String {
        if (value.length <= maxLength) return value
        return value.take(maxLength - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX
    }

    private fun nextLogId(): Long = nextId.getAndIncrement()
}
