package com.artvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.artvpn.app.MainActivity
import com.artvpn.app.R
import com.artvpn.app.data.NodeRepository
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification as BoxNotification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.NetworkInterface

class ArtVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            else -> startTunnel()
        }
        return START_STICKY
    }

    private fun startTunnel() {
        if (commandServer != null) return
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification("Подключаемся…"))
        scope.launch {
            try {
                val repository = NodeRepository(this@ArtVpnService)
                val link = repository.selectedLink() ?: error("Сервер не выбран")
                val node = repository.cached().firstOrNull { it.shareLink == link }
                    ?: error("Сервер отсутствует в кэше. Обновите список.")
                val server = CommandServer(this@ArtVpnService, this@ArtVpnService)
                server.start()
                commandServer = server
                server.startOrReloadService(VpnConfigCompiler.compile(node), OverrideOptions())
                sendStatus(ACTION_CONNECTED, "Подключено")
                updateNotification("Подключено · ${node.countryLabel}")
            } catch (e: Exception) {
                sendStatus(ACTION_FAILED, e.message ?: "Не удалось подключиться")
                stopTunnel()
            }
        }
    }

    private fun stopTunnel() {
        scope.launch {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { tunnel?.close() }
            tunnel = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            sendStatus(ACTION_DISCONNECTED, "Отключено")
        }
    }

    private fun sendStatus(action: String, message: String) {
        sendBroadcast(Intent(action).setPackage(packageName).putExtra(EXTRA_MESSAGE, message))
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "ART VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vpn).setContentTitle("ART VPN").setContentText(text)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }
    override fun onCreate() {
        super.onCreate()
        VpnSocketProtector.install { socket -> protect(socket) }
    }
    override fun onRevoke() { stopTunnel(); super.onRevoke() }
    override fun onDestroy() {
        VpnSocketProtector.uninstall()
        scope.cancel()
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder().setSession("ART VPN").setMtu(options.mtu.coerceIn(1280, 1500))
        val v4 = options.inet4Address
        while (v4.hasNext()) { val a = v4.next(); builder.addAddress(a.address(), a.prefix()) }
        val v6 = options.inet6Address
        while (v6.hasNext()) { val a = v6.next(); builder.addAddress(a.address(), a.prefix()) }
        if (options.dnsMode.value != Libbox.DNSModeDisabled) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(dns.next())
        }
        if (options.autoRoute) {
            builder.addRoute("0.0.0.0", 0)
        }
        val descriptor = builder.establish() ?: error("Android не разрешил создать VPN-туннель")
        tunnel = descriptor
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("Не удалось защитить VPN-сокет от зацикливания")
    }
    override fun findConnectionOwner(ipProtocol: Int, sourceAddress: String, sourcePort: Int, destinationAddress: String, destinationPort: Int): ConnectionOwner =
        Libbox.findConnectionOwner(ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort)
    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) = Unit
    override fun getInterfaces(): NetworkInterfaceIterator =
        InterfaceList(NetworkInterface.getNetworkInterfaces()?.toList().orEmpty())
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = null
    override fun startNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) = Unit
    override fun usePlatformShell(): Boolean = false
    override fun checkPlatformShell() = error("Shell is not supported")
    override fun openShellSession(user: PlatformUser?, command: String?, environ: StringIterator?, term: String?, rows: Int, cols: Int): ShellSession =
        error("Shell is not supported")
    override fun readSystemSSHHostKey(): String = error("SSH is not supported")
    override fun lookupSFTPServer(): String = error("SFTP is not supported")
    override fun tailscaleHostname(): String = "artvpn-android"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("Bridge is not supported")
    override fun lookupUser(username: String?): PlatformUser = error("User lookup is not supported")
    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: BoxNotification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun serviceStop() { sendStatus(ACTION_DISCONNECTED, "VPN остановлен"); stopTunnel() }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) = Unit
    override fun connectSSHAgent(): Int = -1

    private class InterfaceList(private val interfaces: List<NetworkInterface>) : NetworkInterfaceIterator {
        private val iterator = interfaces.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface {
            val item = iterator.next()
            return io.nekohasekai.libbox.NetworkInterface().apply {
                name = item.name
                index = item.index
                mtu = item.mtu
                type = Libbox.InterfaceTypeOther
                addresses = StringList(item.interfaceAddresses.map { "${it.address.hostAddress}/${it.networkPrefixLength}" })
                dnsServer = StringList(emptyList())
                gateway = StringList(emptyList())
            }
        }
    }
    private class StringList(private val values: List<String>) : StringIterator {
        private val iterator = values.iterator()
        override fun len(): Int = values.size
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    companion object {
        const val ACTION_START = "com.artvpn.app.START"
        const val ACTION_STOP = "com.artvpn.app.STOP"
        const val ACTION_CONNECTED = "com.artvpn.app.CONNECTED"
        const val ACTION_DISCONNECTED = "com.artvpn.app.DISCONNECTED"
        const val ACTION_FAILED = "com.artvpn.app.FAILED"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "artvpn-vpn"
        private const val NOTIFICATION_ID = 41
        fun start(context: android.content.Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, ArtVpnService::class.java).setAction(ACTION_START)
            )
        }
        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ArtVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
