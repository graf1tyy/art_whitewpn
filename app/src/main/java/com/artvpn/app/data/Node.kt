package com.artvpn.app.data
data class VpnNode(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val shareLink: String,
    val country: String,
    val protocol: String = "VLESS",
    val pingMs: Long? = null,
    val reachable: Boolean = false,
    val measuredMbps: Double? = null,
) {
    val pingLabel: String get() = pingMs?.let { "$it мс" } ?: "—"
    val countryLabel: String get() = country.ifBlank { "Страна неизвестна" }
}
