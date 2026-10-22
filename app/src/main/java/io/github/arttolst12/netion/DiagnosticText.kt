package io.github.arttolst12.netion

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** English diagnostic formatting, independent of Android and the selected UI language. */
internal object DiagnosticText {
    fun elapsedMillis(startNanos: Long, nowNanos: Long = System.nanoTime()): Long =
        TimeUnit.NANOSECONDS.toMillis((nowNanos - startNanos).coerceAtLeast(0))

    fun failure(error: Throwable): String {
        val reset = error is IOException && error !is SocketTimeoutException &&
            error.message.orEmpty().let {
                it.contains("connection reset", ignoreCase = true) || it.contains("ECONNRESET", ignoreCase = true)
            }
        val reason = if (reset) "connection-reset" else when (error) {
            is SocketTimeoutException -> "timeout"
            is UnknownHostException -> "name-resolution-failed"
            is ConnectException -> "connection-failed"
            is EOFException -> "unexpected-eof"
            is SocketException -> "socket-error"
            is SecurityException -> "permission-denied"
            is IOException -> "io-error"
            is IllegalArgumentException -> "invalid-argument"
            is IllegalStateException -> "invalid-state"
            else -> "unexpected-error"
        }
        val causes = ArrayList<String>(3)
        var current: Throwable? = error
        repeat(3) {
            val cause = current ?: return@repeat
            causes += "${cause.javaClass.simpleName}: ${singleLine(cause.message.orEmpty()).take(240)}"
            current = cause.cause
        }
        return "reason=$reason; ${causes.joinToString(" <- ")}".take(768)
    }

    fun entry(time: String, level: String, tag: String, message: String, elapsedMs: Long, thread: String): String {
        val header = "[${singleLine(time)}] [${singleLine(level)}] [${singleLine(tag)}] " +
            "+${elapsedMs.coerceAtLeast(0)}ms [${singleLine(thread)}] "
        return header + message.lineSequence().joinToString("\n    ") { line ->
            line.map { if (it.isISOControl() && it != '\t') ' ' else it }.joinToString("")
        }
    }

    fun report(metadata: List<Pair<String, String>>, entries: String): String = buildString {
        appendLine("Netion Link diagnostic report")
        for ((key, value) in metadata) appendLine("${singleLine(key)}: ${singleLine(value).take(512)}")
        appendLine("Storage: bounded in-memory log; no automatic upload")
        appendLine("Privacy: entries may contain destination addresses. Review before sharing.")
        appendLine("--- Events (English) ---")
        if (entries.isBlank()) {
            append("(No log entries.)")
        } else if (entries.length <= MAX_EXPORT_CHARS) {
            append(entries)
        } else {
            appendLine("(Earlier events omitted; export limit=$MAX_EXPORT_CHARS characters.)")
            val tail = entries.takeLast(MAX_EXPORT_CHARS)
            val boundary = tail.indexOf('\n')
            append(if (boundary >= 0) tail.substring(boundary + 1) else tail)
        }
    }

    private const val MAX_EXPORT_CHARS = 128 * 1024

    private fun singleLine(value: String): String = value.map {
        if (it.isISOControl()) ' ' else it
    }.joinToString("")
}
