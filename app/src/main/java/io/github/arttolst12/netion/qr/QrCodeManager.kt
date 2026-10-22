package io.github.arttolst12.netion.qr

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.EnumMap

enum class ProxyProtocol {
    HTTP,
    SOCKS5;

    companion object {
        fun fromWireValue(value: String?): ProxyProtocol? = when (value?.trim()?.uppercase()) {
            "HTTP" -> HTTP
            "SOCKS5", "SOCKS" -> SOCKS5
            else -> null
        }

        fun fromPort(port: Int): ProxyProtocol = if (port == 1080) SOCKS5 else HTTP
    }
}

data class QrPayload(
    val ip: String,
    val port: Int,
    val httpPort: Int = 8080,
    val socks5Port: Int = 1080,
    /** Optional for source compatibility with existing callers and legacy QR codes. */
    val protocol: ProxyProtocol = when {
        port == socks5Port && port != httpPort -> ProxyProtocol.SOCKS5
        port == httpPort && port != socks5Port -> ProxyProtocol.HTTP
        else -> ProxyProtocol.fromPort(port)
    },
)

/** Pure parser used by [QrCodeManager] and JVM tests without Android URI stubs. */
object QrPayloadParser {

    /**
     * Accepts only explicit Netion connection URIs, legacy HTTP/SOCKS5 URLs, and
     * raw dotted-IPv4 host:port values. Arbitrary schemes and hosts are rejected.
     */
    fun parse(scannedText: String): QrPayload? {
        val text = scannedText.trim()
        if (text.isEmpty()) return null

        return parseNetionUri(text) ?: parseLegacyUrl(text) ?: parseRawIpv4Endpoint(text)
    }

    private fun parseNetionUri(text: String): QrPayload? {
        val uri = parseUri(text) ?: return null
        if (!uri.scheme.equals("netion", ignoreCase = true)) return null

        val query = parseQuery(uri.rawQuery)
        val queryIp = query["ip"]?.trim().orEmpty()
        val queryPort = parsePort(query["port"])
        if (queryIp.isNotEmpty() || queryPort != null) {
            if (!isValidIpv4(queryIp) || queryPort == null) return null
            val httpPort = parsePort(query["http"]) ?: 8080
            val socks5Port = parsePort(query["socks5"]) ?: 1080
            val protocol = ProxyProtocol.fromWireValue(query["protocol"])
                ?: inferLegacyProtocol(queryPort, httpPort, socks5Port)
            return QrPayload(queryIp, queryPort, httpPort, socks5Port, protocol)
        }

        // Legacy netion://192.168.43.1:1080 form. URI host is only accepted when it
        // is a dotted IPv4 address; arbitrary netion hosts are not configurations.
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 }
        if (!isValidIpv4(host) || port == null) return null
        return QrPayload(ip = host, port = port, protocol = ProxyProtocol.fromPort(port))
    }

    private fun parseLegacyUrl(text: String): QrPayload? {
        val uri = parseUri(text) ?: return null
        val protocol = when {
            uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true) -> ProxyProtocol.HTTP
            uri.scheme.equals("socks5", ignoreCase = true) || uri.scheme.equals("socks", ignoreCase = true) -> ProxyProtocol.SOCKS5
            else -> return null
        }
        val host = uri.host ?: return null
        val port = uri.port.takeIf { it in 1..65535 }
        if (!isValidIpv4(host) || port == null) return null
        return QrPayload(ip = host, port = port, protocol = protocol)
    }

    private fun parseRawIpv4Endpoint(text: String): QrPayload? {
        // Raw scanner payloads are deliberately limited to dotted IPv4:port.
        if (text.any { it.isWhitespace() } || text.count { it == ':' } != 1) return null
        val separator = text.lastIndexOf(':')
        val host = text.substring(0, separator)
        val port = parsePort(text.substring(separator + 1))
        if (!isValidIpv4(host) || port == null) return null
        return QrPayload(ip = host, port = port, protocol = ProxyProtocol.fromPort(port))
    }

    private fun parseUri(text: String): URI? = try {
        URI(text)
    } catch (_: Exception) {
        null
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return buildMap {
            rawQuery.split('&').forEach { component ->
                val delimiter = component.indexOf('=')
                val rawName = if (delimiter < 0) component else component.substring(0, delimiter)
                val rawValue = if (delimiter < 0) "" else component.substring(delimiter + 1)
                val name = decode(rawName).lowercase()
                if (name !in this) put(name, decode(rawValue))
            }
        }
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        ""
    }

    private fun parsePort(value: String?): Int? = value?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }

    private fun inferLegacyProtocol(port: Int, httpPort: Int, socks5Port: Int): ProxyProtocol = when {
        port == socks5Port && port != httpPort -> ProxyProtocol.SOCKS5
        port == httpPort && port != socks5Port -> ProxyProtocol.HTTP
        else -> ProxyProtocol.fromPort(port)
    }

    private fun isValidIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.all { it in '0'..'9' } && part.toIntOrNull() in 0..255
        }
    }
}

object QrCodeManager {

    /**
     * Creates a Netion connection URI. Protocol is encoded explicitly because custom
     * HTTP/SOCKS5 ports must not be reclassified from their port number when scanned.
     */
    fun buildConnectionUri(
        ip: String,
        selectedPort: Int,
        httpPort: Int,
        socks5Port: Int,
        protocol: ProxyProtocol = if (selectedPort == socks5Port && selectedPort != httpPort) {
            ProxyProtocol.SOCKS5
        } else {
            ProxyProtocol.HTTP
        },
    ): String {
        require(isValidQrHost(ip)) { "A non-empty dotted IPv4 address is required" }
        require(selectedPort in 1..65535) { "selectedPort must be in 1..65535" }
        require(httpPort in 1..65535) { "httpPort must be in 1..65535" }
        require(socks5Port in 1..65535) { "socks5Port must be in 1..65535" }
        return buildString {
            append("netion://connect?ip=")
            append(URLEncoder.encode(ip, StandardCharsets.UTF_8.name()))
            append("&port=").append(selectedPort)
            append("&http=").append(httpPort)
            append("&socks5=").append(socks5Port)
            append("&protocol=").append(protocol.name)
        }
    }

    /** Generates a QR Code Bitmap from the given content string. */
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

    /** Parses supported Netion and legacy connection QR payloads only. */
    fun parseQrString(scannedText: String): QrPayload? = QrPayloadParser.parse(scannedText)

    private fun isValidQrHost(host: String): Boolean = QrPayloadParser.parse("$host:1") != null
}
