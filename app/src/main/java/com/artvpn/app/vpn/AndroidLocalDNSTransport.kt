package com.artvpn.app.vpn

import android.net.Network
import io.nekohasekai.libbox.ExchangeContext
import io.nekohasekai.libbox.LocalDNSTransport
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.UnknownHostException

/**
 * Android does not provide a normal /etc/resolv.conf to the embedded Go
 * runtime. Resolve proxy hostnames on the selected physical Network instead
 * of relying on the process-wide resolver (which can point back into the VPN).
 */
internal class AndroidLocalDNSTransport(
    private val networkProvider: DefaultNetworkMonitor
) : LocalDNSTransport {
    override fun raw(): Boolean = false

    override fun exchange(ctx: ExchangeContext, message: ByteArray) {
        error("Raw DNS exchange is disabled; use Android Network.getAllByName")
    }

    override fun lookup(ctx: ExchangeContext, network: String, domain: String) {
        val underlying: Network = networkProvider.currentNetwork()
            ?: error("No physical network is available for DNS bootstrap")
        try {
            val addresses = underlying.getAllByName(domain).filter { address ->
                when {
                    network.endsWith("4") -> address is Inet4Address
                    network.endsWith("6") -> address is Inet6Address
                    else -> true
                }
            }.mapNotNull { it.hostAddress }.distinct()

            if (addresses.isEmpty()) {
                ctx.errorCode(DNS_RCODE_SERVFAIL)
            } else {
                ctx.success(addresses.joinToString("\n"))
            }
        } catch (_: UnknownHostException) {
            ctx.errorCode(DNS_RCODE_SERVFAIL)
        }
    }

    companion object {
        private const val DNS_RCODE_SERVFAIL = 2
    }
}
