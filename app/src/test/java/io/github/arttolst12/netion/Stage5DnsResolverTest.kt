package io.github.arttolst12.netion

import android.net.VpnService
import io.github.arttolst12.netion.vpn.DnsResolver
import io.github.arttolst12.netion.vpn.PacketUtils
import io.github.arttolst12.netion.vpn.TunWriter
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stage 5 Unit Tests: DNS Forwarder, Strict Per-RR TTL, RFC 2308 Negative Caching,
 * EDNS Cache Bypass, Queue Saturated SERVFAIL, Source Validation, and Fallback Policy.
 */
class Stage5DnsResolverTest {

    private fun createDummyTunWriter(sink: ByteArrayOutputStream): TunWriter {
        return TunWriter(sink)
    }

    /**
     * Builds a standard RFC 1035 DNS Query for testing.
     */
    private fun buildStandardQuery(
        id: Int,
        domain: String,
        type: Int = DnsResolver.TYPE_A,
        classCode: Int = DnsResolver.CLASS_IN,
        withOpt: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header
        out.write(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
        out.write(byteArrayOf(0x01, 0x00)) // Standard query, RD=1
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT = 1
        out.write(byteArrayOf(0x00, 0x00)) // ANCOUNT = 0
        out.write(byteArrayOf(0x00, 0x00)) // NSCOUNT = 0
        out.write(byteArrayOf(0x00, if (withOpt) 0x01 else 0x00)) // ARCOUNT

        // QNAME
        for (label in domain.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0) // End of domain

        // QTYPE & QCLASS
        out.write(byteArrayOf((type shr 8).toByte(), (type and 0xFF).toByte()))
        out.write(byteArrayOf((classCode shr 8).toByte(), (classCode and 0xFF).toByte()))

        // Optional EDNS OPT RR
        if (withOpt) {
            out.write(0) // Root domain
            out.write(byteArrayOf(0x00, 0x29)) // TYPE = 41 (OPT)
            out.write(byteArrayOf(0x10, 0x00)) // UDP payload size = 4096
            out.write(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x00)) // Extended RCODE & DO=1 (DNSSEC OK)
            out.write(byteArrayOf(0x00, 0x00)) // RDLENGTH = 0
        }

        return out.toByteArray()
    }

    /**
     * Builds a standard RFC 1035 DNS Response for testing.
     */
    private fun buildStandardResponse(
        id: Int,
        domain: String,
        qType: Int = DnsResolver.TYPE_A,
        rcode: Int = DnsResolver.RCODE_NOERROR,
        answerTtls: List<Long> = emptyList(),
        soaTtlAndMin: Pair<Long, Long>? = null,
        authorityNsTtls: List<Long> = emptyList(),
        additionalTtls: List<Long> = emptyList()
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header
        out.write(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
        val flags = 0x8180 or (rcode and 0x0F) // QR=1, RD=1, RA=1
        out.write(byteArrayOf((flags shr 8).toByte(), (flags and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT = 1
        out.write(byteArrayOf(0x00, answerTtls.size.toByte())) // ANCOUNT
        val nsCount = (if (soaTtlAndMin != null) 1 else 0) + authorityNsTtls.size
        out.write(byteArrayOf(0x00, nsCount.toByte())) // NSCOUNT
        out.write(byteArrayOf(0x00, additionalTtls.size.toByte())) // ARCOUNT

        // Question
        for (label in domain.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf((qType shr 8).toByte(), (qType and 0xFF).toByte()))
        out.write(byteArrayOf(0x00, 0x01)) // CLASS IN

        // Answers
        for (ttl in answerTtls) {
            // Pointer to QNAME at offset 12
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            out.write(byteArrayOf((qType shr 8).toByte(), (qType and 0xFF).toByte()))
            out.write(byteArrayOf(0x00, 0x01)) // CLASS IN
            // 4-byte TTL
            out.write(byteArrayOf(
                (ttl shr 24).toByte(),
                (ttl shr 16).toByte(),
                (ttl shr 8).toByte(),
                (ttl and 0xFF).toByte()
            ))
            // RDLENGTH = 4 (IPv4: 1.2.3.4)
            out.write(byteArrayOf(0x00, 0x04))
            out.write(byteArrayOf(1, 2, 3, 4))
        }

        // Authority (SOA for negative responses)
        if (soaTtlAndMin != null) {
            val (soaTtl, soaMin) = soaTtlAndMin
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            out.write(byteArrayOf(0x00, 0x06)) // TYPE = SOA
            out.write(byteArrayOf(0x00, 0x01)) // CLASS IN
            out.write(byteArrayOf(
                (soaTtl shr 24).toByte(),
                (soaTtl shr 16).toByte(),
                (soaTtl shr 8).toByte(),
                (soaTtl and 0xFF).toByte()
            ))
            val rdata = ByteArrayOutputStream()
            rdata.write(0) // mname = "."
            rdata.write(0) // rname = "."
            rdata.write(byteArrayOf(0, 0, 0, 1)) // serial
            rdata.write(byteArrayOf(0, 0, 0x0E, 0x10)) // refresh = 3600
            rdata.write(byteArrayOf(0, 0, 0x02, 0x58)) // retry = 600
            rdata.write(byteArrayOf(0, 0x01, 0x51, 0x80.toByte())) // expire = 86400
            rdata.write(byteArrayOf(
                (soaMin shr 24).toByte(),
                (soaMin shr 16).toByte(),
                (soaMin shr 8).toByte(),
                (soaMin and 0xFF).toByte()
            ))
            val rbytes = rdata.toByteArray()
            out.write(byteArrayOf((rbytes.size shr 8).toByte(), (rbytes.size and 0xFF).toByte()))
            out.write(rbytes)
        }

        // Additional NS authorities
        for (ttl in authorityNsTtls) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            out.write(byteArrayOf(0x00, 0x02)) // TYPE = NS
            out.write(byteArrayOf(0x00, 0x01)) // CLASS IN
            out.write(byteArrayOf(
                (ttl shr 24).toByte(),
                (ttl shr 16).toByte(),
                (ttl shr 8).toByte(),
                (ttl and 0xFF).toByte()
            ))
            out.write(byteArrayOf(0x00, 0x01)) // RDLENGTH = 1
            out.write(0) // root "."
        }

        // Additional records
        for (ttl in additionalTtls) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C))
            out.write(byteArrayOf(0x00, 0x01)) // TYPE = A
            out.write(byteArrayOf(0x00, 0x01)) // CLASS IN
            out.write(byteArrayOf(
                (ttl shr 24).toByte(),
                (ttl shr 16).toByte(),
                (ttl shr 8).toByte(),
                (ttl and 0xFF).toByte()
            ))
            out.write(byteArrayOf(0x00, 0x04)) // RDLENGTH = 4
            out.write(byteArrayOf(8, 8, 8, 8))
        }

        return out.toByteArray()
    }

    // ─────────────────────────────────────────────────────────────
    // 1. Strict Per-RR TTL & Expiration Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testRecordTtlTrackedIndividuallyAndExpiresWhenAnyRecordReachesZero() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x1234, "example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!
        // Response with 2 answers: TTL 100s and TTL 10s
        val response = buildStandardResponse(0x1234, "example.com", answerTtls = listOf(100L, 10L))
        val parsedResponse = resolver.parseDnsPacket(response)!!

        val key = DnsResolver.DnsCacheKey("example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        val now = System.currentTimeMillis()

        // Cache entry
        val offsets = listOf(
            DnsResolver.RecordTtlOffset(parsedResponse.answers[0].ttlOffset, 100L),
            DnsResolver.RecordTtlOffset(parsedResponse.answers[1].ttlOffset, 10L)
        )
        resolver.cache.put(key, DnsResolver.DnsCacheEntry(response, now, offsets, isNegative = false, negativeTtlSeconds = 0L))

        // 1. At now + 4s: both records valid. Dynamic TTLs adjusted to 96s and 6s
        val hit1 = resolver.cache.get(key, 0x5678, now + 4_000L)
        assertNotNull("Cache hit expected before record expires", hit1)
        val parsedHit1 = resolver.parseDnsPacket(hit1!!)!!
        assertEquals("Transaction ID must be rewritten to current query ID", 0x5678, parsedHit1.id)
        assertEquals("First answer TTL must be decremented by 4s", 96L, parsedHit1.answers[0].ttl)
        assertEquals("Second answer TTL must be decremented by 4s", 6L, parsedHit1.answers[1].ttl)

        // 2. At now + 10s: second record TTL has expired (reaches 0). Must return null and evict!
        val hit2 = resolver.cache.get(key, 0x9999, now + 10_000L)
        assertNull("Cache must expire and return null as soon as any RR reaches 0", hit2)
        assertEquals("Expired entry must be evicted from cache", 0, resolver.cache.size())
    }

    @Test
    fun testZeroTtlRecordsNeverCached() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x1000, "live.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // Response with TTL = 0
        val response = buildStandardResponse(0x1000, "live.example.com", answerTtls = listOf(0L))
        val parsedResponse = resolver.parseDnsPacket(response)!!

        val key = DnsResolver.DnsCacheKey("live.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        val now = System.currentTimeMillis()
        val offsets = listOf(DnsResolver.RecordTtlOffset(parsedResponse.answers[0].ttlOffset, 0L))
        val entry = DnsResolver.DnsCacheEntry(response, now, offsets, isNegative = false, negativeTtlSeconds = 0L)

        // Attempting to evaluate entry with TTL 0 must immediately return null
        assertNull("Entries with TTL 0 must never be served from cache", entry.getValidResponse(0x2000, now))
    }

    @Test
    fun testZeroTtlInAuthorityOrAdditionalPreventsCaching() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x1010, "zerottl.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // 1. Response with valid Answer TTL 60, but Authority NS record with TTL 0
        val respZeroAuth = buildStandardResponse(
            0x1010, "zerottl.example.com",
            answerTtls = listOf(60L),
            authorityNsTtls = listOf(0L)
        )
        val parsedRespZeroAuth = resolver.parseDnsPacket(respZeroAuth)!!
        val keyAuth = DnsResolver.DnsCacheKey("zerottl.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        val dummySink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(dummySink)
        val resResolver = DnsResolver(tunWriter = writer)
        val acceptedAuth = resResolver.validateAndProcessResponse(
            byteArrayOf(10, 0, 0, 2), byteArrayOf(8, 8, 8, 8),
            12345, 53, query, parsedQuery, respZeroAuth
        )
        assertTrue("Packet processed for client", acceptedAuth)
        assertNull("Response with TTL=0 in Authority section must not be cached", resResolver.cache.get(keyAuth, 0x1010))

        // 2. Response with valid Answer TTL 60, but Additional record with TTL 0
        val respZeroAdd = buildStandardResponse(
            0x1010, "zerottl.example.com",
            answerTtls = listOf(60L),
            additionalTtls = listOf(0L)
        )
        val parsedRespZeroAdd = resolver.parseDnsPacket(respZeroAdd)!!
        val acceptedAdd = resResolver.validateAndProcessResponse(
            byteArrayOf(10, 0, 0, 2), byteArrayOf(8, 8, 8, 8),
            12345, 53, query, parsedQuery, respZeroAdd
        )
        assertTrue("Packet processed for client", acceptedAdd)
        assertNull("Response with TTL=0 in Additional section must not be cached", resResolver.cache.get(keyAuth, 0x1010))
    }

    // ─────────────────────────────────────────────────────────────
    // 2. RFC 2308 Negative Caching Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testNegativeCachingPerRfc2308WithSoa() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x3333, "nonexistent.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // NXDOMAIN with SOA: SOA TTL = 600, MINIMUM = 120 -> effective negative TTL = min(600, 120, 300) = 120s
        val nxResponse = buildStandardResponse(
            0x3333,
            "nonexistent.example.com",
            rcode = DnsResolver.RCODE_NXDOMAIN,
            soaTtlAndMin = Pair(600L, 120L)
        )
        val parsedNx = resolver.parseDnsPacket(nxResponse)!!

        val key = DnsResolver.DnsCacheKey("nonexistent.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        val now = System.currentTimeMillis()

        val soaRecord = parsedNx.authorities.first { it.type == DnsResolver.TYPE_SOA }
        val soaMin = resolver.parseSoaMinimumTtl(nxResponse, soaRecord)!!
        assertEquals(120L, soaMin)
        val effectiveNegativeTtl = minOf(soaRecord.ttl, soaMin, DnsResolver.MAX_NEGATIVE_TTL_SEC)
        assertEquals(120L, effectiveNegativeTtl)

        // P2: SOA TTL normalized to effectiveNegativeTtl
        val ttlOffsets = listOf(DnsResolver.RecordTtlOffset(soaRecord.ttlOffset, effectiveNegativeTtl))
        resolver.cache.put(key, DnsResolver.DnsCacheEntry(nxResponse, now, ttlOffsets, isNegative = true, negativeTtlSeconds = effectiveNegativeTtl))

        // Hit at now + 50s: still valid
        val cachedNx = resolver.cache.get(key, 0x4444, now + 50_000L)
        assertNotNull("Negative response must be cached when SOA is present", cachedNx)
        val parsedCachedNx = resolver.parseDnsPacket(cachedNx!!)!!
        assertEquals(0x4444, parsedCachedNx.id)
        assertEquals(DnsResolver.RCODE_NXDOMAIN, parsedCachedNx.rcode)
        assertEquals("SOA record TTL must be normalized and decremented (120 - 50 = 70)", 70L, parsedCachedNx.authorities[0].ttl)

        // At now + 121s: negative TTL expired
        val expiredNx = resolver.cache.get(key, 0x5555, now + 121_000L)
        assertNull("Negative cache entry must expire after effective negative TTL", expiredNx)
    }

    @Test
    fun testNegativeResponseWithoutSoaNeverCached() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x7777, "nosoa.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // NXDOMAIN without SOA record
        val nxNoSoa = buildStandardResponse(0x7777, "nosoa.example.com", rcode = DnsResolver.RCODE_NXDOMAIN, soaTtlAndMin = null)
        val parsedNxNoSoa = resolver.parseDnsPacket(nxNoSoa)!!

        val key = DnsResolver.DnsCacheKey("nosoa.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)

        // authorities is empty, so SOA is null
        assertTrue("No SOA record in authorities", parsedNxNoSoa.authorities.isEmpty())
        assertEquals(0, resolver.cache.size())
    }

    // ─────────────────────────────────────────────────────────────
    // 3. EDNS / DNSSEC Cache Bypass Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testEdnsDnssecBypassesCache() {
        val resolver = DnsResolver()
        // Query with EDNS(0) OPT RR (type 41) and DO bit set
        val ednsQuery = buildStandardQuery(0x8888, "dnssec.example.com", withOpt = true)
        val parsed = resolver.parseDnsPacket(ednsQuery)!!

        assertTrue("Query with OPT RR must be flagged as isEdnsOrDnssec", parsed.isEdnsOrDnssec)
    }

    // ─────────────────────────────────────────────────────────────
    // 4. Queue Overload & SERVFAIL Synthesis Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testBuildServFailPreservesTransactionIdAndQuestion() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0xABCD, "broken.domain.com", type = DnsResolver.TYPE_AAAA)
        val servFail = resolver.buildServFailResponse(query)

        assertNotNull("buildServFailResponse must return valid response", servFail)
        val parsed = resolver.parseDnsPacket(servFail!!)!!

        assertEquals("Transaction ID must match query", 0xABCD, parsed.id)
        assertTrue("Response bit QR must be 1", parsed.isResponse)
        assertEquals("RCODE must be SERVFAIL (2)", DnsResolver.RCODE_SERVFAIL, parsed.rcode)
        assertEquals("QDCOUNT must be 1", 1, parsed.questions.size)
        assertEquals("Question name must match query", "broken.domain.com", parsed.questions[0].name)
        assertEquals("Question type must match query", DnsResolver.TYPE_AAAA, parsed.questions[0].type)
        assertEquals("Question class must match query", DnsResolver.CLASS_IN, parsed.questions[0].classCode)
        assertEquals("ANCOUNT must be 0", 0, parsed.answers.size)
    }

    @Test
    fun testQueueOverloadDropsGarbageQueryWithoutReflection() {
        val resolver = DnsResolver()
        val garbage = byteArrayOf(0x01, 0x02, 0x03) // Less than 12 bytes
        val servFail = resolver.buildServFailResponse(garbage)
        assertNull("Corrupt/garbage query must not produce SERVFAIL reflection", servFail)
    }

    // ─────────────────────────────────────────────────────────────
    // 5. Source Address & Transaction ID Validation Tests
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testMismatchedQuestionOrTransactionIdRejected() {
        val dummySink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(dummySink)
        val resolver = DnsResolver(tunWriter = writer)
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x1111, "legit.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // 1. Response with mismatched Transaction ID 0x2222
        val wrongIdResponse = buildStandardResponse(0x2222, "legit.com", answerTtls = listOf(60L))
        val acceptedWrongId = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, wrongIdResponse
        )
        assertFalse("Mismatched transaction ID must be rejected by validateAndProcessResponse", acceptedWrongId)
        val keyWrongId = DnsResolver.DnsCacheKey("legit.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        assertNull("Rejected wrong ID response must not be cached", resolver.cache.get(keyWrongId, 0x1111))

        // 2. Response with mismatched Question name "spoofed.com"
        val spoofedQResponse = buildStandardResponse(0x1111, "spoofed.com", answerTtls = listOf(60L))
        val acceptedSpoofed = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, spoofedQResponse
        )
        assertFalse("Mismatched question name must be rejected by validateAndProcessResponse", acceptedSpoofed)
        assertNull("Rejected spoofed question response must not be cached", resolver.cache.get(keyWrongId, 0x1111))

        // 3. Response with matching ID and Question
        val validResponse = buildStandardResponse(0x1111, "legit.com", answerTtls = listOf(60L))
        val acceptedValid = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, validResponse
        )
        assertTrue("Matching response must be accepted by validateAndProcessResponse", acceptedValid)
        assertNotNull("Accepted valid response must be cached", resolver.cache.get(keyWrongId, 0x1111))
        resolver.close()
    }

    // ─────────────────────────────────────────────────────────────
    // 6. Pointer Loop Prevention in Domain Name Reader
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testDomainNameCompressionPointerLoopDetected() {
        val resolver = DnsResolver()
        // Create cyclic pointer: offset 12 points to offset 12 (0xC0, 0x0C)
        val loopPacket = ByteArray(20)
        loopPacket[12] = 0xC0.toByte()
        loopPacket[13] = 0x0C.toByte()

        val res = resolver.readDomainName(loopPacket, 12)
        assertNull("Cyclic compression pointer loop must be detected and return null", res)
    }

    // ─────────────────────────────────────────────────────────────
    // 7. Fallback Policy Default Is Disabled & Proxy Fallback Guard
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testPublicFallbackDisabledByDefault() {
        val resolver = DnsResolver()
        // Constructor defaults enablePublicFallback to false
        val field = DnsResolver::class.java.getDeclaredField("enablePublicFallback")
        field.isAccessible = true
        val isFallbackEnabled = field.getBoolean(resolver)

        assertFalse("Direct public DNS fallback must be disabled by default", isFallbackEnabled)
        resolver.close()
    }

    @Test
    fun testProxyFailureDoesNotFallBackToDirectUdp() {
        // Start a mock UDP DNS server on a local port to detect if any direct UDP packets leak
        val directUdpReceived = AtomicBoolean(false)
        val udpServer = DatagramSocket(0)
        val testDnsPort = udpServer.localPort
        val listenerThread = Thread {
            try {
                udpServer.soTimeout = 1000
                val buf = ByteArray(512)
                val pkt = DatagramPacket(buf, buf.size)
                udpServer.receive(pkt)
                directUdpReceived.set(true)
            } catch (_: Exception) {}
        }
        listenerThread.start()

        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)

        // Find an unused TCP port to ensure proxy connect fails
        val testProxyPort = 59998
        val resolver = DnsResolver(
            tunWriter = writer,
            proxyIp = "127.0.0.1",
            proxyPort = testProxyPort,
            enablePublicFallback = false
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(127, 0, 0, 1)
        val query = buildStandardQuery(0x5432, "proxyfail.example.com")
        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, testDnsPort, 8 + query.size)

        resolver.handleQuery(ipHeader, udpHeader, query)

        // Wait for executor resolution task to finish
        listenerThread.join(1200)
        udpServer.close()

        // 1. Direct UDP socket must NOT have received any packet!
        assertFalse("Direct UDP must NOT be called when proxy is configured", directUdpReceived.get())

        // 2. Client must receive SERVFAIL response via TUN
        val writtenBytes = sink.toByteArray()
        assertTrue("TUN writer must have received a response packet", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!

        assertEquals("Transaction ID must match query", 0x5432, parsedDns.id)
        assertEquals("Response RCODE must be SERVFAIL (2) when proxy fails", DnsResolver.RCODE_SERVFAIL, parsedDns.rcode)
        resolver.close()
    }

    // ─────────────────────────────────────────────────────────────
    // 8. Protect Failure Aborts Query and Returns SERVFAIL
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testVpnServiceProtectCheck() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)

        // Simulate protect() returning false
        val resolver = DnsResolver(
            tunWriter = writer,
            proxyIp = "127.0.0.1",
            proxyPort = 59997,
            protectSocketFn = { false }
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x7654, "protectfail.example.com")

        // 1. Direct UDP resolution must return PROTECT_FAILED
        val udpResult = resolver.resolveViaDirectUdp(clientIp, dnsIp, 12345, 53, query, null)
        assertEquals("resolveViaDirectUdp must return PROTECT_FAILED when protect() fails", DnsResolver.ResolutionStatus.PROTECT_FAILED, udpResult)

        // 2. Proxy TCP resolution must return PROTECT_FAILED
        val tcpResult = resolver.resolveViaProxyTcp(clientIp, dnsIp, 12345, 53, query, null)
        assertEquals("resolveViaProxyTcp must return PROTECT_FAILED when protect() fails", DnsResolver.ResolutionStatus.PROTECT_FAILED, tcpResult)

        // 3. Responses sent to TUN must be SERVFAIL
        val writtenBytes = sink.toByteArray()
        assertTrue("SERVFAIL packet must be sent to TUN upon protect() failure", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!
        assertEquals("RCODE must be SERVFAIL", DnsResolver.RCODE_SERVFAIL, parsedDns.rcode)
        resolver.close()
    }

    @Test
    fun testProtectFailureWithPublicFallbackEnabledDoesNotAttemptFallback() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)

        // Enable fallback, but protect() fails
        val resolver = DnsResolver(
            tunWriter = writer,
            enablePublicFallback = true,
            protectSocketFn = { false }
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x9876, "nofallbackonprotect.example.com")
        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, 53, 8 + query.size)

        resolver.handleQuery(ipHeader, udpHeader, query)
        Thread.sleep(200)

        // Must have received exactly one response packet, which is SERVFAIL
        val writtenBytes = sink.toByteArray()
        assertTrue("Must send SERVFAIL on protect failure", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!
        assertEquals(0x9876, parsedDns.id)
        assertEquals(DnsResolver.RCODE_SERVFAIL, parsedDns.rcode)
        resolver.close()
    }

    @Test
    fun testPublicFallbackFailureSendsServFail() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val attemptedIps = mutableListOf<String>()

        // Direct mode with fallback enabled, using substituted transport where both primary and fallback fail
        val resolver = DnsResolver(
            tunWriter = writer,
            enablePublicFallback = true,
            udpTransportFn = { dnsIp, _, _ ->
                attemptedIps.add(PacketUtils.ipToString(dnsIp))
                null // simulate network failure / timeout
            }
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x4321, "fallbackfail.example.com")
        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, 53, 8 + query.size)

        // Invoke REAL handleQuery flow through the resolver
        resolver.handleQuery(ipHeader, udpHeader, query)
        Thread.sleep(200)

        // 1. Primary DNS (8.8.8.8) must be attempted first
        assertEquals("Both primary and fallback must be attempted", 2, attemptedIps.size)
        assertEquals("Primary DNS must be attempted first", "8.8.8.8", attemptedIps[0])
        // 2. Fallback DNS (1.1.1.1) must be attempted second
        assertEquals("Fallback DNS must be attempted second", "1.1.1.1", attemptedIps[1])

        // 3. Since both failed, a real SERVFAIL response must be written to TUN
        val writtenBytes = sink.toByteArray()
        assertTrue("TUN writer must have received a response packet", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!
        assertEquals("Transaction ID must match original query", 0x4321, parsedDns.id)
        assertEquals("Response RCODE must be SERVFAIL", DnsResolver.RCODE_SERVFAIL, parsedDns.rcode)

        resolver.close()
    }

    @Test
    fun testPublicFallbackSuccessSendsResponse() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val attemptedIps = mutableListOf<String>()

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x4322, "fallbackok.example.com")
        val fallbackResponse = buildStandardResponse(0x4322, "fallbackok.example.com", answerTtls = listOf(60L))

        val resolver = DnsResolver(
            tunWriter = writer,
            enablePublicFallback = true,
            udpTransportFn = { targetDnsIp, _, _ ->
                val ipStr = PacketUtils.ipToString(targetDnsIp)
                attemptedIps.add(ipStr)
                if (ipStr == "8.8.8.8") {
                    null // Primary fails
                } else {
                    fallbackResponse // Fallback (1.1.1.1) succeeds
                }
            }
        )

        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + query.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, 53, 8 + query.size)

        resolver.handleQuery(ipHeader, udpHeader, query)
        Thread.sleep(200)

        assertEquals("Both primary and fallback must be attempted", 2, attemptedIps.size)
        assertEquals("Primary DNS must be attempted first", "8.8.8.8", attemptedIps[0])
        assertEquals("Fallback DNS must be attempted second", "1.1.1.1", attemptedIps[1])

        val writtenBytes = sink.toByteArray()
        assertTrue("TUN writer must have received a response packet", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!
        assertEquals("Transaction ID must match query", 0x4322, parsedDns.id)
        assertEquals("Response RCODE must be NOERROR", DnsResolver.RCODE_NOERROR, parsedDns.rcode)
        assertEquals(1, parsedDns.answers.size)
        assertEquals(60L, parsedDns.answers[0].ttl)

        resolver.close()
    }

    @Test
    fun testNegativeCacheEvictsWhenAnyRecordExpires() {
        val resolver = DnsResolver()
        val query = buildStandardQuery(0x3344, "nxexpire.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // NXDOMAIN with SOA (TTL=300, MIN=120 -> effective 120s), but NS record with TTL=20s
        val nxResp = buildStandardResponse(
            0x3344,
            "nxexpire.example.com",
            rcode = DnsResolver.RCODE_NXDOMAIN,
            soaTtlAndMin = Pair(300L, 120L),
            authorityNsTtls = listOf(20L)
        )
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val ok = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, nxResp
        )
        assertTrue("Negative response should be accepted and cached", ok)

        val key = DnsResolver.DnsCacheKey("nxexpire.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        assertEquals("Negative response must be cached", 1, resolver.cache.size())

        val now = System.currentTimeMillis()

        // 1. At now + 10s: elapsed = 10s. SOA remaining = 110s, NS remaining = 10s.
        val hit1 = resolver.cache.get(key, 0x5566, now + 10_000L)
        assertNotNull("Negative response should be valid at t + 10s", hit1)
        val parsedHit1 = resolver.parseDnsPacket(hit1!!)!!
        assertEquals(0x5566, parsedHit1.id)
        assertEquals(DnsResolver.RCODE_NXDOMAIN, parsedHit1.rcode)
        assertEquals(110L, parsedHit1.authorities[0].ttl) // SOA decremented to 110
        assertEquals(10L, parsedHit1.authorities[1].ttl)  // NS decremented to 10

        // 2. At now + 21s: elapsed = 21s. NS record (original TTL 20s) has EXPIRED (20 - 21 <= 0)!
        // Entire negative cache entry must be evicted immediately and return null.
        val hit2 = resolver.cache.get(key, 0x7788, now + 21_000L)
        assertNull("Negative cache entry must be evicted as soon as ANY record expires", hit2)
        assertEquals("Expired negative cache entry must be evicted from cache", 0, resolver.cache.size())

        resolver.close()
    }

    @Test
    fun testNegativeResponseWithZeroTtlAuthorityOrAdditionalNotCached() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val resolver = DnsResolver(tunWriter = writer)
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x2468, "nxzerottl.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // NXDOMAIN with valid SOA (TTL=600, MIN=120), but an additional NS authority record with TTL=0
        val nxWithZeroNs = buildStandardResponse(
            0x2468,
            "nxzerottl.example.com",
            rcode = DnsResolver.RCODE_NXDOMAIN,
            soaTtlAndMin = Pair(600L, 120L),
            authorityNsTtls = listOf(0L)
        )
        val ok = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, nxWithZeroNs
        )
        assertTrue("Packet processed for client", ok)
        val key = DnsResolver.DnsCacheKey("nxzerottl.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        assertNull("Negative response with TTL=0 in Authority section must not be cached", resolver.cache.get(key, 0x2468))

        // NXDOMAIN with valid SOA, but an Additional record with TTL=0
        val nxWithZeroAdd = buildStandardResponse(
            0x2468,
            "nxzerottl.example.com",
            rcode = DnsResolver.RCODE_NXDOMAIN,
            soaTtlAndMin = Pair(600L, 120L),
            additionalTtls = listOf(0L)
        )
        val okAdd = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, nxWithZeroAdd
        )
        assertTrue("Packet processed for client", okAdd)
        assertNull("Negative response with TTL=0 in Additional section must not be cached", resolver.cache.get(key, 0x2468))
        resolver.close()
    }

    @Test
    fun testResponseWithQrZeroRejected() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val resolver = DnsResolver(tunWriter = writer)
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x7788, "queryasresponse.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)!!

        // Upstream sends back a packet with QR = 0 (isResponse == false)
        val fakeQueryAsResponse = buildStandardQuery(0x7788, "queryasresponse.example.com")
        val ok = resolver.validateAndProcessResponse(
            clientIp, dnsIp, 12345, 53, query, parsedQuery, fakeQueryAsResponse
        )
        assertFalse("Packet with QR=0 must be rejected by validateAndProcessResponse", ok)
        assertEquals("No packet should be written to TUN for rejected QR=0 packet", 0, sink.size())
        val key = DnsResolver.DnsCacheKey("queryasresponse.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
        assertNull("Rejected QR=0 packet must not be cached", resolver.cache.get(key, 0x7788))
        resolver.close()
    }

    // ─────────────────────────────────────────────────────────────
    // 9. Concurrent Resolution and LRU Cache Safety
    // ─────────────────────────────────────────────────────────────

    @Test
    fun testConcurrentCacheOperationsThreadSafe() {
        val resolver = DnsResolver()
        val numThreads = 16
        val iterations = 100
        val latch = CountDownLatch(numThreads)
        val errors = AtomicInteger(0)

        for (t in 0 until numThreads) {
            Thread {
                try {
                    for (i in 0 until iterations) {
                        // Keep one key per thread: put/get are separate operations, so a
                        // capacity-overflow workload can legitimately evict a key between them.
                        val domain = "host-$t.example.com"
                        val key = DnsResolver.DnsCacheKey(domain, DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
                        val resp = buildStandardResponse(i, domain, answerTtls = listOf(60L))
                        val parsed = resolver.parseDnsPacket(resp)!!
                        val entry = DnsResolver.DnsCacheEntry(
                            resp,
                            System.currentTimeMillis(),
                            listOf(DnsResolver.RecordTtlOffset(parsed.answers[0].ttlOffset, 60L)),
                            false,
                            0L
                        )
                        resolver.cache.put(key, entry)
                        val hit = resolver.cache.get(key, i, entry.cachedAtMs)
                        if (hit == null || !hit.contentEquals(resp)) {
                            errors.incrementAndGet()
                        }
                    }
                } catch (e: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("All concurrent worker threads must complete", latch.await(5, TimeUnit.SECONDS))
        assertEquals("No errors during concurrent cache access", 0, errors.get())
        assertEquals("All per-thread keys must survive without capacity eviction", numThreads, resolver.cache.size())
        resolver.close()
    }

    @Test
    fun testCacheCapacityEvictsLeastRecentlyUsedEntry() {
        val resolver = DnsResolver()
        try {
            val now = 1000L
            val keys = (0..DnsResolver.MAX_CACHE_ENTRIES).map {
                DnsResolver.DnsCacheKey("lru-$it.example.com", DnsResolver.TYPE_A, DnsResolver.CLASS_IN)
            }
            for (key in keys.take(DnsResolver.MAX_CACHE_ENTRIES)) {
                val response = buildStandardResponse(1, key.name, answerTtls = listOf(60L))
                val parsed = resolver.parseDnsPacket(response)!!
                resolver.cache.put(key, DnsResolver.DnsCacheEntry(
                    response, now,
                    listOf(DnsResolver.RecordTtlOffset(parsed.answers[0].ttlOffset, 60L)),
                    false, 0L
                ))
            }
            // Reading the first inserted entry must move it to the most-recently-used end.
            assertNotNull(resolver.cache.get(keys[0], 1, now))
            val extra = keys.last()
            val response = buildStandardResponse(2, extra.name, answerTtls = listOf(60L))
            val parsed = resolver.parseDnsPacket(response)!!
            resolver.cache.put(extra, DnsResolver.DnsCacheEntry(
                response, now,
                listOf(DnsResolver.RecordTtlOffset(parsed.answers[0].ttlOffset, 60L)),
                false, 0L
            ))
            assertEquals(DnsResolver.MAX_CACHE_ENTRIES, resolver.cache.size())
            assertNotNull("Recently read entry must survive", resolver.cache.get(keys[0], 1, now))
            assertNull("Least recently used entry must be evicted", resolver.cache.get(keys[1], 1, now))
            assertNotNull("New entry must be available", resolver.cache.get(extra, 2, now))
        } finally {
            resolver.close()
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 10. Malformed Query Drop Before Queueing & DNS-over-TCP >4096
    // ─────────────────────────────────────────────────────────────

    private fun buildLargeResponse(
        id: Int,
        domain: String,
        targetSize: Int,
        numAnswers: Int = 1,
        withOpt: Boolean = false
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // Header
        out.write(byteArrayOf((id shr 8).toByte(), (id and 0xFF).toByte()))
        out.write(byteArrayOf(0x81.toByte(), 0x80.toByte())) // QR=1, RD=1, RA=1, RCODE=0
        out.write(byteArrayOf(0x00, 0x01)) // QDCOUNT = 1
        out.write(byteArrayOf((numAnswers shr 8).toByte(), (numAnswers and 0xFF).toByte())) // ANCOUNT
        out.write(byteArrayOf(0x00, 0x00)) // NSCOUNT = 0
        out.write(byteArrayOf(0x00, if (withOpt) 1 else 0)) // ARCOUNT

        // Question
        for (label in domain.split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01)) // TYPE A
        out.write(byteArrayOf(0x00, 0x01)) // CLASS IN

        // Answers
        val optLen = if (withOpt) 11 else 0
        val baseSize = out.size() + optLen
        val budgetForAnswers = maxOf(numAnswers * 14, targetSize - baseSize)
        val perAnswerPayload = maxOf(4, (budgetForAnswers / numAnswers) - 10)

        for (i in 0 until numAnswers) {
            out.write(byteArrayOf(0xC0.toByte(), 0x0C)) // Pointer to QNAME at offset 12
            out.write(byteArrayOf(0x00, 0x10)) // TYPE TXT (16)
            out.write(byteArrayOf(0x00, 0x01)) // CLASS IN
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x3C)) // TTL = 60s
            out.write(byteArrayOf((perAnswerPayload shr 8).toByte(), (perAnswerPayload and 0xFF).toByte()))
            val rdata = ByteArray(perAnswerPayload)
            java.util.Arrays.fill(rdata, (0x41 + (i % 26)).toByte())
            out.write(rdata)
        }

        if (withOpt) {
            // OPT RR: name = 0 (root), type = 41 (OPT), class = 4096 (UDP payload size), ttl = 0, rdLength = 0
            out.write(0x00) // root name
            out.write(byteArrayOf(0x00, 0x29)) // TYPE = 41 (OPT)
            out.write(byteArrayOf(0x10, 0x00)) // CLASS = 4096 (payload size)
            out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00)) // TTL = 0 (extended RCODE/flags)
            out.write(byteArrayOf(0x00, 0x00)) // RDLENGTH = 0
        }

        return out.toByteArray()
    }

    @Test
    fun testMalformedQueryDroppedBeforeNetworkCall() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val networkAttempted = AtomicBoolean(false)

        val resolver = DnsResolver(
            tunWriter = writer,
            udpTransportFn = { _, _, _ ->
                networkAttempted.set(true)
                null
            }
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val garbageQuery = byteArrayOf(0x01, 0x02, 0x03, 0x04) // Malformed: size < 12 bytes
        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + garbageQuery.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, 53, 8 + garbageQuery.size)

        resolver.handleQuery(ipHeader, udpHeader, garbageQuery)
        Thread.sleep(100)

        assertFalse("Malformed query must be dropped immediately without making network calls", networkAttempted.get())
        assertEquals("No task should be queued in executor for malformed query", 0, resolver.executor.queue.size)
        assertEquals("No response packet should be written to TUN", 0, sink.size())

        resolver.close()
    }

    @Test
    fun testMultiQuestionQueryDroppedBeforeNetworkCall() {
        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val networkAttempted = AtomicBoolean(false)

        val resolver = DnsResolver(
            tunWriter = writer,
            udpTransportFn = { _, _, _ ->
                networkAttempted.set(true)
                null
            }
        )

        // Build query packet with QDCOUNT = 2 (unsupported multi-question format)
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x55, 0x66)) // ID
        out.write(byteArrayOf(0x01, 0x00)) // Flags: RD=1
        out.write(byteArrayOf(0x00, 0x02)) // QDCOUNT = 2
        out.write(byteArrayOf(0x00, 0x00)) // ANCOUNT = 0
        out.write(byteArrayOf(0x00, 0x00)) // NSCOUNT = 0
        out.write(byteArrayOf(0x00, 0x00)) // ARCOUNT = 0

        // Question 1
        for (label in "q1.example.com".split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))

        // Question 2
        for (label in "q2.example.com".split(".")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)
        out.write(byteArrayOf(0x00, 0x01, 0x00, 0x01))

        val multiQuestionQuery = out.toByteArray()
        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val ipHeader = PacketUtils.IpHeader(20, 20 + 8 + multiQuestionQuery.size, PacketUtils.PROTO_UDP, clientIp, dnsIp)
        val udpHeader = PacketUtils.UdpHeader(12345, 53, 8 + multiQuestionQuery.size)

        resolver.handleQuery(ipHeader, udpHeader, multiQuestionQuery)
        Thread.sleep(100)

        assertFalse("Unsupported multi-question query must be dropped before network call", networkAttempted.get())
        assertEquals("No task should be queued in executor for unsupported multi-question query", 0, resolver.executor.queue.size)
        assertEquals("No response packet should be written to TUN", 0, sink.size())

        resolver.close()
    }

    @Test
    fun testDnsOverTcpResponseLargerThan4096BytesAccepted() {
        val serverSocket = ServerSocket(0)
        val mockProxyPort = serverSocket.localPort

        val proxyThread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                // Read HTTP CONNECT
                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    sb.append(b.toChar())
                    if (sb.endsWith("\r\n\r\n")) break
                }

                // Send HTTP 200 OK
                output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()

                // Read 2-byte query length
                val qLenHi = input.read()
                val qLenLo = input.read()
                val qLen = (qLenHi shl 8) or qLenLo
                val qBytes = ByteArray(qLen)
                var read = 0
                while (read < qLen) {
                    val n = input.read(qBytes, read, qLen - read)
                    if (n == -1) break
                    read += n
                }

                val queryId = ((qBytes[0].toInt() and 0xFF) shl 8) or (qBytes[1].toInt() and 0xFF)
                val largeResponse = buildLargeResponse(queryId, "large.example.com", 5000)

                // Send 2-byte response length prefix (5000)
                output.write((largeResponse.size shr 8).toByte().toInt())
                output.write((largeResponse.size and 0xFF).toByte().toInt())
                output.write(largeResponse)
                output.flush()

                client.close()
            } catch (_: Exception) {}
        }
        proxyThread.start()

        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val resolver = DnsResolver(
            tunWriter = writer,
            proxyIp = "127.0.0.1",
            proxyPort = mockProxyPort
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        val query = buildStandardQuery(0x7654, "large.example.com")
        val status = resolver.resolveViaProxyTcp(clientIp, dnsIp, 12345, 53, query, null)

        proxyThread.join(2000)
        serverSocket.close()

        assertEquals("Large DNS-over-TCP response (>4096 bytes) must be successfully accepted from upstream", DnsResolver.ResolutionStatus.SUCCESS, status)
        val writtenBytes = sink.toByteArray()
        assertTrue("Response packet must be written to TUN", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!

        // Client query had no EDNS -> must adhere to RFC 1035 512-byte limit and TUN MTU 1400 limit
        assertTrue("DNS UDP response must not exceed 512-byte limit without EDNS", dnsPayload.size <= DnsResolver.DEFAULT_DNS_UDP_SIZE)
        assertTrue("Total IP packet must not exceed TUN MTU (1400 bytes)", writtenBytes.size <= DnsResolver.TUN_MTU)
        assertEquals(0x7654, parsedDns.id)
        assertEquals(DnsResolver.RCODE_NOERROR, parsedDns.rcode)
        assertTrue("Oversized response must have TC bit set to 1", parsedDns.isTruncated)
        assertEquals("Question name must be preserved in truncated response", "large.example.com", parsedDns.questions[0].name)

        resolver.close()
    }

    @Test
    fun testDnsOverTcpWithEdnsRespectsTunMtuAndSetsTc() {
        val serverSocket = ServerSocket(0)
        val mockProxyPort = serverSocket.localPort

        val proxyThread = Thread {
            try {
                val client = serverSocket.accept()
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val sb = StringBuilder()
                while (true) {
                    val b = input.read()
                    if (b == -1) break
                    sb.append(b.toChar())
                    if (sb.endsWith("\r\n\r\n")) break
                }
                output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()

                val qLenHi = input.read()
                val qLenLo = input.read()
                val qLen = (qLenHi shl 8) or qLenLo
                val qBytes = ByteArray(qLen)
                var read = 0
                while (read < qLen) {
                    val n = input.read(qBytes, read, qLen - read)
                    if (n == -1) break
                    read += n
                }

                val queryId = ((qBytes[0].toInt() and 0xFF) shl 8) or (qBytes[1].toInt() and 0xFF)
                val largeResponse = buildLargeResponse(queryId, "ednslarge.example.com", 5000, numAnswers = 40, withOpt = true)

                output.write((largeResponse.size shr 8).toByte().toInt())
                output.write((largeResponse.size and 0xFF).toByte().toInt())
                output.write(largeResponse)
                output.flush()
                client.close()
            } catch (_: Exception) {}
        }
        proxyThread.start()

        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val resolver = DnsResolver(
            tunWriter = writer,
            proxyIp = "127.0.0.1",
            proxyPort = mockProxyPort
        )

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(8, 8, 8, 8)
        // Client sends EDNS OPT record advertising 4096 bytes buffer
        val ednsQuery = buildStandardQuery(0x7655, "ednslarge.example.com", withOpt = true)
        val status = resolver.resolveViaProxyTcp(clientIp, dnsIp, 12345, 53, ednsQuery, null)

        proxyThread.join(2000)
        serverSocket.close()

        assertEquals(DnsResolver.ResolutionStatus.SUCCESS, status)
        val writtenBytes = sink.toByteArray()
        assertTrue("Truncated packet must be written to TUN", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!

        // Must respect TUN MTU (1400 bytes, payload <= 1372 bytes)
        assertTrue("Total IP packet must not exceed TUN MTU (1400)", writtenBytes.size <= DnsResolver.TUN_MTU)
        assertTrue("UDP payload must not exceed MAX_TUN_UDP_PAYLOAD (1372)", dnsPayload.size <= DnsResolver.MAX_TUN_UDP_PAYLOAD)
        assertTrue("TC bit must be set when response cannot fit into TUN MTU", parsedDns.isTruncated)
        assertEquals(0x7655, parsedDns.id)

        // RFC 6891 Section 7: OPT RR must be preserved in truncated EDNS response with updated ARCOUNT
        assertEquals("ARCOUNT must be 1 for the preserved OPT RR", 1, parsedDns.additionals.size)
        assertEquals("Additional record must be OPT RR (type 41)", DnsResolver.TYPE_OPT, parsedDns.additionals[0].type)
        assertEquals("OPT RR payload capacity preserved", 4096, parsedDns.additionals[0].classCode)
        assertTrue("At least one whole answer record must be included before truncation", parsedDns.answers.isNotEmpty())

        resolver.close()
    }

    @Test
    fun testDirectUdpResponseLargerThan4096BytesAcceptedAndTruncated() {
        val serverSocket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val serverPort = serverSocket.localPort

        val serverThread = Thread {
            try {
                val buf = ByteArray(1024)
                val pkt = DatagramPacket(buf, buf.size)
                serverSocket.receive(pkt)

                val queryId = ((pkt.data[0].toInt() and 0xFF) shl 8) or (pkt.data[1].toInt() and 0xFF)
                val largeResponse = buildLargeResponse(queryId, "largeudp.example.com", 5000, numAnswers = 40, withOpt = false)

                val respPkt = DatagramPacket(largeResponse, largeResponse.size, pkt.address, pkt.port)
                serverSocket.send(respPkt)
            } catch (_: Exception) {}
        }
        serverThread.start()

        val sink = ByteArrayOutputStream()
        val writer = createDummyTunWriter(sink)
        val resolver = DnsResolver(tunWriter = writer)

        val clientIp = byteArrayOf(10, 0, 0, 2)
        val dnsIp = byteArrayOf(127, 0, 0, 1)
        val query = buildStandardQuery(0x4321, "largeudp.example.com")
        val parsedQuery = resolver.parseDnsPacket(query)

        val status = resolver.resolveViaDirectUdp(clientIp, dnsIp, 12345, serverPort, query, parsedQuery)

        serverThread.join(2000)
        serverSocket.close()

        assertEquals("Large direct UDP DNS response (>4096 bytes) must succeed", DnsResolver.ResolutionStatus.SUCCESS, status)
        val writtenBytes = sink.toByteArray()
        assertTrue("Truncated UDP packet must be written to TUN", writtenBytes.isNotEmpty())
        val parsedIp = PacketUtils.parseIpHeader(writtenBytes, writtenBytes.size)!!
        val dnsPayload = PacketUtils.udpPayload(writtenBytes, parsedIp)
        val parsedDns = resolver.parseDnsPacket(dnsPayload)!!

        // Standard client without EDNS: capped at 512 bytes, IP <= 1400 bytes, TC=1
        assertTrue("UDP payload without EDNS must not exceed 512 bytes", dnsPayload.size <= DnsResolver.DEFAULT_DNS_UDP_SIZE)
        assertTrue("Total IP packet must not exceed TUN MTU (1400)", writtenBytes.size <= DnsResolver.TUN_MTU)
        assertTrue("Oversized UDP response must set TC=1", parsedDns.isTruncated)
        assertEquals(0x4321, parsedDns.id)
        assertEquals("largeudp.example.com", parsedDns.questions[0].name)
        assertTrue("Whole answers must be preserved within 512-byte budget", parsedDns.answers.isNotEmpty())

        resolver.close()
    }

    @Test
    fun testTruncateDnsResponsePreservesOptRrAndCalculatesArCount() {
        val resolver = DnsResolver()
        // Response with 3 answers (each ~100 bytes) and 1 OPT RR (11 bytes)
        val resp = buildLargeResponse(0x2211, "truncopt.example.com", targetSize = 400, numAnswers = 3, withOpt = true)
        val parsed = resolver.parseDnsPacket(resp)!!
        assertEquals(3, parsed.answers.size)
        assertEquals(1, parsed.additionals.size)
        assertEquals(DnsResolver.TYPE_OPT, parsed.additionals[0].type)

        val optLen = parsed.additionals[0].rawEndOffset - parsed.additionals[0].rawStartOffset
        val qLen = parsed.questions.sumOf { it.rawLength }
        val headerAndQ = 12 + qLen
        val a1Len = parsed.answers[0].rawEndOffset - parsed.answers[0].rawStartOffset
        val a2Len = parsed.answers[1].rawEndOffset - parsed.answers[1].rawStartOffset

        // Budget fits answer 1, answer 2 and OPT RR, but NOT answer 3
        val limit = headerAndQ + a1Len + a2Len + optLen + 2

        val truncated = resolver.truncateDnsResponse(resp, parsed, limit)
        assertTrue("Truncated response must not exceed limit", truncated.size <= limit)

        val parsedTrunc = resolver.parseDnsPacket(truncated)!!
        assertTrue("TC bit must be set", parsedTrunc.isTruncated)
        assertEquals("ANCOUNT must be exactly 2 whole records", 2, parsedTrunc.answers.size)
        assertEquals("ARCOUNT must be 1 for preserved OPT RR", 1, parsedTrunc.additionals.size)
        assertEquals(DnsResolver.TYPE_OPT, parsedTrunc.additionals[0].type)
        assertEquals(0x2211, parsedTrunc.id)

        resolver.close()
    }

    @Test
    fun testTruncateDnsResponsePreservesWholeRecordsAndCounts() {
        val resolver = DnsResolver()
        // Response with 3 answers: 60 bytes, 80 bytes, 150 bytes
        val resp = buildStandardResponse(0x3322, "truncate.example.com", answerTtls = listOf(60L, 60L, 60L))
        val parsed = resolver.parseDnsPacket(resp)!!
        assertEquals(3, parsed.answers.size)

        // Truncate to a limit that fits answer 1 and answer 2, but NOT answer 3
        val qLen = parsed.questions.sumOf { it.rawLength }
        val headerAndQ = 12 + qLen
        val a1Len = parsed.answers[0].rawEndOffset - parsed.answers[0].rawStartOffset
        val a2Len = parsed.answers[1].rawEndOffset - parsed.answers[1].rawStartOffset
        val limit = headerAndQ + a1Len + a2Len + 2 // Fits answers 1 & 2 only

        val truncatedBytes = resolver.truncateDnsResponse(resp, parsed, limit)
        assertTrue("Truncated size must not exceed limit", truncatedBytes.size <= limit)

        val parsedTruncated = resolver.parseDnsPacket(truncatedBytes)!!
        assertTrue("TC bit must be set", parsedTruncated.isTruncated)
        assertEquals("ANCOUNT must be updated to exactly 2 whole records", 2, parsedTruncated.answers.size)
        assertEquals(0, parsedTruncated.authorities.size)
        assertEquals(0, parsedTruncated.additionals.size)
        assertEquals(0x3322, parsedTruncated.id)

        resolver.close()
    }
}
