package com.artvpn.app.vpn

import android.net.Network
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Resolves proxy hostnames on the physical Android network.
 *
 * IMPORTANT: libbox calls LocalDNSTransport from Go. Calling ctx.success() or
 * ctx.errorCode() before lookup() returns creates a nested Go -> Kotlin -> Go
 * callback on the same native stack. On Android this can abort the Go runtime
 * (golang/go#68760). All ExchangeContext completions therefore run on a
 * separate executor, never inline on libbox's calling thread.
 */
internal class AndroidLocalDNSTransport(
    private val networkProvider: DefaultNetworkMonitor
) : LocalDNSTransport {

    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        val cancelled = AtomicBoolean(false)
        ctx.onCancel { cancelled.set(true) }
        dnsExecutor.execute {
            if (!cancelled.get()) ctx.errorCode(DNS_RCODE_SERVFAIL)
        }
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val cancelled = AtomicBoolean(false)
        ctx.onCancel { cancelled.set(true) }

        dnsExecutor.execute {
            if (cancelled.get()) return@execute
            try {
                val underlying: Network = networkProvider.currentNetwork()
                    ?: error("No physical network is available for DNS bootstrap")
                val addresses = underlying.getAllByName(domain).filter { address ->
                    when {
                        network.endsWith("4") -> address is Inet4Address
                        network.endsWith("6") -> address is Inet6Address
                        else -> true
                    }
                }.mapNotNull { it.hostAddress }.distinct()

                if (cancelled.get()) return@execute
                if (addresses.isEmpty()) {
                    ctx.errorCode(DNS_RCODE_SERVFAIL)
                } else {
                    ctx.success(addresses.joinToString("\n"))
                }
            } catch (_: UnknownHostException) {
                if (!cancelled.get()) ctx.errorCode(DNS_RCODE_SERVFAIL)
            } catch (_: Exception) {
                if (!cancelled.get()) ctx.errorCode(DNS_RCODE_SERVFAIL)
            }
        }
    }

    companion object {
        private const val DNS_RCODE_SERVFAIL = 2
        private val dnsExecutor = Executors.newCachedThreadPool(ThreadFactory { task ->
            Thread(task, "ARTVPN-DNS").apply { isDaemon = true }
        })
    }
}
