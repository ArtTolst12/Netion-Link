package com.example.netion.qr

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.util.EnumMap

data class QrPayload(
    val ip: String,
    val port: Int,
    val httpPort: Int = 8080,
    val socks5Port: Int = 1080
)

object QrCodeManager {

    /**
     * Creates a connection URI string:
     * e.g. netion://connect?ip=192.168.43.1&port=1080&http=8080&socks5=1080
     */
    fun buildConnectionUri(ip: String, selectedPort: Int, httpPort: Int, socks5Port: Int): String {
        return "netion://connect?ip=$ip&port=$selectedPort&http=$httpPort&socks5=$socks5Port"
    }

    /**
     * Generates a QR Code Bitmap from the given content string.
     */
    fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? {
        return try {
            val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
                put(EncodeHintType.MARGIN, 1)
            }
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val offset = y * width
                for (x in 0 until width) {
                    pixels[offset + x] = if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses a scanned string into IP and Port.
     * Supports:
     * - netion://connect?ip=192.168.43.1&port=1080&http=8080&socks5=1080
     * - netion://192.168.43.1:1080
     * - http://192.168.43.1:8080
     * - 192.168.43.1:1080
     */
    fun parseQrString(scannedText: String): QrPayload? {
        val trimmed = scannedText.trim()
        if (trimmed.isEmpty()) return null

        try {
            // Check netion:// URI scheme
            if (trimmed.startsWith("netion://", ignoreCase = true)) {
                val uri = Uri.parse(trimmed)
                val ipParam = uri.getQueryParameter("ip")
                val portParam = uri.getQueryParameter("port")?.toIntOrNull()
                val httpParam = uri.getQueryParameter("http")?.toIntOrNull() ?: 8080
                val socksParam = uri.getQueryParameter("socks5")?.toIntOrNull() ?: 1080

                if (!ipParam.isNullOrBlank() && portParam != null && portParam in 1..65535) {
                    return QrPayload(
                        ip = ipParam,
                        port = portParam,
                        httpPort = httpParam,
                        socks5Port = socksParam
                    )
                }

                // Or netion://192.168.43.1:1080
                val host = uri.host
                val port = uri.port
                if (!host.isNullOrBlank() && port in 1..65535) {
                    return QrPayload(ip = host, port = port)
                }
            }

            // Check standard URL: http://192.168.43.1:8080 or socks5://...
            if (trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) ||
                trimmed.startsWith("socks5://", ignoreCase = true)
            ) {
                val uri = Uri.parse(trimmed)
                val host = uri.host
                val port = uri.port
                if (!host.isNullOrBlank() && port in 1..65535) {
                    return QrPayload(ip = host, port = port)
                }
            }

            // Check raw IP:PORT (e.g. 192.168.43.1:1080)
            val parts = trimmed.split(":")
            if (parts.size == 2) {
                val ip = parts[0].trim()
                val port = parts[1].trim().toIntOrNull()
                if (isValidIp(ip) && port != null && port in 1..65535) {
                    return QrPayload(ip = ip, port = port)
                }
            }
        } catch (_: Exception) {}

        return null
    }

    private fun isValidIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        return parts.all {
            val num = it.toIntOrNull()
            num != null && num in 0..255
        }
    }
}
