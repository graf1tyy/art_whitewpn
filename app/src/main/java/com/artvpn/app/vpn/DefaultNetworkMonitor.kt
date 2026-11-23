package com.artvpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import io.nekohasekai.libbox.InterfaceUpdateListener
import java.net.NetworkInterface

/**
 * Tracks the physical uplink used by sing-box. On Android 9+ a plain
 * registerDefaultNetworkCallback may report the VPN itself, so use the best
 * matching/requested underlying network where the platform supports it.
 */
internal class DefaultNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
        ?: error("ConnectivityManager is unavailable")
    private val handlerThread = HandlerThread("ART-VPN-network").apply { start() }
    private val handler = Handler(handlerThread.looper)
    @Volatile private var listener: InterfaceUpdateListener? = null
    @Volatile private var network: Network? = null
    private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network)
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = update(network)
        override fun onLost(network: Network) {
            if (this@DefaultNetworkMonitor.network == network) {
                this@DefaultNetworkMonitor.network = findPhysicalNetwork()
                publish(this@DefaultNetworkMonitor.network)
            }
        }
    }

    @Synchronized
    fun start(listener: InterfaceUpdateListener) {
        this.listener = listener
        if (!registered) {
            registered = true
            try {
                when {
                    Build.VERSION.SDK_INT >= 31 ->
                        connectivity.registerBestMatchingNetworkCallback(request, callback, handler)
                    Build.VERSION.SDK_INT >= 28 ->
                        connectivity.requestNetwork(request, callback, handler)
                    Build.VERSION.SDK_INT >= 26 ->
                        connectivity.registerDefaultNetworkCallback(callback, handler)
                    else -> connectivity.registerNetworkCallback(request, callback, handler)
                }
            } catch (error: Exception) {
                registered = false
                Log.e(TAG, "Cannot register physical network callback", error)
            }
        }
        if (network == null) network = findPhysicalNetwork()
        publish(network)
    }

    @Synchronized
    fun stop() {
        listener = null
        if (registered) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
            registered = false
        }
        network = null
        handlerThread.quitSafely()
    }

    fun currentNetwork(): Network? = network ?: findPhysicalNetwork()

    private fun update(candidate: Network) {
        val capabilities = runCatching { connectivity.getNetworkCapabilities(candidate) }.getOrNull()
            ?: return
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return
        network = candidate
        publish(candidate)
    }

    private fun findPhysicalNetwork(): Network? {
        val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        return networks.firstOrNull { candidate ->
            val caps = runCatching { connectivity.getNetworkCapabilities(candidate) }.getOrNull()
            caps != null &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    private fun publish(candidate: Network?) {
        val target = listener ?: return
        if (candidate == null) {
            target.updateDefaultInterface("", -1, false, false)
            return
        }
        handler.post {
            repeat(10) {
                val properties = runCatching { connectivity.getLinkProperties(candidate) }.getOrNull()
                val name = properties?.interfaceName
                val index = name?.let { runCatching { NetworkInterface.getByName(it)?.index }.getOrNull() }
                if (!name.isNullOrBlank() && index != null && index > 0) {
                    runCatching { target.updateDefaultInterface(name, index, false, false) }
                    return@post
                }
                Thread.sleep(100)
            }
            Log.w(TAG, "Could not resolve physical interface for $candidate")
        }
    }

    companion object {
        private const val TAG = "ARTVPN-Network"
    }
}
