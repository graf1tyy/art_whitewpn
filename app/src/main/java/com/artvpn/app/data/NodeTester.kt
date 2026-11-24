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
     * A quick physical TCP check only. Actual VLESS and HTTPS tunnel usability
     * is verified by ArtVpnService. Probe the most promising nodes, not every
     * entry in a large public list.
     */
    suspend fun test(nodes: List<VpnNode>): List<VpnNode> = withContext(Dispatchers.IO) {
        if (nodes.isEmpty()) return@withContext emptyList()
        val ranked = nodes.sortedWith(priorityComparator())
        val probeIds = ranked.take(MAX_TCP_PROBES).map { it.id }.toSet()
        val semaphore = Semaphore(MAX_PARALLEL_PROBES)
        val tested = coroutineScope {
            nodes.filter { it.id in probeIds }.map { node ->
                async {
                    semaphore.withPermit {
                        val socket = Socket()
                        try {
                            check(VpnSocketProtector.protect(socket)) { "Cannot protect probe socket" }
                            val start = System.nanoTime()
                            socket.connect(InetSocketAddress(node.host, node.port), CONNECT_TIMEOUT_MS)
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
            }.awaitAll()
        }
        val byId = tested.associateBy { it.id }
        nodes.map { byId[it.id] ?: it }.sortedWith(priorityComparator())
    }

    fun priorityComparator(): Comparator<VpnNode> =
        // Connection success is network-specific and ranked by NodeRepository.
        // This comparator only orders physical TCP reachability and measured ping.
        compareBy<VpnNode> { !it.reachable }
            .thenBy { it.pingMs ?: Long.MAX_VALUE }

    private const val MAX_TCP_PROBES = 48
    private const val MAX_PARALLEL_PROBES = 24
    private const val CONNECT_TIMEOUT_MS = 900
}
