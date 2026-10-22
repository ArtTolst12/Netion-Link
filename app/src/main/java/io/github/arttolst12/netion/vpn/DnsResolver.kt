package io.github.arttolst12.netion.vpn

import android.net.VpnService
import android.util.Log
import io.github.arttolst12.netion.AppLogger
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Stage 5 DNS Forwarder and Cache.
 *
 * Resolves DNS queries arriving on the TUN interface (UDP port 53).
 *
 * Key guarantees and architectural constraints:
 * 1. Bounded Worker Pool & Queue: core 2, max 4 threads with an ArrayBlockingQueue of 32 tasks.
 *    Rejection results in immediate SERVFAIL (RCODE=2) response if the query is parseable.
 * 2. Routing Bypass: VpnService.protect() is used strictly to exclude outbound resolver sockets
 *    from the local TUN routing loop; it is not encryption or standalone privacy.
 * 3. Fallback Policy: Direct fallback to external public DNS (e.g. 1.1.1.1 / 8.8.8.8) is DISABLED
 *    by default to prevent silent DNS leaks outside the intended tethering path.
 * 4. Strict TTL & Negative Caching (RFC 1035 / RFC 2308):
 *    - Records with TTL 0 are never cached.
 *    - Each Resource Record's TTL is tracked individually and decremented dynamically.
 *    - A cached response expires as soon as any of its records expires.
 *    - Negative responses (NXDOMAIN / NODATA) are cached only if a valid SOA record is present,
 *      using min(SOA.TTL, SOA.MINIMUM, 300s).
 * 5. EDNS / DNSSEC: Queries with OPT RR (type 41) or the DO (DNSSEC OK) bit bypass the cache
 *    completely to avoid loss of protocol semantics.
 * 6. Response Validation: Validates response source address/port, Transaction ID, Question section,
 *    and packet boundaries before accepting any response.
 */
class DnsResolver(
    private val vpnService: VpnService? = null,
    private val tunWriter: TunWriter? = null,
    private val proxyIp: String? = null,
    private val proxyPort: Int = 0,
    private val isSocks: Boolean = false,
    private val enablePublicFallback: Boolean = false,
    private val protectSocketFn: ((Any) -> Boolean)? = null,
    private val udpTransportFn: ((dnsIp: ByteArray, dnsPort: Int, query: ByteArray) -> ByteArray?)? = null
) {
    companion object {
        private const val TAG = "DnsResolver"
        const val DNS_TIMEOUT_MS = 2500
        const val CORE_DNS_THREADS = 2
        const val MAX_DNS_THREADS = 4
        const val MAX_DNS_QUEUE = 32
        const val MAX_CACHE_ENTRIES = 512
        const val MAX_NEGATIVE_TTL_SEC = 300L
        const val MAX_POSITIVE_TTL_SEC = 3600L

        // DNS Record Types
        const val TYPE_A = 1
        const val TYPE_NS = 2
        const val TYPE_CNAME = 5
        const val TYPE_SOA = 6
        const val TYPE_PTR = 12
        const val TYPE_AAAA = 28
        const val TYPE_OPT = 41

        // DNS Classes
        const val CLASS_IN = 1

        // DNS RCODEs
        const val RCODE_NOERROR = 0
        const val RCODE_SERVFAIL = 2
        const val RCODE_NXDOMAIN = 3

        // Message & Buffer Size Limits
        const val DEFAULT_DNS_UDP_SIZE = 512
        const val TUN_MTU = 1400
        const val IP4_UDP_HEADER_SIZE = 28
        const val MAX_TUN_UDP_PAYLOAD = TUN_MTU - IP4_UDP_HEADER_SIZE // 1372 bytes
        const val MAX_TCP_DNS_RESPONSE_SIZE = 65535                   // RFC 1035 section 4.2.2 16-bit prefix
        const val MAX_IPV4_UDP_PAYLOAD = 65507                        // RFC 768 / RFC 791 max IPv4 UDP payload (65535 - 20 - 8)
    }

    enum class ResolutionStatus {
        SUCCESS,
        NETWORK_FAILURE,
        PROTECT_FAILED
    }

    // Bounded thread pool with bounded queue
    internal val executor = ThreadPoolExecutor(
        CORE_DNS_THREADS,
        MAX_DNS_THREADS,
        60L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_DNS_QUEUE),
        ThreadPoolExecutor.AbortPolicy()
    )

    // LRU DNS Cache
    internal val cache = DnsLruCache(MAX_CACHE_ENTRIES)

    /**
     * [stateLock] makes socket registration atomic with shutdown. A socket is registered before
     * protect/connect/send, so close() can reliably cancel every blocking resolver operation.
     * [publicationLock] serializes the close boundary with cache/TUN publication: once close()
     * returns, no worker can add a cache entry or send a late answer for this resolver generation.
     */
    private val stateLock = Any()
    private val publicationLock = Any()
    private val activeSockets = ConcurrentHashMap.newKeySet<Closeable>()

    @Volatile private var closed = false

    private fun registerSocket(socket: Closeable): Boolean = synchronized(stateLock) {
        if (closed) {
            false
        } else {
            activeSockets.add(socket)
            true
        }
    }

    private fun unregisterSocket(socket: Closeable) {
        activeSockets.remove(socket)
    }

    private fun isClosed(): Boolean = closed

    /**
     * Handle a DNS query packet from the TUN interface.
     */
    fun handleQuery(
        ipHeader: PacketUtils.IpHeader,
        udpHeader: PacketUtils.UdpHeader,
        queryPayload: ByteArray
    ) {
        if (closed || queryPayload.isEmpty()) return
        val srcIp = ipHeader.srcIp.copyOf()
        val dstIp = ipHeader.dstIp.copyOf()
        val srcPort = udpHeader.srcPort
        val dstPort = udpHeader.dstPort

        // 1. Strict pre-validation: Drop malformed packets, responses, or unsupported multi-question queries BEFORE queueing / networking
        val parsedQuery = parseDnsPacket(queryPayload)
        if (parsedQuery == null || parsedQuery.isResponse || parsedQuery.questions.size != 1) {
            Log.d(TAG, "Dropping invalid or unsupported DNS query before queueing (size=${queryPayload.size}, parsed=${parsedQuery != null}, isResponse=${parsedQuery?.isResponse}, questions=${parsedQuery?.questions?.size ?: 0})")
            return
        }

        // 2. Fast-path: Check local cache if query does not use EDNS/DNSSEC
        if (!parsedQuery.isEdnsOrDnssec) {
            val q = parsedQuery.questions[0]
            val key = DnsCacheKey(q.name, q.type, q.classCode)
            val cachedReply = synchronized(publicationLock) {
                if (closed) null else cache.get(key, parsedQuery.id)
            }
            if (cachedReply != null) {
                sendUdpPacket(dstIp, srcIp, dstPort, srcPort, cachedReply)
                return
            }
        }

        // 3. Queue resolution task in bounded executor
        val task = Runnable {
            if (closed) return@Runnable
            var resolved = false
            val proxyConfigured = !proxyIp.isNullOrEmpty() && proxyPort > 0

            if (proxyConfigured) {
                // When proxy is configured, DNS MUST route through the proxy to prevent leaks
                var protectError = false
                try {
                    val status = resolveViaProxyTcp(srcIp, dstIp, srcPort, dstPort, queryPayload, parsedQuery)
                    if (status == ResolutionStatus.SUCCESS) {
                        resolved = true
                    } else if (status == ResolutionStatus.PROTECT_FAILED) {
                        protectError = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Proxy DNS-over-TCP attempt failed: ${e.message}")
                }

                // If protect() failed, resolution is aborted and SERVFAIL already sent; terminate task
                if (protectError || closed) {
                    return@Runnable
                }

                // If proxy DNS resolution failed, do NOT fall back to direct UDP!
                // Return SERVFAIL to client immediately to preserve privacy and prevent bypass.
                if (!resolved) {
                    sendServFail(srcIp, dstIp, srcPort, dstPort, queryPayload)
                    AppLogger.w("DNS", "Proxy DNS resolution failed; returned SERVFAIL to client (direct UDP bypass blocked)")
                }
            } else {
                // Direct mode (no upstream proxy configured)
                var protectError = false
                try {
                    val status = resolveViaDirectUdp(srcIp, dstIp, srcPort, dstPort, queryPayload, parsedQuery)
                    if (status == ResolutionStatus.SUCCESS) {
                        resolved = true
                    } else if (status == ResolutionStatus.PROTECT_FAILED) {
                        protectError = true
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Direct UDP DNS failed: ${e.message}")
                }

                // If protect() failed on primary DNS, terminate immediately: SERVFAIL has already been sent,
                // do not try public fallback or alter routes
                if (protectError || closed) {
                    return@Runnable
                }

                // Optional explicit fallback only if enabled (when primary DNS failed due to network/timeout)
                if (!resolved && enablePublicFallback) {
                    try {
                        val publicDns = byteArrayOf(1, 1, 1, 1)
                        AppLogger.w("DNS", "Primary direct DNS failed; explicit fallback to 1.1.1.1")
                        val fbStatus = resolveViaDirectUdp(srcIp, publicDns, srcPort, 53, queryPayload, parsedQuery)
                        if (fbStatus == ResolutionStatus.SUCCESS) {
                            resolved = true
                        }
                    } catch (_: Exception) {}
                }

                // If resolution still failed (primary failed without fallback, or fallback also failed)
                if (!resolved && !closed) {
                    sendServFail(srcIp, dstIp, srcPort, dstPort, queryPayload)
                }
            }
        }

        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Saturated queue: return SERVFAIL immediately if query can be parsed, else drop
            sendServFail(srcIp, dstIp, srcPort, dstPort, queryPayload)
            AppLogger.w("DNS", "DNS worker queue saturated ($MAX_DNS_QUEUE): returned SERVFAIL to client port $srcPort")
        }
    }

    internal fun resolveViaDirectUdp(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray,
        parsedQuery: ParsedDnsPacket?
    ): ResolutionStatus {
        if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
        if (udpTransportFn != null) {
            if (protectSocketFn != null && !protectSocketFn.invoke(dnsIp)) {
                sendServFail(clientIp, dnsIp, clientPort, dnsPort, queryPayload)
                return ResolutionStatus.PROTECT_FAILED
            }
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
            val responseBytes = udpTransportFn.invoke(dnsIp, dnsPort, queryPayload)
                ?: return ResolutionStatus.NETWORK_FAILURE
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
            val ok = validateAndProcessResponse(clientIp, dnsIp, clientPort, dnsPort, queryPayload, parsedQuery, responseBytes)
            return if (ok) ResolutionStatus.SUCCESS else ResolutionStatus.NETWORK_FAILURE
        }

        val socket = DatagramSocket()
        if (!registerSocket(socket)) {
            try { socket.close() } catch (_: Exception) {}
            return ResolutionStatus.NETWORK_FAILURE
        }
        try {
            // Registration precedes protect/send so resolver close can unblock any point below.
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
            val protected = protectSocket(socket)
            if (!protected) {
                Log.w(TAG, "vpnService.protect() failed for DNS socket")
                AppLogger.w("DNS", "Socket protect() failed; aborting to prevent VPN routing loop")
                sendServFail(clientIp, dnsIp, clientPort, dnsPort, queryPayload)
                return ResolutionStatus.PROTECT_FAILED
            }
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            socket.soTimeout = DNS_TIMEOUT_MS
            val dnsServer = InetAddress.getByAddress(dnsIp)
            val request = DatagramPacket(queryPayload, queryPayload.size, dnsServer, dnsPort)
            socket.send(request)
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            val responseBuf = ByteArray(MAX_IPV4_UDP_PAYLOAD)
            val recvPacket = DatagramPacket(responseBuf, responseBuf.size)
            socket.receive(recvPacket)
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            // Validate source address and port strictly
            if (recvPacket.address != dnsServer || recvPacket.port != dnsPort) {
                Log.w(TAG, "Mismatched DNS response source: expected $dnsServer:$dnsPort, got ${recvPacket.address}:${recvPacket.port}")
                return ResolutionStatus.NETWORK_FAILURE
            }

            val respBytes = recvPacket.data.copyOf(recvPacket.length)
            if (!validateAndProcessResponse(clientIp, dnsIp, clientPort, dnsPort, queryPayload, parsedQuery, respBytes)) {
                return ResolutionStatus.NETWORK_FAILURE
            }
            return if (isClosed()) ResolutionStatus.NETWORK_FAILURE else ResolutionStatus.SUCCESS
        } catch (_: Exception) {
            return ResolutionStatus.NETWORK_FAILURE
        } finally {
            unregisterSocket(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    internal fun resolveViaProxyTcp(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray,
        parsedQuery: ParsedDnsPacket?
    ): ResolutionStatus {
        if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
        val startedNanos = System.nanoTime()
        val deadlineNs = startedNanos + TimeUnit.MILLISECONDS.toNanos(DNS_TIMEOUT_MS.toLong())
        var phase = "protect-and-bind"
        val socket = Socket()
        if (!registerSocket(socket)) {
            try { socket.close() } catch (_: Exception) {}
            return ResolutionStatus.NETWORK_FAILURE
        }

        try {
            // The socket has been registered before protect/bind/connect so close() can cancel it.
            if (!VpnSocketSetup.prepare(socket, protect = ::protectSocket, bindToNetwork = { protectedSocket ->
                    vpnService?.let { service ->
                        val network = io.github.arttolst12.netion.NetworkUtils.getUnderlyingNetwork(service, proxyIp)
                            ?: throw IOException("No underlying network for DNS proxy")
                        try {
                            network.bindSocket(protectedSocket)
                        } catch (e: Exception) {
                            throw IOException("Failed to bind DNS proxy socket to underlying network", e)
                        }
                    }
                })) {
                if (isClosed()) return ResolutionStatus.NETWORK_FAILURE
                Log.w(TAG, "vpnService.protect() failed for DNS TCP socket")
                AppLogger.w("DNS", "Socket protect() failed; proxy=$proxyIp:$proxyPort; bound=${socket.isBound}; connected=${socket.isConnected}; tx_id=${parsedQuery?.id}; direct_fallback=blocked. Check VPN permission and active service.")
                sendServFail(clientIp, dnsIp, clientPort, dnsPort, queryPayload)
                return ResolutionStatus.PROTECT_FAILED
            }
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            phase = "proxy-handshake"
            val targetPort = if (dnsPort > 0) dnsPort else 53
            val connectTimeout = remainingTimeoutMs(deadlineNs)
            if (connectTimeout <= 0) return ResolutionStatus.NETWORK_FAILURE
            // ProxyHandshake owns CONNECT/SOCKS framing. The deadline started above so every
            // remaining DNS-over-TCP read shares the same DNS_TIMEOUT_MS budget.
            ProxyHandshake.connect(
                socket = socket,
                proxyIp = proxyIp ?: return ResolutionStatus.NETWORK_FAILURE,
                proxyPort = proxyPort,
                targetIp = dnsIp,
                targetPort = targetPort,
                socks5 = isSocks,
                timeoutMs = connectTimeout,
                onPhase = { phase = it }
            )
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            phase = "dns-write"
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            val qLen = queryPayload.size
            output.write((qLen shr 8).toByte().toInt())
            output.write((qLen and 0xFF).toByte().toInt())
            output.write(queryPayload)
            output.flush()

            phase = "dns-read-length"
            val respLenHi = readByteWithDeadline(input, socket, deadlineNs)
            val respLenLo = readByteWithDeadline(input, socket, deadlineNs)
            if (respLenHi == -1 || respLenLo == -1) return ResolutionStatus.NETWORK_FAILURE
            val respLen = (respLenHi shl 8) or respLenLo
            if (respLen <= 0 || respLen > MAX_TCP_DNS_RESPONSE_SIZE) return ResolutionStatus.NETWORK_FAILURE

            phase = "dns-read-body"
            val dnsResponse = ByteArray(respLen)
            readFully(input, dnsResponse, socket, deadlineNs)
            if (isClosed()) return ResolutionStatus.NETWORK_FAILURE

            phase = "response-validation"
            val ok = validateAndProcessResponse(clientIp, dnsIp, clientPort, dnsPort, queryPayload, parsedQuery, dnsResponse)
            if (!isClosed()) {
                val details = "tx_id=${parsedQuery?.id}; type=${parsedQuery?.questions?.firstOrNull()?.type}; proxy=$proxyIp:$proxyPort; protocol=${if (isSocks) "SOCKS5" else "HTTP_CONNECT"}; response_bytes=$respLen; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}"
                if (ok) AppLogger.s("DNS", "DNS response accepted; $details")
                else AppLogger.w("DNS", "DNS response rejected; $details")
            }
            return if (ok && !isClosed()) ResolutionStatus.SUCCESS else ResolutionStatus.NETWORK_FAILURE
        } catch (e: Exception) {
            if (!isClosed()) {
                Log.d(TAG, "DNS proxy connection failed: ${e.message}")
                AppLogger.e("DNS", "DNS proxy operation failed; phase=$phase; tx_id=${parsedQuery?.id}; proxy=$proxyIp:$proxyPort; timeout_ms=$DNS_TIMEOUT_MS; elapsed_ms=${io.github.arttolst12.netion.DiagnosticText.elapsedMillis(startedNanos)}", e)
            }
            return ResolutionStatus.NETWORK_FAILURE
        } finally {
            unregisterSocket(socket)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    internal fun validateAndProcessResponse(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray,
        parsedQuery: ParsedDnsPacket?,
        responseBytes: ByteArray
    ): Boolean {
        if (isClosed()) return false
        val q = parsedQuery ?: parseDnsPacket(queryPayload) ?: return false
        val r = parseDnsPacket(responseBytes) ?: return false

        // 1. QR bit must be set (must be a Response, not a Query)
        if (!r.isResponse) {
            Log.w(TAG, "Received DNS packet with QR=0 (query, not response); rejecting")
            return false
        }

        // 2. Opcode must match query opcode (standard query opcode is 0)
        if (r.opcode != q.opcode) {
            Log.w(TAG, "DNS Opcode mismatch: expected ${q.opcode}, got ${r.opcode}")
            return false
        }

        // 3. Transaction ID match
        if (r.id != q.id) {
            Log.w(TAG, "Transaction ID mismatch: expected ${q.id}, got ${r.id}")
            return false
        }

        // 4. Question section match (validate all questions completely)
        if (q.questions.size != r.questions.size) {
            Log.w(TAG, "DNS question count mismatch: expected ${q.questions.size}, got ${r.questions.size}")
            return false
        }
        for (i in q.questions.indices) {
            val qQ = q.questions[i]
            val rQ = r.questions[i]
            if (qQ.name != rQ.name || qQ.type != rQ.type || qQ.classCode != rQ.classCode) {
                Log.w(TAG, "DNS question[$i] mismatch: expected ${qQ.name}/${qQ.type}, got ${rQ.name}/${rQ.type}")
                return false
            }
        }

        if (isClosed()) return false

        // 5. Determine maximum allowed UDP payload size for client (RFC 1035 / RFC 6891 / TUN MTU)
        val maxAllowedUdp = if (q.isEdnsOrDnssec) {
            val opt = q.additionals.firstOrNull { it.type == TYPE_OPT }
            val clientUdp = if (opt != null && opt.classCode >= DEFAULT_DNS_UDP_SIZE) opt.classCode else DEFAULT_DNS_UDP_SIZE
            minOf(clientUdp, MAX_TUN_UDP_PAYLOAD)
        } else {
            DEFAULT_DNS_UDP_SIZE
        }

        val isOversized = responseBytes.size > maxAllowedUdp

        // 6. Cache response if query is standard (single question, not EDNS/DO) and response was not truncated
        if (!q.isEdnsOrDnssec && q.questions.size == 1 && !r.isTruncated && !isOversized) {
            maybeCacheResponse(DnsCacheKey(q.questions[0].name, q.questions[0].type, q.questions[0].classCode), r, responseBytes)
        }

        // 7. If response exceeds client UDP capacity or TUN MTU, create well-formed truncated response with TC=1
        val finalPayload = if (isOversized) {
            truncateDnsResponse(responseBytes, r, maxAllowedUdp)
        } else {
            responseBytes
        }

        // 8. Send UDP packet to TUN client. sendUdpPacket has the final close check while holding
        // publicationLock, so no response can cross a completed resolver close().
        return sendUdpPacket(dnsIp, clientIp, dnsPort, clientPort, finalPayload)
    }

    private fun maybeCacheResponse(
        key: DnsCacheKey,
        resp: ParsedDnsPacket,
        rawBytes: ByteArray
    ) {
        val now = System.currentTimeMillis()

        // Truncated responses (TC=1) must NEVER be cached (RFC 2181 section 9)
        if (resp.isTruncated) return

        // P2: Check ALL Resource Records across answers, authorities, and additionals (except OPT).
        // If ANY RR has TTL <= 0, the response must NEVER be cached (for both positive and negative responses).
        if (resp.answers.any { it.ttl <= 0L }) return
        if (resp.authorities.any { it.ttl <= 0L }) return
        if (resp.additionals.any { it.type != TYPE_OPT && it.ttl <= 0L }) return

        // Negative Caching (RFC 2308) for NXDOMAIN (3) or NODATA (NOERROR with ANCOUNT=0)
        if (resp.rcode == RCODE_NXDOMAIN || (resp.rcode == RCODE_NOERROR && resp.answers.isEmpty())) {
            val soaRecord = resp.authorities.firstOrNull { it.type == TYPE_SOA } ?: return
            val soaMinTtl = parseSoaMinimumTtl(rawBytes, soaRecord) ?: return
            val effectiveNegativeTtl = minOf(soaRecord.ttl, soaMinTtl, MAX_NEGATIVE_TTL_SEC)
            if (effectiveNegativeTtl <= 0L) return

            // Track TTL offsets for SOA and all other authority/additional records, capped at effectiveNegativeTtl
            val offsets = mutableListOf<RecordTtlOffset>()
            offsets.add(RecordTtlOffset(soaRecord.ttlOffset, effectiveNegativeTtl))
            for (rr in resp.authorities) {
                if (rr !== soaRecord) {
                    offsets.add(RecordTtlOffset(rr.ttlOffset, minOf(rr.ttl, effectiveNegativeTtl)))
                }
            }
            for (rr in resp.additionals) {
                if (rr.type != TYPE_OPT) {
                    offsets.add(RecordTtlOffset(rr.ttlOffset, minOf(rr.ttl, effectiveNegativeTtl)))
                }
            }

            putCacheIfOpen(key, DnsCacheEntry(rawBytes, now, offsets, isNegative = true, negativeTtlSeconds = effectiveNegativeTtl))
            return
        }

        // Positive Caching (NOERROR with answers)
        if (resp.rcode == RCODE_NOERROR && resp.answers.isNotEmpty()) {
            val offsets = mutableListOf<RecordTtlOffset>()
            for (rr in resp.answers) {
                offsets.add(RecordTtlOffset(rr.ttlOffset, minOf(rr.ttl, MAX_POSITIVE_TTL_SEC)))
            }
            for (rr in resp.authorities) {
                offsets.add(RecordTtlOffset(rr.ttlOffset, minOf(rr.ttl, MAX_POSITIVE_TTL_SEC)))
            }
            for (rr in resp.additionals) {
                if (rr.type != TYPE_OPT) {
                    offsets.add(RecordTtlOffset(rr.ttlOffset, minOf(rr.ttl, MAX_POSITIVE_TTL_SEC)))
                }
            }

            putCacheIfOpen(key, DnsCacheEntry(rawBytes, now, offsets, isNegative = false, negativeTtlSeconds = 0L))
        }
    }

    private fun putCacheIfOpen(key: DnsCacheKey, entry: DnsCacheEntry) {
        synchronized(publicationLock) {
            if (!closed) {
                cache.put(key, entry)
            }
        }
    }

    private fun sendServFail(
        clientIp: ByteArray,
        dnsIp: ByteArray,
        clientPort: Int,
        dnsPort: Int,
        queryPayload: ByteArray
    ) {
        val servFailPayload = buildServFailResponse(queryPayload) ?: return
        sendUdpPacket(dnsIp, clientIp, dnsPort, clientPort, servFailPayload)
    }

    private fun sendUdpPacket(
        srcIp: ByteArray,
        dstIp: ByteArray,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): Boolean {
        if (payload.size > MAX_TUN_UDP_PAYLOAD) {
            Log.w(TAG, "Payload size ${payload.size} exceeds TUN MTU payload budget $MAX_TUN_UDP_PAYLOAD; dropping")
            return false
        }
        synchronized(publicationLock) {
            if (closed) return false
            return try {
                val pkt = PacketUtils.buildUdpPacket(
                    srcIp = srcIp,
                    dstIp = dstIp,
                    srcPort = srcPort,
                    dstPort = dstPort,
                    payload = payload
                )
                // Recheck immediately before publishing. A generation close that wins this lock
                // will suppress the write instead of allowing a late DNS answer onto a new TUN.
                if (closed) return false
                tunWriter?.write(pkt)
                true
            } catch (e: Exception) {
                Log.d(TAG, "Failed writing UDP packet to TUN: ${e.message}")
                false
            }
        }
    }

    internal fun truncateDnsResponse(
        rawResponse: ByteArray,
        parsedResp: ParsedDnsPacket,
        maxAllowedUdp: Int
    ): ByteArray {
        val qLen = parsedResp.questions.sumOf { it.rawLength }
        val headerAndQEnd = 12 + qLen
        if (headerAndQEnd > rawResponse.size || headerAndQEnd > maxAllowedUdp) {
            val hdrOnly = rawResponse.copyOf(12)
            hdrOnly[2] = (hdrOnly[2].toInt() or 0x02).toByte() // TC = 1
            w16(hdrOnly, 4, 0)
            w16(hdrOnly, 6, 0)
            w16(hdrOnly, 8, 0)
            w16(hdrOnly, 10, 0)
            return hdrOnly
        }

        // RFC 6891 Section 7: If the response had an OPT RR (type 41), it MUST be included in the
        // truncated response (if space permits) to preserve EDNS context.
        // We reserve budget for OPT RR so that answers do not starve the OPT record out of the packet.
        val optRr = parsedResp.additionals.firstOrNull { it.type == TYPE_OPT }
        val optLen = if (optRr != null) (optRr.rawEndOffset - optRr.rawStartOffset) else 0
        val canFitOpt = optRr != null && (headerAndQEnd + optLen <= maxAllowedUdp)
        val budgetForOtherSections = if (canFitOpt) (maxAllowedUdp - optLen) else maxAllowedUdp

        val out = ByteArrayOutputStream()
        val baseHeaderAndQ = rawResponse.copyOfRange(0, headerAndQEnd)
        baseHeaderAndQ[2] = (baseHeaderAndQ[2].toInt() or 0x02).toByte() // Set TC = 1
        out.write(baseHeaderAndQ)

        var anCount = 0
        var nsCount = 0
        var arCount = 0

        // 1. Answers: include whole records as long as they fit within budgetForOtherSections
        for (rr in parsedResp.answers) {
            val rrLen = rr.rawEndOffset - rr.rawStartOffset
            if (out.size() + rrLen <= budgetForOtherSections) {
                out.write(rawResponse, rr.rawStartOffset, rrLen)
                anCount++
            } else {
                break
            }
        }

        // 2. Authorities: include whole records only if all answers fit and this authority fits
        if (anCount == parsedResp.answers.size) {
            for (rr in parsedResp.authorities) {
                val rrLen = rr.rawEndOffset - rr.rawStartOffset
                if (out.size() + rrLen <= budgetForOtherSections) {
                    out.write(rawResponse, rr.rawStartOffset, rrLen)
                    nsCount++
                } else {
                    break
                }
            }
        }

        // 3. Additionals (excluding OPT RR): include whole records only if all answers and authorities fit
        if (anCount == parsedResp.answers.size && nsCount == parsedResp.authorities.size) {
            for (rr in parsedResp.additionals) {
                if (rr == optRr) continue
                val rrLen = rr.rawEndOffset - rr.rawStartOffset
                if (out.size() + rrLen <= budgetForOtherSections) {
                    out.write(rawResponse, rr.rawStartOffset, rrLen)
                    arCount++
                } else {
                    break
                }
            }
        }

        // 4. Append OPT RR if present and space was reserved
        if (canFitOpt) {
            val opt = optRr!!
            out.write(rawResponse, opt.rawStartOffset, optLen)
            arCount++
        }

        val result = out.toByteArray()
        // Update section counts in the 12-byte header to match whole records actually included
        w16(result, 6, anCount)
        w16(result, 8, nsCount)
        w16(result, 10, arCount)
        return result
    }

    private fun remainingTimeoutMs(deadlineNs: Long): Int {
        val remainingNs = deadlineNs - System.nanoTime()
        if (remainingNs <= 0L) return 0
        val ms = TimeUnit.NANOSECONDS.toMillis(remainingNs)
        return if (ms > 0) ms.toInt().coerceAtLeast(1) else 1
    }

    private fun readByteWithDeadline(input: InputStream, socket: Socket, deadlineNs: Long): Int {
        val remaining = remainingTimeoutMs(deadlineNs)
        if (remaining <= 0) throw java.net.SocketTimeoutException("DNS proxy operation deadline exceeded")
        socket.soTimeout = remaining
        return input.read()
    }

    private fun readFully(input: InputStream, buffer: ByteArray, socket: Socket, deadlineNs: Long) {
        var offset = 0
        while (offset < buffer.size) {
            val remaining = remainingTimeoutMs(deadlineNs)
            if (remaining <= 0) throw java.net.SocketTimeoutException("DNS proxy operation deadline exceeded")
            socket.soTimeout = remaining
            val count = input.read(buffer, offset, buffer.size - offset)
            if (count == -1) throw java.io.EOFException("Unexpected EOF reading DNS buffer")
            offset += count
        }
    }

    private fun protectSocket(socket: Socket): Boolean {
        if (isClosed()) return false
        return if (protectSocketFn != null) protectSocketFn.invoke(socket)
        else vpnService?.protect(socket) ?: (vpnService == null)
    }

    private fun protectSocket(socket: DatagramSocket): Boolean {
        if (isClosed()) return false
        return if (protectSocketFn != null) protectSocketFn.invoke(socket)
        else vpnService?.protect(socket) ?: (vpnService == null)
    }

    /**
     * Begins shutdown before returning. Resolver workers recheck [closed] before cache/TUN
     * publication; socket registration is atomic with this transition, so every blocking network
     * operation is closed here without joining a worker.
     */
    fun close() {
        val sockets: List<Closeable>
        synchronized(publicationLock) {
            synchronized(stateLock) {
                if (closed) return
                // Invalidate first. Workers recheck this before every cache/TUN publication.
                closed = true
                sockets = activeSockets.toList()
                activeSockets.clear()
            }
            cache.clear()
        }
        // Close blocking I/O before executor shutdown. Never wait for workers here.
        sockets.forEach { socket ->
            try { socket.close() } catch (_: Exception) {}
        }
        executor.shutdownNow()
    }

    // ─────────────────────────────────────────────────
    // DNS Protocol Parser & Builder
    // ─────────────────────────────────────────────────

    data class DnsQuestion(
        val name: String,
        val type: Int,
        val classCode: Int,
        val rawLength: Int
    )

    data class DnsRecord(
        val name: String,
        val type: Int,
        val classCode: Int,
        val ttl: Long,
        val ttlOffset: Int,
        val rdLength: Int,
        val rdataOffset: Int,
        val rawStartOffset: Int = 0,
        val rawEndOffset: Int = rdataOffset + rdLength
    )

    data class ParsedDnsPacket(
        val id: Int,
        val flags: Int,
        val isResponse: Boolean,
        val rcode: Int,
        val isTruncated: Boolean,
        val questions: List<DnsQuestion>,
        val answers: List<DnsRecord>,
        val authorities: List<DnsRecord>,
        val additionals: List<DnsRecord>,
        val isEdnsOrDnssec: Boolean
    ) {
        val opcode: Int get() = (flags ushr 11) and 0x0F
    }

    data class DnsCacheKey(
        val name: String,
        val type: Int,
        val classCode: Int
    )

    data class RecordTtlOffset(
        val offsetInBytes: Int,
        val originalTtlSeconds: Long
    )

    class DnsCacheEntry(
        val templateBytes: ByteArray,
        val cachedAtMs: Long,
        val ttlOffsets: List<RecordTtlOffset>,
        val isNegative: Boolean,
        val negativeTtlSeconds: Long
    ) {
        fun getValidResponse(currentTxId: Int, nowMs: Long): ByteArray? {
            val elapsedSec = maxOf(0L, (nowMs - cachedAtMs) / 1000L)
            if (isNegative && elapsedSec >= negativeTtlSeconds) return null
            if (ttlOffsets.isEmpty()) return null
            for (rec in ttlOffsets) {
                val remaining = rec.originalTtlSeconds - elapsedSec
                if (remaining <= 0L) return null // Evict as soon as ANY record expires!
            }
            val res = templateBytes.copyOf()
            // Update Transaction ID
            res[0] = (currentTxId shr 8).toByte()
            res[1] = (currentTxId and 0xFF).toByte()
            // Set QR = 1, RA = 1
            res[2] = (res[2].toInt() or 0x80).toByte()
            res[3] = (res[3].toInt() or 0x80).toByte()
            // Update each RR's TTL dynamically in response payload
            for (rec in ttlOffsets) {
                val remaining = maxOf(1L, rec.originalTtlSeconds - elapsedSec)
                w32(res, rec.offsetInBytes, remaining)
            }
            return res
        }
    }

    class DnsLruCache(private val maxEntries: Int = 512) {
        private val map = object : LinkedHashMap<DnsCacheKey, DnsCacheEntry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: Map.Entry<DnsCacheKey, DnsCacheEntry>): Boolean {
                return size > maxEntries
            }
        }

        @Synchronized
        fun get(key: DnsCacheKey, currentTxId: Int, nowMs: Long = System.currentTimeMillis()): ByteArray? {
            val entry = map[key] ?: return null
            val valid = entry.getValidResponse(currentTxId, nowMs)
            if (valid == null) {
                map.remove(key)
                return null
            }
            return valid
        }

        @Synchronized
        fun put(key: DnsCacheKey, entry: DnsCacheEntry) {
            map[key] = entry
        }

        @Synchronized
        fun clear() {
            map.clear()
        }

        @Synchronized
        fun size(): Int = map.size
    }

    // ─────────────────────────────────────────────────
    // Wire Parsing Helpers
    // ─────────────────────────────────────────────────

    internal fun parseDnsPacket(data: ByteArray): ParsedDnsPacket? {
        if (data.size < 12) return null
        val id = u16(data, 0)
        val flags = u16(data, 2)
        val isResponse = (flags and 0x8000) != 0
        val isTruncated = (flags and 0x0200) != 0
        val rcode = flags and 0x0F
        val qdCount = u16(data, 4)
        val anCount = u16(data, 6)
        val nsCount = u16(data, 8)
        val arCount = u16(data, 10)

        var offset = 12
        val questions = mutableListOf<DnsQuestion>()
        for (i in 0 until qdCount) {
            val startQ = offset
            val (name, consumed) = readDomainName(data, offset) ?: return null
            offset += consumed
            if (offset + 4 > data.size) return null
            val qType = u16(data, offset)
            val qClass = u16(data, offset + 2)
            offset += 4
            questions.add(DnsQuestion(name, qType, qClass, offset - startQ))
        }

        val answers = mutableListOf<DnsRecord>()
        for (i in 0 until anCount) {
            val rec = readRecord(data, offset) ?: return null
            answers.add(rec)
            offset = rec.rdataOffset + rec.rdLength
        }

        val authorities = mutableListOf<DnsRecord>()
        for (i in 0 until nsCount) {
            val rec = readRecord(data, offset) ?: return null
            authorities.add(rec)
            offset = rec.rdataOffset + rec.rdLength
        }

        var isEdnsOrDnssec = false
        val additionals = mutableListOf<DnsRecord>()
        for (i in 0 until arCount) {
            val rec = readRecord(data, offset) ?: return null
            additionals.add(rec)
            if (rec.type == TYPE_OPT) {
                isEdnsOrDnssec = true
            }
            offset = rec.rdataOffset + rec.rdLength
        }

        return ParsedDnsPacket(
            id = id,
            flags = flags,
            isResponse = isResponse,
            rcode = rcode,
            isTruncated = isTruncated,
            questions = questions,
            answers = answers,
            authorities = authorities,
            additionals = additionals,
            isEdnsOrDnssec = isEdnsOrDnssec
        )
    }

    private fun readRecord(data: ByteArray, startOffset: Int): DnsRecord? {
        var offset = startOffset
        val (name, consumed) = readDomainName(data, offset) ?: return null
        offset += consumed
        if (offset + 10 > data.size) return null

        val type = u16(data, offset)
        val classCode = u16(data, offset + 2)
        val ttlOffset = offset + 4
        val ttl = u32(data, ttlOffset)
        val rdLength = u16(data, offset + 8)
        val rdataOffset = offset + 10

        if (rdataOffset + rdLength > data.size) return null
        return DnsRecord(name, type, classCode, ttl, ttlOffset, rdLength, rdataOffset, startOffset, rdataOffset + rdLength)
    }

    internal fun readDomainName(data: ByteArray, startOffset: Int): Pair<String, Int>? {
        if (startOffset !in data.indices) return null
        val labels = mutableListOf<String>()
        var offset = startOffset
        var jumps = 0
        var bytesConsumed = 0
        var expandedLength = 1 // The terminating root label counts toward the 255-byte limit.
        var jumped = false

        while (offset < data.size && jumps < 32) {
            val len = data[offset].toInt() and 0xFF
            if (len == 0) {
                if (!jumped) bytesConsumed = (offset + 1) - startOffset
                return Pair(labels.joinToString("."), bytesConsumed)
            } else if ((len and 0xC0) == 0xC0) {
                if (offset + 1 >= data.size) return null
                val ptr = ((len and 0x3F) shl 8) or (data[offset + 1].toInt() and 0xFF)
                // RFC 1035 compression points to a prior occurrence, never forward or to itself.
                if (ptr >= offset) return null
                if (!jumped) {
                    bytesConsumed = (offset + 2) - startOffset
                    jumped = true
                }
                offset = ptr
                jumps++
            } else {
                // The 01 and 10 label prefixes are reserved, not lengths of 64..191 bytes.
                if (len and 0xC0 != 0) return null
                offset++
                if (offset + len > data.size) return null
                expandedLength += len + 1
                if (expandedLength > 255) return null
                labels.add(String(data, offset, len, Charsets.US_ASCII).lowercase())
                offset += len
                if (!jumped) bytesConsumed = offset - startOffset
            }
        }
        return null // Missing root terminator or an excessive/cyclic compression chain.
    }

    internal fun parseSoaMinimumTtl(data: ByteArray, soaRecord: DnsRecord): Long? {
        if (soaRecord.type != TYPE_SOA) return null
        var o = soaRecord.rdataOffset
        // Skip MNAME
        val (_, consumedM) = readDomainName(data, o) ?: return null
        o += consumedM
        // Skip RNAME
        val (_, consumedR) = readDomainName(data, o) ?: return null
        o += consumedR
        // Serial (4), Refresh (4), Retry (4), Expire (4), Minimum (4) = 20 bytes
        if (o + 20 > data.size || o + 20 > soaRecord.rdataOffset + soaRecord.rdLength) return null
        return u32(data, o + 16)
    }

    internal fun buildServFailResponse(queryPayload: ByteArray): ByteArray? {
        val parsed = parseDnsPacket(queryPayload) ?: return null
        val firstQ = parsed.questions.firstOrNull() ?: return null
        val qLen = firstQ.rawLength
        val res = ByteArray(12 + qLen)

        // ID
        res[0] = (parsed.id shr 8).toByte()
        res[1] = (parsed.id and 0xFF).toByte()

        // Flags: QR=1 (response), RD=1, RA=1, RCODE=2 (SERVFAIL)
        res[2] = 0x81.toByte()
        res[3] = 0x82.toByte()

        // QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
        res[4] = 0; res[5] = 1
        res[6] = 0; res[7] = 0
        res[8] = 0; res[9] = 0
        res[10] = 0; res[11] = 0

        // Copy original question section
        System.arraycopy(queryPayload, 12, res, 12, qLen)
        return res
    }
}

private fun u16(d: ByteArray, o: Int): Int =
    ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

private fun u32(d: ByteArray, o: Int): Long =
    ((d[o].toLong() and 0xFF) shl 24) or ((d[o + 1].toLong() and 0xFF) shl 16) or
    ((d[o + 2].toLong() and 0xFF) shl 8) or (d[o + 3].toLong() and 0xFF)

private fun w16(d: ByteArray, o: Int, v: Int) {
    d[o]     = (v shr 8).toByte()
    d[o + 1] = (v and 0xFF).toByte()
}

private fun w32(d: ByteArray, o: Int, v: Long) {
    d[o]     = (v shr 24).toByte()
    d[o + 1] = (v shr 16).toByte()
    d[o + 2] = (v shr 8).toByte()
    d[o + 3] = (v and 0xFF).toByte()
}
