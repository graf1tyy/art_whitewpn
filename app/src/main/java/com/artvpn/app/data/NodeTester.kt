package com.artvpn.app.data

import com.artvpn.app.vpn.VpnSocketProtector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

object NodeTester {
    /**
     * This is explicitly a TCP reachability check, not proof that the VLESS
     * handshake or end-to-end tunnel works. Real tunnel verification is done
     * by ArtVpnService after it starts sing-box.
     */
    suspend fun test(nodes: List<VpnNode>): List<VpnNode> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(10)
        coroutineScope {
            nodes.map { node ->
                async {
                    semaphore.withPermit {
                        val socket = Socket()
                        try {
                            // Probe the physical connection, never the tunnel being tested.
                            check(VpnSocketProtector.protect(socket)) { "Cannot protect probe socket" }
                            val start = System.nanoTime()
                            socket.connect(InetSocketAddress(node.host, node.port), 3500)
                            node.copy(
                                pingMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                                reachable = true
                            )
                        } catch (_: Exception) {
                            node.copy(pingMs = null, reachable = false)
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }.awaitAll().sortedWith(
                compareByDescending<VpnNode> { it.lastSuccessAt ?: 0L }
                    .thenBy { !it.reachable }
                    .thenBy { it.pingMs ?: Long.MAX_VALUE }
            )
        }
    }
}
