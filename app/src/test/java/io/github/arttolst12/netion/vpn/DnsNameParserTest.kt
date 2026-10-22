package io.github.arttolst12.netion.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.random.Random

class DnsNameParserTest {
    @Test
    fun invalidOffsetsAndEmptyInputAreRejected() = withResolver { resolver ->
        assertNull(resolver.readDomainName(byteArrayOf(), 0))
        assertNull(resolver.readDomainName(byteArrayOf(0), -1))
        assertNull(resolver.readDomainName(byteArrayOf(0), 1))
        assertNull(resolver.readDomainName(byteArrayOf(0), Int.MAX_VALUE))
    }

    @Test
    fun missingRootTerminatorAndTruncatedLabelAreRejected() = withResolver { resolver ->
        assertNull(resolver.readDomainName(byteArrayOf(1, 'a'.code.toByte()), 0))
        assertNull(resolver.readDomainName(byteArrayOf(2, 'a'.code.toByte()), 0))
        assertNull(resolver.readDomainName(byteArrayOf(0xC0.toByte()), 0))
    }

    @Test
    fun reservedLabelPrefixesAreNotAcceptedAsLongLabels() = withResolver { resolver ->
        for (length in listOf(64, 65, 127, 128, 191)) {
            val name = byteArrayOf(length.toByte()) + ByteArray(length) { 'a'.code.toByte() } + byteArrayOf(0)
            assertNull("reserved label prefix: $length", resolver.readDomainName(name, 0))
            assertNull("whole query must reject invalid label", resolver.parseDnsPacket(query(name)))
        }
    }

    @Test
    fun maximumLabelAndNameLengthsAreEnforced() = withResolver { resolver ->
        val maximum = name(63, 63, 63, 61)
        assertEquals(255, maximum.size)
        assertEquals(255, resolver.readDomainName(maximum, 0)!!.second)
        assertNotNull(resolver.parseDnsPacket(query(maximum)))
        assertNull(resolver.readDomainName(name(63, 63, 63, 62), 0))
        assertNull(resolver.parseDnsPacket(query(name(63, 63, 63, 62))))
    }

    @Test
    fun compressedNameUsesExpandedLengthLimit() = withResolver { resolver ->
        val suffix = name(63, 63)
        val prefix = name(63, 63).dropLast(1).toByteArray()
        val data = suffix + prefix + byteArrayOf(0xC0.toByte(), 0)
        assertNull(resolver.readDomainName(data, suffix.size))
    }

    @Test
    fun validBackwardCompressionPreservesConsumedLength() = withResolver { resolver ->
        val suffix = byteArrayOf(3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0)
        val data = suffix + byteArrayOf(3, 'W'.code.toByte(), 'W'.code.toByte(), 'W'.code.toByte(), 0xC0.toByte(), 0)
        assertEquals("www.com" to 6, resolver.readDomainName(data, suffix.size))
        assertEquals("com" to 5, resolver.readDomainName(data, 0))
        assertEquals("" to 1, resolver.readDomainName(byteArrayOf(0), 0))
    }

    @Test
    fun forwardSelfAndOutOfBoundsPointersAreRejected() = withResolver { resolver ->
        assertNull(resolver.readDomainName(byteArrayOf(0xC0.toByte(), 2, 0), 0))
        assertNull(resolver.readDomainName(byteArrayOf(0xC0.toByte(), 0), 0))
        assertNull(resolver.readDomainName(byteArrayOf(0xFF.toByte(), 0xFF.toByte()), 0))
    }

    @Test
    fun malformedNamesDoNotEscapeThroughQueryPrevalidation() = withResolver { resolver ->
        val missingRoot = byteArrayOf(1, 'a'.code.toByte())
        assertNull(resolver.parseDnsPacket(query(missingRoot)))
        assertNull(resolver.buildServFailResponse(query(name(63, 63, 63, 62))))
    }

    @Test
    fun seededMalformedInputNeverThrowsOrReturnsOutOfBoundsLength() = withResolver { resolver ->
        val random = Random(27419)
        repeat(1000) {
            val bytes = random.nextBytes(random.nextInt(0, 512))
            resolver.parseDnsPacket(bytes)
            val start = random.nextInt(-1, bytes.size + 2)
            val result = resolver.readDomainName(bytes, start)
            if (result != null) {
                assertTrue(start >= 0)
                assertTrue(result.second > 0)
                assertTrue(start + result.second <= bytes.size)
            }
        }
    }

    private fun query(name: ByteArray): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    ) + name + byteArrayOf(0, 1, 0, 1)

    private fun name(vararg lengths: Int): ByteArray {
        val output = ByteArrayOutputStream()
        for (length in lengths) {
            output.write(length)
            output.write(ByteArray(length) { 'a'.code.toByte() })
        }
        output.write(0)
        return output.toByteArray()
    }

    private fun withResolver(block: (DnsResolver) -> Unit) {
        val resolver = DnsResolver()
        try {
            block(resolver)
        } finally {
            resolver.close()
        }
    }
}
