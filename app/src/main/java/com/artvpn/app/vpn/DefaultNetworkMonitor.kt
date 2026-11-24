package com.artvpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
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
 * Tracks the physical uplink and exposes it to sing-box.
 *
 * Important: sing-box owns InterfaceUpdateListener. It is valid only between
 * startDefaultInterfaceMonitor() and closeDefaultInterfaceMonitor(). Keeping
 * an old listener after a core reload can call into a released Go object and
 * terminate the process from the network callback thread.
 */
internal class DefaultNetworkMonitor(
    context: Context,
    private val onUnderlyingNetworkChanged: (Network?) -> Unit
) {
    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: error("ConnectivityManager is unavailable")
    private val callbackThread = HandlerThread("network-callback").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val stateLock = Any()

    @Volatile private var listener: InterfaceUpdateListener? = null
    @Volatile private var network: Network? = null
    @Volatile private var registered = false

    private val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
        .build()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network)

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) = update(network)

        override fun onLinkPropertiesChanged(
            network: Network,
            linkProperties: android.net.LinkProperties
        ) = update(network)

        override fun onLost(network: Network) {
            synchronized(stateLock) {
                if (this@DefaultNetworkMonitor.network != network) return
                val replacement = findPhysicalNetwork(excluding = network)
                this@DefaultNetworkMonitor.network = replacement
                onUnderlyingNetworkChanged(replacement)
                publishLocked(replacement)
            }
        }
    }

    /** Start tracking the physical network without invoking sing-box callbacks. */
    fun start() {
        synchronized(stateLock) {
            if (registered) return
            registered = true
        }

        try {
            when {
                Build.VERSION.SDK_INT >= 31 ->
                    connectivity.registerBestMatchingNetworkCallback(request, callback, callbackHandler)
                Build.VERSION.SDK_INT >= 28 ->
                    connectivity.requestNetwork(request, callback, callbackHandler)
                Build.VERSION.SDK_INT >= 26 ->
                    connectivity.registerDefaultNetworkCallback(callback, callbackHandler)
                else ->
                    connectivity.registerNetworkCallback(request, callback, callbackHandler)
            }
        } catch (error: Exception) {
            synchronized(stateLock) { registered = false }
            Log.e(TAG, "Cannot register physical network callback", error)
        }

        val current = findPhysicalNetwork()
        synchronized(stateLock) {
            if (network == null) network = current
            onUnderlyingNetworkChanged(network)
        }
    }

    /** Called only by sing-box while its native monitor is alive. */
    fun setListener(listener: InterfaceUpdateListener) {
        synchronized(stateLock) {
            this.listener = listener
            publishLocked(network ?: findPhysicalNetwork())
        }
    }

    /** Detach only the listener instance being closed by sing-box. */
    fun clearListener(expected: InterfaceUpdateListener) {
        synchronized(stateLock) {
            if (listener === expected) listener = null
        }
    }

    /** Stop the Android watcher and invalidate all native callbacks. */
    fun stop() {
        val shouldUnregister = synchronized(stateLock) {
            listener = null
            val wasRegistered = registered
            registered = false
            network = null
            wasRegistered
        }
        if (shouldUnregister) {
            runCatching { connectivity.unregisterNetworkCallback(callback) }
                .onFailure { Log.w(TAG, "Cannot unregister physical network callback", it) }
        }
    }

    fun close() {
        stop()
        callbackThread.quitSafely()
    }

    fun currentNetwork(): Network? = network ?: findPhysicalNetwork()

    private fun update(candidate: Network) {
        synchronized(stateLock) {
            if (!registered) return
            val capabilities = runCatching {
                connectivity.getNetworkCapabilities(candidate)
            }.getOrNull() ?: return
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return

            network = candidate
            onUnderlyingNetworkChanged(candidate)
            publishLocked(candidate)
        }
    }

    private fun findPhysicalNetwork(excluding: Network? = null): Network? {
        fun isPhysical(candidate: Network): Boolean {
            if (candidate == excluding) return false
            val capabilities = runCatching {
                connectivity.getNetworkCapabilities(candidate)
            }.getOrNull() ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }

        val active = runCatching { connectivity.activeNetwork }.getOrNull()
        if (active != null && isPhysical(active)) return active
        val networks = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        return networks.firstOrNull(::isPhysical)
    }

    /**
     * NetworkCallback already runs on callbackThread. Deliver the callback
     * synchronously there, like the official sing-box Android client; do not
     * enqueue a second callback that could outlive closeDefaultInterfaceMonitor.
     */
    private fun publishLocked(candidate: Network?) {
        val target = listener ?: return
        if (candidate == null) {
            runCatching { target.updateDefaultInterface("", -1, false, false) }
                .onFailure { Log.w(TAG, "Cannot clear default interface", it) }
            return
        }

        repeat(10) { attempt ->
            val properties = runCatching {
                connectivity.getLinkProperties(candidate)
            }.getOrNull()
            val name = properties?.interfaceName
            val index = name?.let {
                runCatching { NetworkInterface.getByName(it)?.index }.getOrNull()
            }
            if (!name.isNullOrBlank() && index != null && index > 0) {
                runCatching {
                    target.updateDefaultInterface(name, index, false, false)
                }.onFailure {
                    Log.w(TAG, "Cannot publish default interface $name/$index", it)
                }
                return
            }
            if (attempt < 9) Thread.sleep(100)
        }
        Log.w(TAG, "Could not resolve physical interface for $candidate")
    }

    companion object {
        private const val TAG = "ARTVPN-Network"
    }
}
