package com.artvpn.app.vpn
import com.artvpn.app.data.VpnNode
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

object VpnConfigCompiler {
    fun compile(node: VpnNode): String {
        val uri = URI(node.shareLink)
        require(uri.scheme.equals("vless", true)) { "Пока поддерживаются только VLESS-серверы" }
        val params = query(uri.rawQuery.orEmpty())
        val uuid = requireNotNull(uri.userInfo) { "UUID не найден" }
        val host = requireNotNull(uri.host) { "Адрес сервера не найден" }
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Неверный порт сервера")
        val security = params["security"].orEmpty()
        val tls = JSONObject()
        if (security == "tls" || security == "reality") {
            tls.put("enabled", true)
            params["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", params["fp"] ?: "chrome"))
            if (security == "reality") {
                val publicKey = params["pbk"] ?: error("В Reality-конфигурации отсутствует pbk")
                tls.put("reality", JSONObject().put("enabled", true)
                    .put("public_key", publicKey).put("short_id", params["sid"].orEmpty()))
            }
        }
        val outbound = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", host).put("server_port", port).put("uuid", uuid).put("packet_encoding", "xudp")
        params["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        if (tls.length() > 0) outbound.put("tls", tls)
        when (params["type"]?.lowercase()) {
            "ws" -> {
                val transport = JSONObject().put("type", "ws").put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let { transport.put("headers", JSONObject().put("Host", it)) }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                params["serviceName"]?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            "tcp", null, "" -> Unit
            else -> error("Неподдерживаемый транспорт: ${params["type"]}")
        }
        val outbounds = JSONArray().put(outbound).put(JSONObject().put("type", "direct").put("tag", "direct"))
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "local"))).put("final", "local"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in").put("interface_name", "artvpn0")
                .put("address", JSONArray().put("172.19.0.1/30")).put("mtu", 1400)
                .put("auto_route", true).put("strict_route", false)))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("auto_detect_interface", true)
                .put("rules", JSONArray().put(
                    JSONObject().put("protocol", "dns").put("action", "hijack-dns")
                ))
                .put("final", "proxy"))
        return config.toString()
    }
    private fun query(raw: String): Map<String, String> = raw.split("&").filter { it.isNotBlank() }
        .associate { decode(it.substringBefore("=")) to decode(it.substringAfter("=", "")) }
    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}
