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
        require(uri.scheme.equals("vless", true)) { "Поддерживаются только VLESS-серверы" }
        val params = query(uri.rawQuery.orEmpty())
        val uuid = requireNotNull(uri.userInfo) { "UUID не найден" }
        val host = requireNotNull(uri.host) { "Адрес сервера не найден" }
        val port = uri.port.takeIf { it in 1..65535 } ?: error("Неверный порт сервера")
        val security = params["security"].orEmpty().lowercase()
        require(security in setOf("", "none", "tls", "reality")) {
            "Неподдерживаемая защита: $security"
        }

        val tls = JSONObject()
        if (security == "tls" || security == "reality") {
            tls.put("enabled", true)
            params["sni"]?.takeIf { it.isNotBlank() }?.let { tls.put("server_name", it) }
            val fingerprint = params["fp"]?.takeIf { it.isNotBlank() }
                ?.lowercase()?.takeUnless { it.contains('%') } ?: "chrome"
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint))
            params["alpn"]?.takeIf { it.isNotBlank() && !it.contains('%') }?.let { rawAlpn ->
                val alpn = rawAlpn.split(",").map { it.trim() }.filter {
                    it.isNotBlank() && it.length <= 32
                }
                if (alpn.isNotEmpty()) tls.put("alpn", JSONArray(alpn))
            }
            val insecure = params["allowInsecure"] == "1" || params["insecure"] == "1"
            if (insecure) tls.put("insecure", true)
            if (security == "reality") {
                val publicKey = params["pbk"] ?: error("В Reality-конфигурации отсутствует pbk")
                tls.put("reality", JSONObject().put("enabled", true)
                    .put("public_key", publicKey).put("short_id", params["sid"].orEmpty()))
            }
        }

        val outbound = JSONObject().put("type", "vless").put("tag", "proxy")
            .put("server", host).put("server_port", port).put("uuid", uuid)
            .put("packet_encoding", "xudp")
            .put("domain_resolver", "local")
        params["flow"]?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }

        val transportType = params["type"].orEmpty().lowercase()
        when (transportType) {
            "", "tcp" -> Unit
            "ws" -> {
                val transport = JSONObject().put("type", "ws")
                    .put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                outbound.put("transport", transport)
            }
            "grpc" -> {
                val transport = JSONObject().put("type", "grpc")
                (params["serviceName"] ?: params["service_name"])
                    ?.takeIf { it.isNotBlank() }?.let { transport.put("service_name", it) }
                outbound.put("transport", transport)
            }
            "http" -> {
                val transport = JSONObject().put("type", "http")
                    .put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("host", JSONArray().put(it))
                }
                outbound.put("transport", transport)
            }
            "httpupgrade" -> {
                val transport = JSONObject().put("type", "httpupgrade")
                    .put("path", params["path"] ?: "/")
                params["host"]?.takeIf { it.isNotBlank() }?.let {
                    transport.put("headers", JSONObject().put("Host", it))
                }
                outbound.put("transport", transport)
            }
            "xhttp" -> error("XHTTP не поддерживается используемой версией sing-box")
            else -> error("Неподдерживаемый транспорт: $transportType")
        }
        if (tls.length() > 0) outbound.put("tls", tls)

        val outbounds = JSONArray()
            .put(outbound)
            .put(JSONObject().put("type", "direct").put("tag", "direct"))
        val config = JSONObject()
            .put("log", JSONObject().put("level", "warn").put("timestamp", true))
            .put("dns", JSONObject()
                .put("servers", JSONArray()
                    .put(JSONObject().put("type", "local").put("tag", "local")))
                .put("final", "local"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "tun").put("tag", "tun-in").put("interface_name", "artvpn0")
                .put("address", JSONArray()
                    .put("172.19.0.1/30")
                    .put("fdfe:dcba:9876::1/126"))
                .put("mtu", 1380).put("auto_route", true).put("strict_route", true)))
            .put("outbounds", outbounds)
            .put("route", JSONObject()
                .put("override_android_vpn", true)
                .put("rules", JSONArray().put(
                    JSONObject().put("protocol", "dns").put("action", "hijack-dns")
                ))
                .put("final", "proxy"))
        return config.toString()
    }

    private fun query(raw: String): Map<String, String> = raw.split("&")
        .filter { it.isNotBlank() }
        .associate { part ->
            val key = part.substringBefore("=")
            decode(key) to decode(part.substringAfter("=", ""))
        }

    private fun decode(value: String): String =
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
}
