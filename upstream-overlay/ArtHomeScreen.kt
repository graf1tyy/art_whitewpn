package io.nekohasekai.sfa.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.URL

@Composable
internal fun ArtHomeScreen(
    serviceStatus: Status,
    canConnect: Boolean,
    modifier: Modifier = Modifier,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var tunnelVerified by remember { mutableStateOf(false) }

    // "Started" only means Android created the VPN interface. Do not call it
    // connected until a real HTTPS 204 response has passed through the tunnel.
    LaunchedEffect(serviceStatus) {
        tunnelVerified = false
        if (serviceStatus == Status.Started) {
            while (true) {
                tunnelVerified = verifyArtTunnel()
                delay(if (tunnelVerified) 12_000L else 1_200L)
            }
        }
    }

    val preparing = serviceStatus == Status.Stopped && !canConnect
    val starting = serviceStatus == Status.Starting
    val stopping = serviceStatus == Status.Stopping
    val running = serviceStatus == Status.Started
    val connected = running && tunnelVerified
    val searching = running && !tunnelVerified
    val busy = preparing || starting || stopping || searching
    val buttonEnabled = (serviceStatus == Status.Stopped && canConnect) || running

    val statusText = when {
        preparing -> "Подготавливаем серверы"
        starting -> "Запускаем VPN"
        stopping -> "Отключаем VPN"
        connected -> "Подключено · интернет проверен"
        searching -> "Ищем рабочий сервер"
        else -> "Готово к подключению"
    }
    val actionText = when {
        preparing -> "ПОДГОТОВКА"
        starting -> "ПОДКЛЮЧЕНИЕ"
        stopping -> "ОТКЛЮЧЕНИЕ"
        running -> "ОТКЛЮЧИТЬ"
        else -> "ПОДКЛЮЧИТЬ"
    }

    val pulseTransition = rememberInfiniteTransition(label = "art-connect-pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.16f,
        animationSpec = infiniteRepeatable(
            animation = tween(1_350, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "art-pulse",
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = Color(0xFF090909),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "ART",
                color = Color.White,
                fontSize = 42.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
            )
            Text(
                text = "VPN",
                color = Color(0xFF9A9A9A),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 8.sp,
            )

            Spacer(Modifier.height(44.dp))

            Text(
                text = statusText,
                color = if (connected) Color.White else Color(0xFFB7B7B7),
                fontSize = 17.sp,
                fontWeight = if (connected) FontWeight.SemiBold else FontWeight.Normal,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(25.dp))

            Box(
                modifier = Modifier.size(252.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (busy) {
                    Box(
                        modifier = Modifier
                            .size(222.dp)
                            .graphicsLayer {
                                scaleX = pulse
                                scaleY = pulse
                                alpha = 1.16f - pulse
                            }
                            .border(1.5.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(222.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape),
                    )
                }

                Box(
                    modifier = Modifier
                        .size(188.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(enabled = buttonEnabled) {
                            if (running) onDisconnect() else onConnect()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(58.dp),
                            color = Color.Black,
                            strokeWidth = 4.dp,
                        )
                    } else {
                        Text(
                            text = if (connected) "ON" else "OFF",
                            color = Color.Black,
                            fontSize = 34.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = actionText,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text = if (connected) {
                    "Трафик проходит через VPN"
                } else {
                    "Автоматический подбор лучшего сервера"
                },
                color = Color(0xFF858585),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private suspend fun verifyArtTunnel(): Boolean {
    val endpoints = listOf(
        "https://www.gstatic.com/generate_204",
        "https://cp.cloudflare.com/generate_204",
    )
    return withTimeoutOrNull(2_600L) {
        coroutineScope {
            endpoints.map { endpoint ->
                async(Dispatchers.IO) {
                    var connection: HttpURLConnection? = null
                    try {
                        connection = URL(endpoint).openConnection() as HttpURLConnection
                        connection.connectTimeout = 1_700
                        connection.readTimeout = 1_700
                        connection.requestMethod = "GET"
                        connection.instanceFollowRedirects = false
                        connection.useCaches = false
                        connection.setRequestProperty("Cache-Control", "no-cache")
                        connection.setRequestProperty("User-Agent", "ART-VPN-Android/1.0")
                        val code = connection.responseCode
                        code in 200..399
                    } catch (_: Exception) {
                        false
                    } finally {
                        connection?.disconnect()
                    }
                }
            }.awaitAll().any { it }
        }
    } ?: false
}
