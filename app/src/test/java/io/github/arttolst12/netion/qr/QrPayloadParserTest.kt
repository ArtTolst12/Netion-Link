package io.github.arttolst12.netion.qr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadParserTest {

    @Test
    fun netionUriRoundTripsExplicitProtocolOnCustomPort() {
        val uri = QrCodeManager.buildConnectionUri(
            ip = "192.168.43.1",
            selectedPort = 3128,
            httpPort = 3128,
            socks5Port = 1090,
            protocol = ProxyProtocol.HTTP,
        )

        val payload = QrPayloadParser.parse(uri)
        assertEquals("192.168.43.1", payload?.ip)
        assertEquals(3128, payload?.port)
        assertEquals(3128, payload?.httpPort)
        assertEquals(1090, payload?.socks5Port)
        assertEquals(ProxyProtocol.HTTP, payload?.protocol)
    }

    @Test
    fun legacyNetionMetadataInfersProtocolFromConfiguredPorts() {
        val socksPayload = QrPayloadParser.parse(
            "netion://connect?ip=192.168.43.1&port=1090&http=3128&socks5=1090"
        )
        assertEquals(ProxyProtocol.SOCKS5, socksPayload?.protocol)

        val httpPayload = QrPayloadParser.parse(
            "netion://connect?ip=192.168.43.1&port=3128&http=3128&socks5=1090"
        )
        assertEquals(ProxyProtocol.HTTP, httpPayload?.protocol)
    }

    @Test
    fun knownLegacyFormatsPreserveTheirSchemeProtocol() {
        assertEquals(
            ProxyProtocol.HTTP,
            QrPayloadParser.parse("https://192.168.43.1:8443")?.protocol,
        )
        assertEquals(
            ProxyProtocol.SOCKS5,
            QrPayloadParser.parse("socks5://192.168.43.1:1090")?.protocol,
        )
        assertEquals(
            ProxyProtocol.SOCKS5,
            QrPayloadParser.parse("192.168.43.1:1080")?.protocol,
        )
        assertEquals(
            ProxyProtocol.HTTP,
            QrPayloadParser.parse("192.168.43.1:3128")?.protocol,
        )
    }

    @Test
    fun arbitrarySchemesHostsAndInvalidEndpointsAreRejected() {
        listOf(
            "ftp://192.168.43.1:21",
            "https://example.com:443",
            "netion://example.com:1080",
            "netion://connect?ip=example.com&port=1080",
            "netion://connect?ip=192.168.43.1&port=0",
            "192.168.43.1",
            "192.168.43.1:65536",
            "hello world",
        ).forEach { value -> assertNull(value, QrPayloadParser.parse(value)) }
    }

    @Test
    fun builderRejectsBlankHostAndInvalidPorts() {
        assertFails { QrCodeManager.buildConnectionUri("", 8080, 8080, 1080) }
        assertFails { QrCodeManager.buildConnectionUri("localhost", 8080, 8080, 1080) }
        assertFails { QrCodeManager.buildConnectionUri("192.168.43.1", 0, 8080, 1080) }
        assertTrue(QrPayloadParser.parse("netion://192.168.43.1:1080") != null)
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
