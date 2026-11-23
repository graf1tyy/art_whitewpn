package com.artvpn.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Returns a local-only operator key when the device's default data network is
 * cellular. Wi-Fi users keep the general ranking; a SIM operator is irrelevant
 * when traffic is actually travelling over Wi-Fi.
 */
object NetworkProfile {
    fun activeCellularOperator(context: Context): String? = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching null
        val active = connectivity.activeNetwork ?: return@runCatching null
        val capabilities = connectivity.getNetworkCapabilities(active)
            ?: return@runCatching null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return@runCatching null
        }

        val telephony = context.getSystemService(TelephonyManager::class.java)
            ?: return@runCatching "cellular:unknown"
        val operatorCode = runCatching { telephony.networkOperator.orEmpty() }
            .getOrDefault("")
            .filter(Char::isDigit)
        val operatorName = runCatching { telephony.networkOperatorName.orEmpty() }
            .getOrDefault("")
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        val identity = operatorCode.takeIf { it.length in 5..6 }
            ?: operatorName.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        "cellular:$identity"
    }.getOrNull()
}
