package com.artvpn.app.vpn

import java.net.Socket

/**
 * Keeps ART VPN's diagnostic TCP probes on the physical network.
 * A probe must never be routed into the tunnel it is trying to measure.
 */
object VpnSocketProtector {
    @Volatile
    private var protectSocket: ((Socket) -> Boolean)? = null

    fun install(protector: (Socket) -> Boolean) {
        protectSocket = protector
    }

    fun uninstall() {
        protectSocket = null
    }

    fun protect(socket: Socket): Boolean = protectSocket?.invoke(socket) ?: true
}
