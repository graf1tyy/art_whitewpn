package io.nekohasekai.sfa.artvpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Creates one native sing-box profile with a urltest outbound group.
 * The sing-box core, VPN service, tun handling and network monitor are the
 * upstream SagerNet Android implementation; ART only provides server feeds.
 */
object ArtAutoProfileManager {
    private const val TAG = "ART-AUTO"
    private const val PROFILE_NAME = "ART Auto"
    private const val PREFS = "art_auto_profile"
    private const val KEY_UPDATED = "updated_at"
    private const val KEY_PROFILE_REVISION = "profile_revision"
    private const val PROFILE_REVISION = 13
    private const val REFRESH_INTERVAL = 60L * 60 * 1000
    private const val MAX_BODY = 4 * 1024 * 1024
    private const val MAX_NODES = 192
    private val uuidPattern = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
    private val refreshLock = Mutex()
    private val supportedFingerprints = setOf(
        "chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle",
        "chrome_pq", "chrome_pq_psk", "firefox", "edge", "safari", "360",
        "qq", "ios", "android", "random", "randomized"
    )

    // Start with a separately health-checked mobile VLESS bundle. The source
    // rebuilds its list from measured configs rather than merely aggregating
    // arbitrary public links. Keep the Russia-focused feeds as additional
    // candidates, but never let one feed monopolize the pool.
    private val feedGroups = listOf(
        // These two sources publish continuously refreshed, pre-tested VLESS
        // candidates. Keep them first; the mobile whitelist feeds below remain
        // as fallback pools for operators that restrict outbound destinations.
        listOf(
            "https://raw.githubusercontent.com/mehrtat/vless-collector/main/vless.txt",
            "https://cdn.jsdelivr.net/gh/mehrtat/vless-collector@main/vless.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/morpheusadam/v2ray-config/main/subs/bundles/best.txt",
            "https://cdn.jsdelivr.net/gh/morpheusadam/v2ray-config@main/subs/bundles/best.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/zieng2/wl/main/vless_universal.txt",
            "https://codeberg.org/zieng2/wl/raw/branch/main/vless_universal.txt",
            "https://gitlab.com/zieng2/wl/raw/main/vless_universal.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt"
        )
    )

    suspend fun installOrRefresh(context: Context, force: Boolean = false) = refreshLock.withLock {
        withContext(Dispatchers.IO) {
        val app = context.applicationContext
        Log.i(TAG, "Profile discovery entered force=$force")
        val profiles = ProfileManager.list()
        val existing = profiles.firstOrNull { it.name == PROFILE_NAME }
        Log.i(TAG, "Profile database ready: count=${profiles.size}, artAutoPresent=${existing != null}")
        val preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastUpdated = preferences.getLong(KEY_UPDATED, 0L)
        val savedRevision = preferences.getInt(KEY_PROFILE_REVISION, 0)

        // Never let a stale third-party/default profile run while ART is still
        // preparing its first server set. A failed first import leaves Connect disabled.
        if (existing == null && Settings.selectedProfile != -1L) {
            Settings.selectedProfile = -1L
            Settings.rebuildServiceMode()
            Log.i(TAG, "Cleared the non-ART profile while preparing ART Auto")
        }

        // ART is a single-purpose VPN: never start an older imported/default
        // profile just because it remained selected in the upstream database.
        // This also repairs installations upgraded from builds that left a
        // non-ART profile selected.
        val selectionChanged = existing != null && Settings.selectedProfile != existing.id
        if (selectionChanged) {
            Settings.selectedProfile = existing.id
            Settings.rebuildServiceMode()
            Log.i(TAG, "Forced ART Auto profile selection id=${existing.id}, serviceMode=${Settings.serviceMode}")
        }

        if (!force && existing != null && savedRevision == PROFILE_REVISION &&
            System.currentTimeMillis() - lastUpdated < REFRESH_INTERVAL) {
            if (selectionChanged) reloadRunningService()
            return@withContext
        }

        // Bind probes to Wi-Fi/mobile data, never to the VPN being refreshed.
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        // Android may start Application before Wi-Fi/mobile data is registered.
        // Wait briefly instead of permanently leaving Connect disabled.
        val physicalNetwork: Network? = withTimeoutOrNull(20_000L) {
            var selected: Network? = null
            while (selected == null) {
                selected = runCatching {
                    connectivity?.let { manager ->
                        val usable: (Network) -> Boolean = { network ->
                            val capabilities = manager.getNetworkCapabilities(network)
                            capabilities != null &&
                                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        }
                        manager.activeNetwork?.takeIf(usable) ?: manager.allNetworks.firstOrNull(usable)
                    }
                }.onFailure {
                    Log.w(TAG, "Unable to inspect Android networks: ${it.message}", it)
                }.getOrNull()
                if (selected == null) delay(500L)
            }
            selected
        }
        if (physicalNetwork == null) {
            Log.e(TAG, "No physical internet network after 20 seconds; keeping the current ART Auto profile")
            if (existing != null) return@withContext
            error("Android не предоставил приложению физическую сеть с доступом в интернет.")
        }
        Log.i(TAG, "Using physical network for ART feed discovery")

        val feedResults = coroutineScope {
            feedGroups.map { mirrors -> async { fetchFirstValid(mirrors, physicalNetwork) } }.awaitAll()
        }
        Log.i(TAG, "Android feeds returned ${feedResults.sumOf { it.size }} links across ${feedResults.count { it.isNotEmpty() }} feed groups")
        // Interleave sources so the largest feed cannot monopolize the first
        // 48 candidates. Each source contributes before any source contributes
        // its second entry; deduplication then removes overlapping endpoints.
        val links = buildList {
            val maxFeedSize = feedResults.maxOfOrNull { it.size } ?: 0
            for (index in 0 until maxFeedSize) {
                feedResults.forEach { feed -> feed.getOrNull(index)?.let(::add) }
            }
        }
        // Prefer Chrome uTLS variants when a feed publishes the same endpoint
        // several times. Probe only configurations the bundled core supports.
        val uniqueLinks = links.sortedBy(::fingerprintRank)
            .distinctBy { nodeIdentity(it) ?: it }
            .filter { compileVless(it) != null }
            .take(320)
        if (uniqueLinks.isEmpty()) {
            Log.e(TAG, "No supported VLESS configs parsed: links=${links.size}, feedGroups=${feedResults.count { it.isNotEmpty() }}")
            error("Не удалось загрузить поддерживаемые VLESS-конфигурации; текущий профиль ART Auto сохранён.")
        }
        // Give the native URLTest group a wider pool than one CI probe pass.
        // It selects only nodes that complete VLESS + TLS + HTTPS 204; a TCP-open
        // endpoint alone is never treated as a working VPN server.
        val outbounds = uniqueLinks.take(MAX_NODES).mapNotNull(::compileVless)
        Log.i(TAG, "Server pool prepared: links=${links.size}, uniqueSupported=${uniqueLinks.size}, candidates=${outbounds.size}")
        if (outbounds.isEmpty()) {
            if (existing != null) {
                // A temporary carrier outage or feed change must not destroy the
                // last-known profile. Keep it selected and let the user retry later.
                Log.w(TAG, "No supported VLESS candidates; keeping the previous ART Auto profile")
                return@withContext
            }
            error("Не найдено поддерживаемых VLESS-серверов. Существующий профиль не удалён.")
        }

        val nodeTags = JSONArray()
        outbounds.forEach { nodeTags.put(it.getString("tag")) }
        val allOutbounds = JSONArray()
        outbounds.forEach { allOutbounds.put(it) }
        allOutbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", "art-auto")
                .put("outbounds", nodeTags)
                .put("url", "https://www.gstatic.com/generate_204")
                .put("interval", "30s")
                .put("tolerance", 60)
                .put("interrupt_exist_connections", true)
        )
        allOutbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val config = JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("dns", JSONObject()
                .put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local")))
                .put("final", "local"))
            .put("inbounds", JSONArray().put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("interface_name", "arttun0")
                    .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
                    .put("mtu", 1380)
                    .put("auto_route", true)
                    .put("strict_route", true)
            ))
            .put("outbounds", allOutbounds)
            .put("route", JSONObject()
                .put("override_android_vpn", true)
                .put("rules", JSONArray().put(
                    JSONObject().put("protocol", "dns").put("action", "hijack-dns")
                ))
                .put("final", "art-auto"))
            .toString()

        // Let the exact bundled sing-box version reject invalid JSON/schema before
        // saving it as the active profile.
        Log.i(TAG, "Validating generated sing-box config with ${outbounds.size} VLESS outbounds")
        Libbox.checkConfig(config)
        Log.i(TAG, "Generated sing-box config passed native validation")

        val target = existing ?: Profile(name = PROFILE_NAME, typed = TypedProfile())
        target.name = PROFILE_NAME
        target.typed.type = TypedProfile.Type.Local
        target.typed.remoteURL = ""
        val configFile = if (existing != null && target.typed.path.isNotBlank()) {
            java.io.File(target.typed.path)
        } else {
            val directory = java.io.File(app.filesDir, "configs").apply { mkdirs() }
            java.io.File(directory, "art-auto.json")
        }
        configFile.parentFile?.mkdirs()
        configFile.writeText(config)
        target.typed.path = configFile.absolutePath

        if (existing == null) {
            target.userOrder = ProfileManager.nextOrder()
            ProfileManager.create(target, andSelect = true)
        } else {
            ProfileManager.update(target)
        }
        Log.i(TAG, "ART Auto profile row saved id=${target.id}, path=${target.typed.path}")

        // ProfileManager.update() deliberately preserves upstream selection.
        // For ART VPN, the generated ART Auto TUN profile must always be active.
        Settings.selectedProfile = target.id
        Settings.rebuildServiceMode()
        Log.i(TAG, "ART Auto active profile id=${target.id}, serviceMode=${Settings.serviceMode}")
        reloadRunningService()

        preferences.edit()
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .putInt(KEY_PROFILE_REVISION, PROFILE_REVISION)
            .apply()
        Log.i(TAG, "Installed ART Auto profile with ${outbounds.size} unique VLESS candidates (from ${links.size} links)")
        }
    }

    private fun reloadRunningService() {
        runCatching {
            Libbox.newStandaloneCommandClient().serviceReload()
        }.onSuccess {
            Log.i(TAG, "Reloaded the active service with the ART Auto profile")
        }.onFailure {
            // On a cold start there is no command server yet; the next Connect
            // starts the selected profile directly.
            Log.d(TAG, "No running service to reload: ${it.message}")
        }
    }

    private suspend fun fetchFirstValid(mirrors: List<String>, network: Network): List<String> = coroutineScope {
        val found = CompletableDeferred<List<String>>()
        val jobs = mirrors.map { source ->
            async {
                val body = runCatching { fetch(source, network) }.onFailure {
                    Log.d(TAG, "Feed mirror failed: ${URI(source).host}: ${it.message}")
                }.getOrNull() ?: return@async
                val parsed = Regex("""(?i)vless://[^\s"'<>]+""")
                    .findAll(body.replace("&amp;", "&"))
                    .map { it.value.trimEnd(',', ';', ')', ']', '.') }
                    .filter { compileVless(it) != null }
                    .toList()
                if (parsed.isNotEmpty()) found.complete(parsed)
            }
        }
        val winner = withTimeoutOrNull(7000L) { found.await() }.orEmpty()
        jobs.forEach { it.cancel() }
        if (winner.isEmpty()) Log.w(TAG, "No VLESS configurations received from any feed mirror")
        winner
    }

    private fun fetch(source: String, network: Network): String {
        // Downloads must use the physical network, not a previously running VPN.
        val connection = (network.openConnection(URL(source)) as HttpURLConnection).apply {
            connectTimeout = 3500
            readTimeout = 4500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "ART-VPN-Android/1.0")
            setRequestProperty("Accept", "text/plain,*/*")
        }
        try {
            val length = connection.contentLengthLong
            require(length < 0 || length <= MAX_BODY) { "Feed too large" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BODY) { "Feed too large" }
                    output.write(buffer, 0, count)
                }
                output.toString(Charsets.UTF_8.name())
            }
        } finally {
            connection.disconnect()
        }
    }
    private fun fingerprintRank(link: String): Int = runCatching {
        when (query(URI(link.trim()).rawQuery.orEmpty())["fp"].orEmpty().lowercase()) {
            "", "chrome" -> 0
            "edge" -> 1
            "firefox" -> 2
            "safari" -> 3
            "android" -> 4
            else -> 5
        }
    }.getOrDefault(10)

    private fun nodeIdentity(link: String): String? = runCatching {
        val uri = URI(link.trim())
        val uuid = uri.userInfo ?: error("missing uuid")
        val host = uri.host?.lowercase() ?: error("missing host")
        val port = uri.port
        require(port in 1..65535)
        val params = query(uri.rawQuery.orEmpty())
        val transport = params["type"].orEmpty().lowercase().let {
            if (it == "tcp" || it == "raw") "" else it
        }
        listOf(
            uuid.lowercase(), host, port.toString(),
            params["security"].orEmpty().lowercase().let { if (it == "none") "" else it },
            transport, params["flow"].orEmpty(),
            params["sni"].orEmpty().lowercase(), params["pbk"].orEmpty(),
            params["sid"].orEmpty().lowercase(), params["alpn"].orEmpty().lowercase(),
            params["host"].orEmpty().lowercase(), params["path"].orEmpty(),
            (params["serviceName"] ?: params["service_name"]).orEmpty(),
            params["insecure"].orEmpty(), params["allowInsecure"].orEmpty()
        ).joinToString("\u001f")
    }.getOrNull()

    private fun compileVless(link: String): JSONObject? = runCatching {
        val uri = URI(link.trim())
        require(uri.scheme.equals("vless", true))
        val uuid = uri.userInfo ?: error("missing uuid")
        require(uuidPattern.matches(uuid))
        val host = uri.host ?: error("missing host")
        val port = uri.port
        require(port in 1..65535)
        val params = query(uri.rawQuery.orEmpty())
        val security = params["security"].orEmpty().lowercase()
        require(security in setOf("", "none", "tls", "reality"))
        // Several VLESS subscriptions use "raw" for ordinary TCP transport.
        // sing-box represents that as its default transport (no transport block).
        val transportType = params["type"].orEmpty().lowercase().let {
            if (it == "raw") "tcp" else it
        }
        require(transportType in setOf("", "tcp", "ws", "grpc", "http", "httpupgrade"))
        val flow = params["flow"].orEmpty()
        require(flow.isBlank() || flow == "xtls-rprx-vision")
        if (flow.isNotBlank()) require(security == "tls" || security == "reality")

        val identity = nodeIdentity(link) ?: error("invalid node identity")
        val tag = "art-" + sha256(identity).take(16)
        val outbound = JSONObject()
            .put("type", "vless")
            .put("tag", tag)
            .put("server", host)
            .put("server_port", port)
            .put("uuid", uuid)
            .put("packet_encoding", "xudp")
            .put("domain_resolver", "local")
        if (flow.isNotBlank()) outbound.put("flow", flow)

        if (security == "tls" || security == "reality") {
            val tls = JSONObject().put("enabled", true)
            params["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            val fingerprint = (params["fp"]?.takeIf { it.isNotBlank() } ?: "chrome").lowercase()
            require(fingerprint in supportedFingerprints)
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            params["alpn"]?.takeIf { it.isNotBlank() }?.let {
                tls.put("alpn", JSONArray(it.split(",").map(String::trim).filter(String::isNotBlank)))
            }
            if (security == "reality") {
                val key = params["pbk"] ?: error("Reality pbk missing")
                tls.put("reality", JSONObject().put("enabled", true).put("public_key", key)
                    .put("short_id", params["sid"].orEmpty()))
            }
            if (params["allowInsecure"] == "1" || params["insecure"] == "1") tls.put("insecure", true)
            outbound.put("tls", tls)
        }

        when (transportType) {
            "", "tcp" -> Unit
            "ws" -> {
                val transport = JSONObject().put("type", "ws").put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("headers", JSONObject().put("Host", it)) }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                (params["serviceName"] ?: params["service_name"])?.takeIf { it.isNotBlank() }
                    ?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            "http" -> {
                val transport = JSONObject().put("type", "http").put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("host", JSONArray().put(it)) }
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade").put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("headers", JSONObject().put("Host", it)) }
                outbound.put("transport", transport)
            }
        }
        outbound
    }.getOrNull()

    private fun query(raw: String): Map<String, String> = raw.split("&")
        .filter { it.isNotBlank() }
        .associate { part ->
            decode(part.substringBefore("=")) to decode(part.substringAfter("=", ""))
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
