package io.github.arttolst12.netion.vpn

import java.io.Closeable
import java.io.OutputStream
import java.nio.channels.Selector
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the TUN resources even when startup fails before a runtime can be published. */
internal class VpnRuntimeResources private constructor(
    private val tun: Closeable,
    val writer: TunWriter,
    val dnsResolver: DnsResolver,
    val selector: Selector
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        release(tun, null, writer, dnsResolver, selector)
    }

    companion object {
        fun create(
            tun: Closeable,
            createOutput: () -> OutputStream,
            createWriter: (OutputStream) -> TunWriter,
            createDnsResolver: (TunWriter) -> DnsResolver,
            createSelector: () -> Selector = { Selector.open() }
        ): VpnRuntimeResources {
            var output: OutputStream? = null
            var writer: TunWriter? = null
            var resolver: DnsResolver? = null
            var selector: Selector? = null
            try {
                output = createOutput()
                writer = createWriter(output)
                resolver = createDnsResolver(writer)
                selector = createSelector()
                return VpnRuntimeResources(tun, writer, resolver, selector)
            } catch (failure: Throwable) {
                release(tun, output, writer, resolver, selector)
                throw failure
            }
        }

        private fun release(
            tun: Closeable,
            output: OutputStream?,
            writer: TunWriter?,
            resolver: DnsResolver?,
            selector: Selector?
        ) {
            // The descriptor owns the TUN: close it before any wrapper/worker that may be
            // blocked in I/O. A failed close must not prevent cleanup of the other resources.
            try { tun.close() } catch (_: Exception) {}
            try { resolver?.close() } catch (_: Exception) {}
            try { selector?.close() } catch (_: Exception) {}
            try {
                if (writer != null) writer.close() else output?.close()
            } catch (_: Exception) {}
        }
    }
}
