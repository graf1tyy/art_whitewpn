package com.artvpn.app.data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import com.artvpn.app.vpn.VpnSocketProtector

object NodeTester {
    suspend fun test(nodes: List<VpnNode>): List<VpnNode> = withContext(Dispatchers.IO) {
        val semaphore = Semaphore(12)
        coroutineScope {
            nodes.map { node -> async {
                semaphore.withPermit {
                    val socket = Socket()
                    try {
                        val start = System.nanoTime()
                        socket.connect(InetSocketAddress(node.host, node.port), 2500)
                        node.copy(pingMs = (System.nanoTime() - start) / 1_000_000, reachable = true)
                    } catch (_: Exception) {
                        node.copy(pingMs = null, reachable = false)
                    } finally { runCatching { socket.close() } }
                }
            } }.awaitAll().sortedWith(compareBy<VpnNode> { !it.reachable }.thenBy { it.pingMs ?: Long.MAX_VALUE })
        }
    }
}
