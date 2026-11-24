package io.nekohasekai.sfa.artvpn

import android.content.Context
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
    private const val REFRESH_INTERVAL = 4L * 60 * 60 * 1000
    private const val MAX_BODY = 4 * 1024 * 1024
    private const val MAX_NODES = 50
    private val uuidPattern = Regex("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val feedGroups = listOf(
        listOf(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/WHITE-CIDR-RU-all.txt",
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt"
        ),
        listOf(
            "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni/vless.txt",
            "https://raw.githack.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni/vless.txt"
        )
    )

    suspend fun installOrRefresh(context: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val profiles = ProfileManager.list()
        val existing = profiles.firstOrNull { it.name == PROFILE_NAME }
        val lastUpdated = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_UPDATED, 0L)
        if (!force && existing != null && System.currentTimeMillis() - lastUpdated < REFRESH_INTERVAL) {
            return@withContext
        }

        val links = coroutineScope {
            feedGroups.map { mirrors -> async { fetchFirstValid(mirrors) } }.awaitAll()
        }.flatten().distinct()
        val outbounds = links.mapNotNull(::compileVless).distinctBy { it.optString("tag") }.take(MAX_NODES)
        check(outbounds.isNotEmpty()) { "Не удалось получить рабочий список VLESS-конфигураций" }

        val nodeTags = JSONArray()
        outbounds.forEach { nodeTags.put(it.getString("tag")) }
        val allOutbounds = JSONArray()
        outbounds.forEach { allOutbounds.put(it) }
        allOutbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", "art-auto")
                .put("outbounds", nodeTags)
                .put("url", "https://cp.cloudflare.com/generate_204")
                .put("interval", "3m")
                .put("tolerance", 60)
        )
        allOutbounds.put(JSONObject().put("type", "direct").put("tag", "direct"))

        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
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
        Libbox.checkConfig(config)

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
            if (Settings.selectedProfile == -1L) Settings.selectedProfile = target.id
        }

        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_UPDATED, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Installed ART Auto profile with ${outbounds.size} VLESS candidates")
    }

    private suspend fun fetchFirstValid(mirrors: List<String>): List<String> = coroutineScope {
        val found = CompletableDeferred<List<String>>()
        val jobs = mirrors.map { source ->
            async {
                val body = runCatching { fetch(source) }.getOrNull() ?: return@async
                val parsed = Regex("(?i)vless://[^\\\\s\\\"'<>]+")
                    .findAll(body.replace("&amp;", "&"))
                    .map { it.value.trimEnd(',', ';', ')', ']', '.') }
                    .filter { compileVless(it) != null }
                    .toList()
                if (parsed.isNotEmpty()) found.complete(parsed)
            }
        }
        val winner = withTimeoutOrNull(7000L) { found.await() }.orEmpty()
        jobs.forEach { it.cancel() }
        winner
    }

    private fun fetch(source: String): String {
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
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
        val transportType = params["type"].orEmpty().lowercase()
        require(transportType in setOf("", "tcp", "ws", "grpc", "http", "httpupgrade"))
        val flow = params["flow"].orEmpty()
        require(flow.isBlank() || flow == "xtls-rprx-vision")
        if (flow.isNotBlank()) require(security == "tls" || security == "reality")

        val tag = "art-" + sha256("$uuid|$host|$port|$security|$transportType|$link").take(16)
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
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() } ?: "chrome"
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
