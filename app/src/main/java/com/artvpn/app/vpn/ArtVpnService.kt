package com.artvpn.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.artvpn.app.ArtVpnApplication
import com.artvpn.app.CrashDiagnostics
import com.artvpn.app.MainActivity
import com.artvpn.app.R
import com.artvpn.app.data.NetworkProfile
import com.artvpn.app.data.NodeRepository
import com.artvpn.app.data.NodeTester
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ArtVpnService : VpnService(), PlatformInterface, CommandServerHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var commandServer: CommandServer? = null
    private var tunnel: ParcelFileDescriptor? = null
    private var connectionJob: Job? = null
    private var closing = false
    @Volatile private var foregroundStarted = false
    private val networkMonitorDelegate = lazy {
        DefaultNetworkMonitor(this) { underlying ->
            if (tunnel != null) {
                runCatching { setUnderlyingNetworks(underlying?.let { arrayOf(it) }) }
            }
        }
    }
    private val networkMonitor by networkMonitorDelegate

    private val healthClient by lazy {
        OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(1000, TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
            .callTimeout(1500, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashDiagnostics.mark(this, "RUNNING:onStartCommand:" + intent?.action)
        try {
            when (intent?.action) {
                ACTION_STOP -> stopTunnel()
                else -> startTunnel()
            }
        } catch (error: Exception) {
            CrashDiagnostics.mark(this, "FAILED:onStartCommand:" + error.javaClass.simpleName + ":" + error.message)
            Log.e(TAG, "Unable to start VPN service", error)
            sendStatus(ACTION_FAILED, error.message ?: "Не удалось запустить VPN-службу")
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (closing || connectionJob?.isActive == true || commandServer != null) return
        closing = false
        createNotificationChannel()
        CrashDiagnostics.mark(this, "RUNNING:foreground-promotion")
        promoteToForeground("Автоматически подбираем сервер…")
        CrashDiagnostics.mark(this, "RUNNING:startTunnel")
        Log.i(TAG, "VPN service started; preparing the tunnel")
        sendStatus(ACTION_CONNECTING, "Подбираем лучший сервер…")

        connectionJob = scope.launch {
            try {
                val repository = NodeRepository(this@ArtVpnService)
                val allNodes = repository.cached()
                val networkKey = NetworkProfile.activeNetworkProfile(this@ArtVpnService)
                val ordered = repository.rankForNetwork(allNodes, networkKey)
                val now = System.currentTimeMillis()
                val preferred = repository.selectedLink()?.let { link ->
                    ordered.firstOrNull { it.shareLink == link }?.takeIf { node ->
                        if (!networkKey.isNullOrBlank()) {
                            repository.hasRecentNetworkSuccess(
                                node.shareLink, networkKey, PREFERRED_SUCCESS_TTL_MS
                            )
                        } else {
                            val recentlySucceeded = node.lastSuccessAt?.let {
                                now - it < PREFERRED_SUCCESS_TTL_MS
                            } == true
                            val recentlyFailed = node.lastFailureAt?.let {
                                now - it < FAILURE_COOLDOWN_MS
                            } == true
                            recentlySucceeded && !recentlyFailed
                        }
                    }
                }
                val candidates = (listOfNotNull(preferred) + ordered)
                    .distinctBy { it.id }
                    .take(MAX_CONNECTION_CANDIDATES)

                check(candidates.isNotEmpty()) {
                    "Нет серверов для подключения. Сначала обновите список."
                }

                CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:libbox-setup")
                (application as ArtVpnApplication).ensureLibboxSetup()
                CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:libbox-setup-complete")

                CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:command-server-create")
                val server = CommandServer(this@ArtVpnService, this@ArtVpnService)
                try {
                    server.start()
                    commandServer = server
                } catch (error: Throwable) {
                    runCatching { server.close() }
                    throw error
                }
                CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:command-server-started")

                // Match the official Android client: the command server and
                // libbox setup are ready before registering the physical network.
                CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:physical-network-monitor")
                networkMonitor.start()

                var lastFailure = "Сайты не ответили через VPN"
                for ((index, node) in candidates.withIndex()) {
                    val progress = "Проверяем сервер ${index + 1} из ${candidates.size}"
                    updateNotification(progress)
                    sendStatus(ACTION_CONNECTING, progress)
                    try {
                        CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:compile-config:" + (index + 1))
                        val config = VpnConfigCompiler.compile(node)
                        CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:validate-config:" + (index + 1))
                        Libbox.checkConfig(config)
                        CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:libbox-start:" + (index + 1))
                        server.startOrReloadService(config, OverrideOptions())
                        CrashDiagnostics.mark(this@ArtVpnService, "RUNNING:traffic-probe:" + (index + 1))
                        delay(350)

                        if (verifyTrafficThroughTunnel() != null) {
                            repository.saveSelected(node.shareLink)
                            repository.markSuccessful(node.shareLink, networkKey)
                            CrashDiagnostics.mark(this@ArtVpnService, "CONNECTED:" + node.countryLabel)
                            updateNotification("VPN подключён")
                            sendStatus(
                                ACTION_CONNECTED,
                                "Подключено · ${node.countryLabel}"
                            )
                            return@launch
                        }

                        repository.markFailed(node.shareLink, networkKey)
                        lastFailure = "Сервер не пропустил интернет-трафик"
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Exception) {
                        CrashDiagnostics.mark(this@ArtVpnService, "FAILED:candidate-" + (index + 1) + ":" + e.javaClass.simpleName + ":" + e.message)
                        Log.e(TAG, "Candidate ${index + 1} failed (${node.countryLabel})", e)
                        repository.markFailed(node.shareLink, networkKey)
                        lastFailure = e.message ?: "Сервер не ответил"
                    }
                }
                throw IllegalStateException(
                    "Не удалось подключиться. Проверено серверов: ${candidates.size}. $lastFailure"
                )
            } catch (cancelled: CancellationException) {
                closeTunnel(notifyDisconnected = false)
            } catch (e: Throwable) {
                CrashDiagnostics.mark(this@ArtVpnService, "FAILED:connection:" + e.javaClass.simpleName + ":" + e.message)
                Log.e(TAG, "VPN connection attempt failed", e)
                sendStatus(ACTION_FAILED, e.message ?: e.javaClass.simpleName)
                closeTunnel(notifyDisconnected = false)
            }
        }
    }

    /**
     * Probe several independent HTTPS endpoints at the same time. The first
     * successful response proves that packets are actually passing the TUN;
     * a TCP-open node alone is never reported as connected.
     */
    private suspend fun verifyTrafficThroughTunnel(): String? = coroutineScope {
        val probes = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://yandex.ru/robots.txt"
        )
        val result = CompletableDeferred<String?>()
        val remaining = AtomicInteger(probes.size)
        val jobs = probes.map { baseUrl ->
            launch(Dispatchers.IO) {
                try {
                    val url = "$baseUrl?artvpn_check=${System.currentTimeMillis()}"
                    val request = Request.Builder()
                        .url(url)
                        .header("Cache-Control", "no-cache, no-store")
                        .header("User-Agent", "ART-VPN-HealthCheck/0.3")
                        .build()
                    healthClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code in 300..399) {
                            result.complete(baseUrl)
                        }
                    }
                } catch (_: Exception) {
                    // Other HTTPS endpoints are tested concurrently.
                } finally {
                    if (remaining.decrementAndGet() == 0) result.complete(null)
                }
            }
        }

        val winner = withTimeoutOrNull(HEALTH_PROBE_TIMEOUT_MS) { result.await() }
        jobs.forEach { it.cancel() }
        winner
    }

    private fun stopTunnel() {
        if (closing) return
        connectionJob?.cancel()
        closeTunnel(notifyDisconnected = true)
    }

    private fun closeTunnel(notifyDisconnected: Boolean) {
        if (closing) return
        closing = true
        scope.launch {
            runCatching { commandServer?.closeService() }
            runCatching { commandServer?.close() }
            commandServer = null
            runCatching { if (networkMonitorDelegate.isInitialized()) networkMonitor.stop() }
            runCatching { tunnel?.close() }
            tunnel = null
            if (foregroundStarted) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                foregroundStarted = false
            }
            if (notifyDisconnected) {
                CrashDiagnostics.mark(this@ArtVpnService, "STOPPED:user-disconnect")
                sendStatus(ACTION_DISCONNECTED, "VPN отключён")
            }
            stopSelf()
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

    private fun promoteToForeground(text: String) {
        if (foregroundStarted) {
            updateNotification(text)
            return
        }
        // Match the Android VpnService lifecycle used by the official
        // sing-box client: start foreground immediately from onStartCommand.
        // Android reads the declared systemExempted type from the manifest.
        startForeground(NOTIFICATION_ID, notification(text))
        foregroundStarted = true
        Log.i(TAG, "Foreground mode enabled after VPN interface establishment")
    }

    private fun updateNotification(text: String) {
        if (!foregroundStarted) return
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
        if (networkMonitorDelegate.isInitialized()) runCatching { networkMonitor.close() }
        scope.cancel()
        super.onDestroy()
    }

    override fun openTun(options: TunOptions): Int {
        CrashDiagnostics.mark(this, "RUNNING:openTun-enter")
        check(VpnService.prepare(this) == null) { "Android VPN permission was revoked" }
        val builder = Builder()
            .setSession("ART VPN")
            .setMtu(options.mtu.coerceIn(1280, 1500))

        networkMonitor.currentNetwork()?.let { underlying ->
            runCatching { builder.setUnderlyingNetworks(arrayOf(underlying)) }
        }

        val v4 = options.inet4Address
        val hasV4Address = v4.hasNext()
        while (v4.hasNext()) {
            val address = v4.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val v6 = options.inet6Address
        val hasV6Address = v6.hasNext()
        while (v6.hasNext()) {
            val address = v6.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dns = options.dnsServerAddress
                while (dns.hasNext()) builder.addDnsServer(dns.next())
            }

            val v4Routes = options.inet4RouteAddress
            if (v4Routes.hasNext()) {
                while (v4Routes.hasNext()) {
                    val route = v4Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            } else if (hasV4Address) {
                builder.addRoute("0.0.0.0", 0)
            }

            val v6Routes = options.inet6RouteAddress
            if (v6Routes.hasNext()) {
                while (v6Routes.hasNext()) {
                    val route = v6Routes.next()
                    builder.addRoute(route.address(), route.prefix())
                }
            } else if (hasV6Address) {
                builder.addRoute("::", 0)
            }
        }

        CrashDiagnostics.mark(this, "RUNNING:openTun-establish")
        val descriptor = builder.establish()
            ?: error("Android не разрешил создать VPN-туннель")
        tunnel = descriptor
        // systemExempted is permitted for an active VPN, so promote only after
        // Android has created the VPN interface, not before Builder.establish().
        promoteToForeground("Подключаем защищённое соединение…")
        CrashDiagnostics.mark(this, "RUNNING:openTun-complete")
        return descriptor.fd
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        if (!protect(fd)) error("Не удалось защитить VPN-сокет от зацикливания")
    }

    override fun findConnectionOwner(
        ipProtocol: Int, sourceAddress: String, sourcePort: Int,
        destinationAddress: String, destinationPort: Int
    ): ConnectionOwner {
        val owner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uid = getSystemService(ConnectivityManager::class.java)
                .getConnectionOwnerUid(
                    ipProtocol,
                    InetSocketAddress(sourceAddress, sourcePort),
                    InetSocketAddress(destinationAddress, destinationPort)
                )
            check(uid != android.os.Process.INVALID_UID) { "Connection owner not found" }
            ConnectionOwner().apply { userId = uid }
        } else {
            Libbox.findConnectionOwner(
                ipProtocol, sourceAddress, sourcePort, destinationAddress, destinationPort
            )
        }
        val packages = packageManager.getPackagesForUid(owner.userId)?.toList().orEmpty()
        owner.userName = packages.firstOrNull().orEmpty()
        owner.setAndroidPackageNames(StringList(packages))
        return owner
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // The physical network watcher is started before the core so DNS can
        // resolve the proxy host. The native callback is attached only when
        // sing-box asks for it.
        networkMonitor.start()
        networkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        // Never retain a Go-backed listener after the core stops/reloads.
        networkMonitor.clearListener(listener)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val connectivity = getSystemService(ConnectivityManager::class.java)
            ?: return InterfaceArray(emptyList())
        val systemInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val interfaces = connectivity.allNetworks.mapNotNull { network ->
            val properties = runCatching { connectivity.getLinkProperties(network) }.getOrNull()
                ?: return@mapNotNull null
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
                ?: return@mapNotNull null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val name = properties.interfaceName ?: return@mapNotNull null
            val systemInterface = systemInterfaces.firstOrNull { it.name == name }
                ?: return@mapNotNull null

            io.nekohasekai.libbox.NetworkInterface().apply {
                this.name = name
                index = systemInterface.index
                mtu = runCatching { systemInterface.mtu }.getOrDefault(1500)
                type = when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                    else -> Libbox.InterfaceTypeOther
                }
                addresses = StringList(systemInterface.interfaceAddresses.map {
                    "${it.address.hostAddress}/${it.networkPrefixLength}"
                })
                dnsServer = StringList(properties.dnsServers.mapNotNull { it.hostAddress })
                gateway = StringList(
                    properties.routes
                        .filter { it.destination.prefixLength == 0 }
                        .mapNotNull { it.gateway }
                        .filterNot { it.isAnyLocalAddress }
                        .mapNotNull { it.hostAddress }
                )
                metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            }
        }
        return InterfaceArray(interfaces)
    }
    override fun underNetworkExtension(): Boolean = false
    override fun includeAllNetworks(): Boolean = false
    override fun clearDNSCache() = Unit
    override fun readWIFIState(): WIFIState? = null
    override fun localDNSTransport(): LocalDNSTransport? = AndroidLocalDNSTransport(networkMonitor)
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
        stopTunnel()
    }
    override fun serviceReload() = Unit
    override fun getSystemProxyStatus(): SystemProxyStatus? = null
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        Log.d("ARTVPN-singbox", message.orEmpty())
    }
    override fun connectSSHAgent(): Int = -1

    private class InterfaceArray(
        private val interfaces: List<io.nekohasekai.libbox.NetworkInterface>
    ) : NetworkInterfaceIterator {
        private val iterator = interfaces.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.NetworkInterface = iterator.next()
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
        const val ACTION_CONNECTING = "com.artvpn.app.CONNECTING"
        const val ACTION_CONNECTED = "com.artvpn.app.CONNECTED"
        const val ACTION_DISCONNECTED = "com.artvpn.app.DISCONNECTED"
        const val ACTION_FAILED = "com.artvpn.app.FAILED"
        const val EXTRA_MESSAGE = "message"
        private const val TAG = "ARTVPN"
        private const val CHANNEL_ID = "artvpn-vpn"
        private const val NOTIFICATION_ID = 41
        private const val MAX_CONNECTION_CANDIDATES = 5
        private const val HEALTH_PROBE_TIMEOUT_MS = 1600L
        private const val PREFERRED_SUCCESS_TTL_MS = 12L * 60 * 60 * 1000
        private const val FAILURE_COOLDOWN_MS = 6L * 60 * 60 * 1000

        fun start(context: android.content.Context) {
            CrashDiagnostics.mark(context, "RUNNING:request-foreground-service")
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, ArtVpnService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: android.content.Context) {
            context.startService(Intent(context, ArtVpnService::class.java).setAction(ACTION_STOP))
        }
    }
}
