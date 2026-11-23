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
import com.artvpn.app.data.VpnNode
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.TimeUnit

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
        startForeground(NOTIFICATION_ID, notification("Подбираем рабочий сервер…"))
        scope.launch {
            try {
                val repository = NodeRepository(this@ArtVpnService)
                val savedLink = repository.selectedLink()
                val allNodes = repository.cached()
                val preferred = allNodes.firstOrNull { it.shareLink == savedLink }
                val candidates = (listOfNotNull(preferred) + allNodes
                    .sortedWith(
                        compareByDescending<VpnNode> { it.lastSuccessAt ?: 0L }
                            .thenBy { !it.reachable }
                            .thenBy { it.pingMs ?: Long.MAX_VALUE }
                    ))
                    .distinctBy { it.id }
                    .take(MAX_CONNECTION_CANDIDATES)
                check(candidates.isNotEmpty()) {
                    "Нет совместимых серверов. Обновите список перед подключением."
                }

                val server = CommandServer(this@ArtVpnService, this@ArtVpnService)
                server.start()
                commandServer = server

                var lastFailure = "Проверка интернет-трафика не прошла"
                for ((index, node) in candidates.withIndex()) {
                    updateNotification("Проверяем сервер ${index + 1}/${candidates.size} · ${node.countryLabel}")
                    try {
                        val config = VpnConfigCompiler.compile(node)
                        server.startOrReloadService(config, OverrideOptions())
                        // Let sing-box establish its outbound and DNS before the HTTP probe.
                        delay(1300)
                        val reachableUrl = verifyTrafficThroughTunnel()
                        if (reachableUrl != null) {
                            repository.saveSelected(node.shareLink)
                            repository.markSuccessful(node.shareLink)
                            updateNotification("Подключено · ${node.countryLabel}")
                            sendStatus(
                                ACTION_CONNECTED,
                                "Подключено · ${node.countryLabel} · VPN-трафик проверен"
                            )
                            return@launch
                        }
                        lastFailure = "Туннель запустился, но сайты не открылись"
                    } catch (e: Exception) {
                        lastFailure = e.message ?: "Ошибка конфигурации или соединения"
                    }
                }
                error("Не найден рабочий сервер: проверено ${candidates.size}. $lastFailure")
            } catch (e: Exception) {
                sendStatus(ACTION_FAILED, e.message ?: "Не удалось подключиться")
                stopTunnel()
            }
        }
    }

    /**
     * Run a real HTTPS request from the app process while it is inside the VPN.
     * A TCP-open server is not accepted until at least one request succeeds
     * through the active TUN/sing-box path.
     */
    private fun verifyTrafficThroughTunnel(): String? {
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(1800, TimeUnit.MILLISECONDS)
            .readTimeout(1800, TimeUnit.MILLISECONDS)
            .callTimeout(3000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
        val probes = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://www.google.com/generate_204",
            "https://yandex.ru/robots.txt"
        )
        for (baseUrl in probes) {
            val url = "$baseUrl?artvpn_check=${System.currentTimeMillis()}"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache, no-store")
                    .header("User-Agent", "ART-VPN-HealthCheck/0.2")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code in 300..399) {
                        return baseUrl
                    }
                }
            } catch (_: Exception) {
                // Try the next independent HTTPS endpoint.
            }
        }
        return null
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
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        VpnSocketProtector.uninstall()
        scope.cancel()
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        val builder = Builder().setSession("ART VPN").setMtu(options.mtu.coerceIn(1280, 1500))
        val v4 = options.inet4Address
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }
        if (options.dnsMode.value != Libbox.DNSModeDisabled) {
            val dns = options.dnsServerAddress
            while (dns.hasNext()) builder.addDnsServer(dns.next())
        }
        if (options.autoRoute) {
            builder.addRoute("0.0.0.0", 0)
            builder.addRoute("::", 0)
        }
        val descriptor = builder.establish() ?: error("Android не разрешил создать VPN-туннель")
        tunnel = descriptor
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true
    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("Не удалось защитить VPN-сокет от зацикливания")
    }
    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): ConnectionOwner = Libbox.findConnectionOwner(
        ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort
    )
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
    override fun openShellSession(
        user: PlatformUser?, command: String?, environ: StringIterator?,
        term: String?, rows: Int, cols: Int
    ): ShellSession = error("Shell is not supported")
    override fun readSystemSSHHostKey(): String = error("SSH is not supported")
    override fun lookupSFTPServer(): String = error("SFTP is not supported")
    override fun tailscaleHostname(): String = "artvpn-android"
    override fun usePlatformBridge(): Boolean = false
    override fun createBridge(options: BridgeOptions?): BridgeSession = error("Bridge is not supported")
    override fun lookupUser(username: String?): PlatformUser = error("User lookup is not supported")
    override fun registerMyInterface(name: String?) = Unit

    override fun sendNotification(notification: BoxNotification) = Unit
    override fun cancelNotification(identifier: String, typeID: Int) = Unit
    override fun serviceStop() {
        sendStatus(ACTION_DISCONNECTED, "VPN остановлен")
        stopTunnel()
    }
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
                addresses = StringList(item.interfaceAddresses.map {
                    "${it.address.hostAddress}/${it.networkPrefixLength}"
                })
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
        private const val MAX_CONNECTION_CANDIDATES = 8

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
