package com.artvpn.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * A local-only profile for the active physical network. On cellular networks
 * the profile is keyed by the registered operator; Wi-Fi is kept separate so
 * Wi-Fi failures never blacklist a server for a mobile carrier.
 */
object NetworkProfile {
    fun activeNetworkProfile(context: Context): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        val active = connectivity.activeNetwork ?: return@runCatching null
        val capabilities = connectivity.getNetworkCapabilities(active)
            ?: return@runCatching null

        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                val telephony = context.getSystemService(TelephonyManager::class.java)
                val operatorCode = runCatching { telephony?.networkOperator.orEmpty() }
                    .getOrDefault("")
                    .filter(Char::isDigit)
                val operatorName = runCatching { telephony?.networkOperatorName.orEmpty() }
                    .getOrDefault("")
                    .trim()
                    .lowercase(Locale.ROOT)
                    .replace(Regex("\\s+"), " ")
                val identity = operatorCode.takeIf { it.length in 5..6 }
                    ?: operatorName.takeIf { it.isNotBlank() }
                    ?: "unknown"
                "cellular:$identity"
            }
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
    }.getOrNull()
}
