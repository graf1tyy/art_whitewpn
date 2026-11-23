package com.artvpn.app.data
import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class NodeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("artvpn", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).followRedirects(true).build()

    fun cached(): List<VpnNode> = prefs.getString("nodes", null)
        ?.let { runCatching { decodeCache(it) }.getOrNull() }.orEmpty()
    fun save(nodes: List<VpnNode>) { prefs.edit().putString("nodes", encodeCache(nodes)).apply() }
    fun saveSelected(link: String) { prefs.edit().putString("selected_link", link).apply() }
    fun selectedLink(): String? = prefs.getString("selected_link", null)

    suspend fun refresh(): List<VpnNode> {
        val sources = listOf(
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        )
        var lastError: Exception? = null
        for (source in sources) try {
            val request = Request.Builder().url(source).header("User-Agent", "ART-VPN/0.1").build()
            val body = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val bytes = response.body?.bytes() ?: error("Empty response")
                check(bytes.size <= MAX_SUBSCRIPTION_BYTES) { "Subscription is too large" }
                bytes.toString(Charsets.UTF_8)
            }
            val parsed = body.lineSequence().mapNotNull(::parseVless).distinctBy { it.id }.toList()
            check(parsed.isNotEmpty()) { "No supported VLESS nodes in subscription" }
            save(parsed)
            return parsed
        } catch (e: Exception) { lastError = e }
        throw lastError ?: IllegalStateException("No subscription sources available")
    }

    private fun parseVless(line: String): VpnNode? {
        val raw = line.trim()
        if (!raw.startsWith("vless://", true)) return null
        return runCatching {
            val uri = URI(raw)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val uuid = uri.userInfo ?: return null
            require(uuid.isNotBlank())
            val remark = decode(uri.rawFragment.orEmpty()).ifBlank { host }
            val canonical = "$uuid@$host:$port?${uri.rawQuery.orEmpty()}"
            VpnNode(
                id = sha256(canonical), name = remark.take(80), host = host, port = port,
                shareLink = raw, country = findCountry(remark, host)
            )
        }.getOrNull()
    }

    private fun findCountry(name: String, host: String): String {
        val text = "$name $host".lowercase()
        val countries = linkedMapOf(
            "🇩🇪" to "Германия", "germany" to "Германия", "de-" to "Германия",
            "🇳🇱" to "Нидерланды", "netherlands" to "Нидерланды", "nl-" to "Нидерланды",
            "🇫🇮" to "Финляндия", "finland" to "Финляндия", "fi-" to "Финляндия",
            "🇫🇷" to "Франция", "france" to "Франция", "fr-" to "Франция",
            "🇺🇸" to "США", "united states" to "США", "us-" to "США",
            "🇬🇧" to "Великобритания", "united kingdom" to "Великобритания", "uk-" to "Великобритания",
            "🇸🇪" to "Швеция", "sweden" to "Швеция", "🇵🇱" to "Польша", "poland" to "Польша",
            "🇨🇿" to "Чехия", "czech" to "Чехия", "🇨🇦" to "Канада", "canada" to "Канада",
            "🇯🇵" to "Япония", "japan" to "Япония", "🇸🇬" to "Сингапур", "singapore" to "Сингапур",
            "🇹🇷" to "Турция", "turkey" to "Турция", "🇷🇺" to "Россия", "russia" to "Россия"
        )
        return countries.entries.firstOrNull { text.contains(it.key) }?.value.orEmpty()
    }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun encodeCache(nodes: List<VpnNode>): String = nodes.joinToString("\n") { node ->
        listOf(
            node.id, node.name, node.host, node.port.toString(), node.shareLink, node.country,
            node.pingMs?.toString().orEmpty(), node.reachable.toString(),
            node.measuredMbps?.toString().orEmpty()
        ).joinToString("\t") {
            android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP)
        }
    }
    private fun decodeCache(value: String): List<VpnNode> = value.lineSequence().mapNotNull { line ->
        runCatching {
            val fields = line.split("\t").map {
                String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))
            }
            VpnNode(
                id = fields[0], name = fields[1], host = fields[2], port = fields[3].toInt(),
                shareLink = fields[4], country = fields[5],
                pingMs = fields.getOrNull(6)?.toLongOrNull(),
                reachable = fields.getOrNull(7)?.toBooleanStrictOrNull() ?: false,
                measuredMbps = fields.getOrNull(8)?.toDoubleOrNull()
            )
        }.getOrNull()
    }.toList()

    companion object { private const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024 }
}
