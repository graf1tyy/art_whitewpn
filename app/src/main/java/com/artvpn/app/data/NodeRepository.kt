package com.artvpn.app.data

import android.content.Context
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class NodeRepository(context: Context) {
    private val prefs = context.getSharedPreferences("artvpn", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(true)
        .build()

    fun cached(): List<VpnNode> = prefs.getString("nodes", null)
        ?.let { runCatching { decodeCache(it) }.getOrNull() }.orEmpty()

    fun save(nodes: List<VpnNode>) {
        prefs.edit().putString("nodes", encodeCache(nodes)).apply()
    }

    fun saveSelected(link: String) {
        prefs.edit().putString("selected_link", link).apply()
    }

    fun selectedLink(): String? = prefs.getString("selected_link", null)

    /**
     * Fetch both official subscription files concurrently. For each file, race
     * the documented mirrors and keep the first valid, supported VLESS list.
     * A failed mirror must never hold the user behind a long serial timeout.
     */
    suspend fun refresh(): List<VpnNode> = withContext(Dispatchers.IO) {
        val previous = cached().associateBy { it.id }
        // Primary whitelist feeds plus a separately maintained RU/CIS VLESS pool.
        // The phone still validates real tunnel traffic before trusting any node.
        val feeds = listOf(
            sourceUrls("Vless-Reality-White-Lists-Rus-Mobile.txt"),
            sourceUrls("WHITE-CIDR-RU-all.txt"),
            listOf(
                "https://raw.githubusercontent.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni/vless.txt",
                "https://raw.githack.com/kort0881/vpn-vless-configs-russia/main/data/githubmirror/ru-sni/vless.txt"
            )
        )
        val fetched = coroutineScope {
            feeds.map { urls -> async { fetchFirstAvailable(urls) } }.awaitAll()
        }

        val merged = linkedMapOf<String, VpnNode>()
        for (parsed in fetched) {
            for (node in parsed) {
                val old = previous[node.id]
                merged[node.id] = node.copy(
                    lastSuccessAt = old?.lastSuccessAt,
                    lastFailureAt = old?.lastFailureAt,
                    failureCount = old?.failureCount ?: 0,
                    measuredMbps = old?.measuredMbps
                )
            }
        }

        check(merged.isNotEmpty()) {
            "Не удалось получить совместимые серверы ни с одного зеркала. " +
                "Проверьте интернет и попробуйте ещё раз."
        }
        val result = merged.values.toList()
        save(result)
        result
    }

    private suspend fun fetchFirstAvailable(urls: List<String>): List<VpnNode> = coroutineScope {
        val result = CompletableDeferred<List<VpnNode>>()
        val remaining = AtomicInteger(urls.size)
        val jobs = urls.map { source ->
            launch {
                try {
                    val parsed = runCatching { requestNodes(source) }.getOrDefault(emptyList())
                    if (parsed.isNotEmpty()) result.complete(parsed)
                } finally {
                    if (remaining.decrementAndGet() == 0) result.complete(emptyList())
                }
            }
        }

        val winner = withTimeoutOrNull(MIRROR_RACE_TIMEOUT_MS) { result.await() }.orEmpty()
        jobs.forEach { it.cancel() }
        winner
    }

    private suspend fun requestNodes(source: String): List<VpnNode> =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(source)
                .header("User-Agent", "ART-VPN/0.3 Android")
                .header("Accept", "text/plain,*/*")
                .build()
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: java.io.IOException) {
                    if (continuation.isActive) continuation.resume(emptyList())
                }

                override fun onResponse(call: Call, response: Response) {
                    val parsed = runCatching {
                        response.use {
                            if (!it.isSuccessful) return@use emptyList()
                            val body = it.body ?: return@use emptyList()
                            if (body.contentLength() > MAX_SUBSCRIPTION_BYTES) return@use emptyList()
                            val bytes = body.bytes()
                            if (bytes.size > MAX_SUBSCRIPTION_BYTES) return@use emptyList()
                            extractVless(bytes.toString(Charsets.UTF_8))
                                .mapNotNull(::parseVless)
                                .distinctBy { node -> node.id }
                                .toList()
                        }
                    }.getOrDefault(emptyList())
                    if (continuation.isActive) continuation.resume(parsed)
                }
            })
        }

    private fun sourceUrls(path: String): List<String> {
        val bitbucket = "https://bitbucket.org/igareck/vpn-configs-for-russia/raw/main/$path"
        return listOf(
            "https://translate.yandex.ru/translate?url=$bitbucket&lang=de-de",
            "https://gitlab.com/igareck/vpn-configs-for-russia/-/raw/main/$path",
            "https://raw.githubusercontent.com/igareck/vpn-configs-for-russia/main/$path",
            "https://codeberg.org/igareck/vpn-configs-for-russia/raw/branch/main/$path",
            "https://gitea.com/igareck/vpn-configs-for-russia/raw/branch/main/$path",
            bitbucket,
            "https://raw.githack.com/igareck/vpn-configs-for-russia/main/$path"
        )
    }

    private fun extractVless(content: String): Sequence<String> {
        val htmlSafe = content.replace("&amp;", "&")
        val regex = Regex("""(?i)vless://[^\s"'<>]+""")
        return regex.findAll(htmlSafe).map { match ->
            match.value.trimEnd(',', ';', ')', ']', '.')
        }
    }

    private fun parseVless(line: String): VpnNode? {
        val raw = line.trim()
        if (!raw.startsWith("vless://", true)) return null
        return runCatching {
            val uri = URI(raw)
            val host = uri.host ?: return null
            val port = uri.port.takeIf { it in 1..65535 } ?: return null
            val uuid = uri.userInfo ?: return null
            require(UUID_PATTERN.matches(uuid)) { "Invalid VLESS UUID" }

            val params = query(uri.rawQuery.orEmpty())
            val transport = params["type"].orEmpty().lowercase()
            require(transport in SUPPORTED_TRANSPORTS) {
                "Unsupported VLESS transport: $transport"
            }
            val security = params["security"].orEmpty().lowercase()
            require(security in SUPPORTED_SECURITY)
            val flow = params["flow"].orEmpty()
            require(flow in SUPPORTED_FLOWS) { "Unsupported VLESS flow: $flow" }
            require(flow.isBlank() || security in setOf("tls", "reality")) {
                "VLESS vision flow requires TLS or Reality"
            }
            if (security == "reality") {
                require(!params["pbk"].isNullOrBlank()) { "Reality public key missing" }
                require(!params["sni"].isNullOrBlank()) { "Reality SNI missing" }
            }

            val remark = decode(uri.rawFragment.orEmpty()).ifBlank { host }
            val identityParts = listOf(
                uuid, host.lowercase(), port.toString(), security, transport,
                params["sni"].orEmpty(), params["path"].orEmpty(),
                params["host"].orEmpty(), params["serviceName"].orEmpty(),
                params["service_name"].orEmpty(), params["flow"].orEmpty(),
                params["pbk"].orEmpty(), params["sid"].orEmpty(),
                params["fp"].orEmpty(), params["alpn"].orEmpty()
            )
            val canonical = identityParts.joinToString("|")
            VpnNode(
                id = sha256(canonical),
                name = remark.take(80),
                host = host,
                port = port,
                shareLink = raw,
                country = findCountry(remark, host)
            )
        }.getOrNull()
    }

    private fun query(raw: String): Map<String, String> = raw.split("&")
        .filter { it.isNotBlank() }
        .associate { part ->
            val key = part.substringBefore("=")
            decode(key) to decode(part.substringAfter("=", ""))
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
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /**
     * Carrier outcomes stay local. A failure on one operator must not poison
     * a server for every other operator; two recent failures are needed before
     * it is temporarily pushed down the list.
     */
    fun rankForOperator(nodes: List<VpnNode>, operatorKey: String?): List<VpnNode> {
        if (operatorKey.isNullOrBlank()) return nodes.sortedWith(NodeTester.priorityComparator())
        val histories = nodes.associate { it.id to readOperatorHistory(operatorKey, it.shareLink) }
        val now = System.currentTimeMillis()
        val base = NodeTester.priorityComparator()
        return nodes.sortedWith(Comparator { first, second ->
            val a = histories[first.id] ?: OperatorHistory()
            val b = histories[second.id] ?: OperatorHistory()
            val aPenalty = if (a.failureCount >= OPERATOR_FAILURE_THRESHOLD &&
                now - a.lastFailureAt < OPERATOR_FAILURE_COOLDOWN_MS) 1 else 0
            val bPenalty = if (b.failureCount >= OPERATOR_FAILURE_THRESHOLD &&
                now - b.lastFailureAt < OPERATOR_FAILURE_COOLDOWN_MS) 1 else 0
            val aSuccess = a.lastSuccessAt.takeIf {
                it > a.lastFailureAt && now - it < OPERATOR_SUCCESS_TTL_MS
            } ?: 0L
            val bSuccess = b.lastSuccessAt.takeIf {
                it > b.lastFailureAt && now - it < OPERATOR_SUCCESS_TTL_MS
            } ?: 0L
            val penaltyCompare = aPenalty.compareTo(bPenalty)
            if (penaltyCompare != 0) penaltyCompare else {
                val successCompare = bSuccess.compareTo(aSuccess)
                if (successCompare != 0) successCompare else base.compare(first, second)
            }
        })
    }

    fun hasRecentOperatorSuccess(link: String, operatorKey: String?, maxAgeMs: Long): Boolean {
        if (operatorKey.isNullOrBlank()) return false
        val history = readOperatorHistory(operatorKey, link)
        return history.lastSuccessAt > history.lastFailureAt &&
            System.currentTimeMillis() - history.lastSuccessAt < maxAgeMs
    }

    private fun readOperatorHistory(operatorKey: String, link: String): OperatorHistory {
        val fields = prefs.getString(operatorHistoryKey(operatorKey, link), null)?.split('|').orEmpty()
        return OperatorHistory(
            lastSuccessAt = fields.getOrNull(0)?.toLongOrNull() ?: 0L,
            lastFailureAt = fields.getOrNull(1)?.toLongOrNull() ?: 0L,
            failureCount = fields.getOrNull(2)?.toIntOrNull() ?: 0
        )
    }

    private fun operatorHistoryKey(operatorKey: String, link: String): String =
        "operator_${sha256(operatorKey).take(16)}_${sha256(link).take(24)}"

    fun markSuccessful(link: String, operatorKey: String? = null) {
        val now = System.currentTimeMillis()
        save(cached().map { node ->
            if (node.shareLink == link) node.copy(
                lastSuccessAt = now,
                lastFailureAt = null,
                failureCount = 0
            ) else node
        })
        if (!operatorKey.isNullOrBlank()) {
            prefs.edit().putString(operatorHistoryKey(operatorKey, link), "$now|0|0").apply()
        }
    }

    fun markFailed(link: String, operatorKey: String? = null) {
        if (operatorKey.isNullOrBlank()) {
            val now = System.currentTimeMillis()
            save(cached().map { node ->
                if (node.shareLink == link) node.copy(
                    lastFailureAt = now,
                    failureCount = (node.failureCount + 1).coerceAtMost(20)
                ) else node
            })
            return
        }
        val old = readOperatorHistory(operatorKey, link)
        val now = System.currentTimeMillis()
        val failures = (old.failureCount + 1).coerceAtMost(20)
        prefs.edit().putString(
            operatorHistoryKey(operatorKey, link),
            "${old.lastSuccessAt}|$now|$failures"
        ).apply()
    }

    private data class OperatorHistory(
        val lastSuccessAt: Long = 0L,
        val lastFailureAt: Long = 0L,
        val failureCount: Int = 0
    )

    private fun encodeCache(nodes: List<VpnNode>): String = nodes.joinToString("\n") { node ->
        listOf(
            node.id, node.name, node.host, node.port.toString(), node.shareLink, node.country,
            node.pingMs?.toString().orEmpty(), node.reachable.toString(),
            node.measuredMbps?.toString().orEmpty(), node.lastSuccessAt?.toString().orEmpty(),
            node.lastFailureAt?.toString().orEmpty(), node.failureCount.toString()
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
                measuredMbps = fields.getOrNull(8)?.toDoubleOrNull(),
                lastSuccessAt = fields.getOrNull(9)?.toLongOrNull(),
                lastFailureAt = fields.getOrNull(10)?.toLongOrNull(),
                failureCount = fields.getOrNull(11)?.toIntOrNull() ?: 0
            )
        }.getOrNull()
    }.toList()

    companion object {
        private const val MAX_SUBSCRIPTION_BYTES = 4 * 1024 * 1024L
        private const val MIRROR_RACE_TIMEOUT_MS = 7_500L
        private const val OPERATOR_FAILURE_THRESHOLD = 2
        private const val OPERATOR_FAILURE_COOLDOWN_MS = 30L * 60 * 1000
        private const val OPERATOR_SUCCESS_TTL_MS = 7L * 24 * 60 * 60 * 1000
        private val UUID_PATTERN = Regex(
            "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        )
        private val SUPPORTED_TRANSPORTS = setOf("", "tcp", "ws", "grpc", "http", "httpupgrade")
        private val SUPPORTED_SECURITY = setOf("", "none", "tls", "reality")
        private val SUPPORTED_FLOWS = setOf("", "xtls-rprx-vision")
    }
}
