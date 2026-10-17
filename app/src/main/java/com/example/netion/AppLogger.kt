package com.example.netion

import androidx.compose.runtime.mutableStateListOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    const val MAX_ENTRIES = 120

    data class LogItem(
        val id: Long = System.nanoTime(),
        val time: String,
        val tag: String,
        val message: String,
        val isError: Boolean = false,
        val isSuccess: Boolean = false
    )

    val logs = mutableStateListOf<LogItem>()

    fun d(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
        post(tag, msg, isError = false, isSuccess = false)
    }

    fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
        post(tag, msg, isError = false, isSuccess = false)
    }

    fun s(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
        post(tag, msg, isError = false, isSuccess = true)
    }

    fun w(tag: String, msg: String) {
        android.util.Log.w(tag, msg)
        post(tag, msg, isError = true, isSuccess = false)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        android.util.Log.e(tag, msg, tr)
        val text = if (tr != null) "$msg (${tr.localizedMessage ?: tr.javaClass.simpleName})" else msg
        post(tag, text, isError = true, isSuccess = false)
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private fun post(tag: String, msg: String, isError: Boolean, isSuccess: Boolean) {
        val timeStr = synchronized(timeFormat) { timeFormat.format(Date()) }
        val item = LogItem(time = timeStr, tag = tag, message = msg, isError = isError, isSuccess = isSuccess)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (logs.size >= MAX_ENTRIES) {
                logs.removeAt(0)
            }
            logs.add(item)
        }
    }

    fun clear() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            logs.clear()
        }
    }

    fun getAllText(): String {
        val snapshot = logs.toList()
        return snapshot.joinToString("\n") { "[${it.time}] [${it.tag}] ${it.message}" }
    }
}
