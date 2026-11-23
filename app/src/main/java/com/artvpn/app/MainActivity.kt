package com.artvpn.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.artvpn.app.data.NetworkProfile
import com.artvpn.app.data.NodeRepository
import com.artvpn.app.data.NodeTester
import com.artvpn.app.data.VpnNode
import com.artvpn.app.vpn.ArtVpnService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var repository: NodeRepository
    private var nodes by mutableStateOf<List<VpnNode>>(emptyList())
    private var selectedId by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var connecting by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var status by mutableStateOf("Готов к подключению")
    private var speed by mutableStateOf("—")
    private var showServers by mutableStateOf(false)
    private var connectAfterRefresh = false
    private var speedJobRunning = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) connectNow()
        else status = "Не получено разрешение Android на VPN"
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(ArtVpnService.EXTRA_MESSAGE).orEmpty()
            when (intent?.action) {
                ArtVpnService.ACTION_CONNECTING -> {
                    connecting = true
                    connected = false
                    status = message.ifBlank { "Подбираем лучший сервер…" }
                }
                ArtVpnService.ACTION_CONNECTED -> {
                    connecting = false
                    connected = true
                    status = "VPN подключён"
                    repository.selectedLink()?.let { link ->
                        nodes.firstOrNull { it.shareLink == link }?.let { selectedId = it.id }
                    }
                    measureSpeed()
                }
                ArtVpnService.ACTION_DISCONNECTED -> {
                    connecting = false
                    connected = false
                    status = "VPN отключён"
                    speed = "—"
                }
                ArtVpnService.ACTION_FAILED -> {
                    connecting = false
                    connected = false
                    status = message.ifBlank { "Не удалось подключиться" }
                    speed = "—"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = NodeRepository(this)
        nodes = repository.rankForNetwork(
            repository.cached(), NetworkProfile.activeNetworkProfile(this)
        )
        selectedId = bestRememberedNode(nodes)?.id

        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            IntentFilter().apply {
                addAction(ArtVpnService.ACTION_CONNECTING)
                addAction(ArtVpnService.ACTION_CONNECTED)
                addAction(ArtVpnService.ACTION_DISCONNECTED)
                addAction(ArtVpnService.ACTION_FAILED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.White,
                    background = Color(0xFF080808),
                    surface = Color(0xFF151515)
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080808)) {
                    ArtVpnScreen()
                }
            }
        }
        refreshAndTest()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(statusReceiver) }
        super.onDestroy()
    }

    @Composable
    private fun ArtVpnScreen() {
        val pulseTransition = rememberInfiniteTransition(label = "connect-pulse")
        val pulse by pulseTransition.animateFloat(
            initialValue = 1f,
            targetValue = if (connecting) 1.045f else if (connected) 1.012f else 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(if (connecting) 650 else 1400),
                repeatMode = RepeatMode.Reverse
            ),
            label = "button-scale"
        )
        val buttonColor = when {
            connecting -> Color(0xFF343434)
            connected -> Color(0xFF17452E)
            else -> Color(0xFFF2F2F2)
        }
        val buttonTextColor = if (connected || connecting) Color.White else Color(0xFF090909)

        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "ART VPN",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Простой и автоматический",
                        color = Color(0xFF9B9B9B),
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }

            Spacer(Modifier.weight(1f))

            Text(
                when {
                    connected -> "Вы под защитой"
                    connecting -> "Подключаемся"
                    else -> "Безопасный интернет"
                },
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    connected -> "VPN работает. Можно пользоваться приложениями."
                    connecting -> status
                    nodes.isEmpty() -> "Готовим список серверов для подключения"
                    else -> "Нажмите кнопку — ART VPN сам выберет сервер"
                },
                color = Color(0xFFB0B0B0),
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(34.dp))

            Box(
                modifier = Modifier.size(270.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(252.dp)
                        .scale(pulse)
                        .border(
                            width = 2.dp,
                            color = when {
                                connected -> Color(0xFF4AAB74).copy(alpha = 0.3f)
                                connecting -> Color.White.copy(alpha = 0.28f)
                                else -> Color.White.copy(alpha = 0.12f)
                            },
                            shape = CircleShape
                        )
                )
                Surface(
                    onClick = { onPowerButtonClick() },
                    modifier = Modifier.size(230.dp).scale(pulse),
                    shape = CircleShape,
                    color = buttonColor,
                    contentColor = buttonTextColor,
                    shadowElevation = 10.dp
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        if (connecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(58.dp),
                                color = Color.White,
                                strokeWidth = 4.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.PowerSettingsNew,
                                contentDescription = null,
                                modifier = Modifier.size(72.dp)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when {
                                connected -> "ОТКЛЮЧИТЬ"
                                connecting -> "ОТМЕНИТЬ"
                                else -> "ПОДКЛЮЧИТЬ"
                            },
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                if (connected) "Соединение активно"
                else if (connecting) "Проверяем серверы и интернет"
                else if (busy) "Обновляем список серверов…"
                else if (nodes.isEmpty()) "Серверы пока не загружены"
                else "Серверов в подборке: ${nodes.size}",
                color = Color(0xFF9B9B9B),
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.weight(1f))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (nodes.isEmpty()) "Список загружается" else "${nodes.size} в подборке",
                        color = Color(0xFF929292),
                        fontSize = 14.sp
                    )
                }
                TextButton(onClick = { showServers = !showServers }) {
                    Text(if (showServers) "Скрыть" else "Подробнее", fontSize = 15.sp)
                }
            }

            AnimatedVisibility(visible = showServers) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF151515))
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Подборка ART VPN",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { refreshAndTest() }, enabled = !busy) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text(" Обновить", fontSize = 14.sp)
                            }
                        }
                        if (busy) {
                            Text(
                                "Обновляем и проверяем серверы…",
                                color = Color(0xFFB5B5B5),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        if (nodes.isEmpty()) {
                            Text(
                                "Пока нет доступных записей. Попробуйте обновить список.",
                                color = Color(0xFFB5B5B5),
                                fontSize = 14.sp
                            )
                        } else {
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 230.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(nodes, key = { it.id }) { node ->
                                    ServerRow(node, node.id == selectedId) {
                                        selectedId = node.id
                                        repository.saveSelected(node.shareLink)
                                    }
                                }
                            }
                        }
                        if (connected) {
                            Text(
                                "Скорость: $speed",
                                color = Color(0xFFB5B5B5),
                                fontSize = 14.sp,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                        }
                    }
                }
            }

            if (busy && !showServers) {
                Spacer(Modifier.height(8.dp))
                Text("Список серверов обновляется автоматически", color = Color(0xFF858585), fontSize = 14.sp)
            }
        }
    }

    @Composable
    private fun ServerRow(node: VpnNode, selected: Boolean, onClick: () -> Unit) {
        val recentlyVerified = node.lastSuccessAt?.let {
            System.currentTimeMillis() - it < VERIFIED_NODE_TTL_MS
        } == true
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) Color(0xFF303030) else Color(0xFF202020)
            )
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        node.countryLabel,
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                    Text(
                        node.name.ifBlank { node.host },
                        color = Color(0xFFAAAAAA),
                        fontSize = 14.sp,
                        maxLines = 1
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        when {
                            recentlyVerified -> "Работал · ${node.pingLabel}"
                            node.reachable -> node.pingLabel
                            else -> "Не проверен"
                        },
                        color = if (recentlyVerified) Color(0xFF9BE4B0) else Color(0xFFCCCCCC),
                        fontSize = 14.sp
                    )
                    node.measuredMbps?.let {
                        Text(
                            "%.1f Мбит/с".format(java.util.Locale.US, it),
                            color = Color(0xFFAAAAAA),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }

    private fun bestRememberedNode(list: List<VpnNode>): VpnNode? =
        repository.rankForNetwork(list, NetworkProfile.activeNetworkProfile(this)).firstOrNull()

    private fun refreshAndTest() {
        if (busy) return
        busy = true
        status = "Обновляем серверы…"
        lifecycleScope.launch {
            try {
                val fresh = repository.refresh()
                status = "Проверяем лучшие варианты…"
                val tested = NodeTester.test(fresh)
                val ranked = repository.rankForNetwork(
                    tested, NetworkProfile.activeNetworkProfile(this@MainActivity)
                )
                nodes = ranked
                repository.save(ranked)
                selectedId = ranked.firstOrNull()?.id
                status = "Серверы обновлены"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                val cached = repository.cached()
                if (cached.isNotEmpty()) {
                    nodes = repository.rankForNetwork(
                        cached, NetworkProfile.activeNetworkProfile(this@MainActivity)
                    )
                    selectedId = nodes.firstOrNull()?.id
                    status = "Используем сохранённый список"
                } else {
                    status = e.message ?: "Не удалось загрузить серверы"
                }
            } finally {
                busy = false
                if (connectAfterRefresh) {
                    connectAfterRefresh = false
                    if (nodes.isNotEmpty()) askVpnPermission()
                }
            }
        }
    }

    private fun onPowerButtonClick() {
        if (connected || connecting) {
            ArtVpnService.stop(this)
            return
        }
        if (nodes.isEmpty()) {
            connectAfterRefresh = true
            if (!busy) refreshAndTest()
            status = "Сначала загружаем серверы…"
            return
        }
        askVpnPermission()
    }

    private fun askVpnPermission() {
        val selected = nodes.firstOrNull { it.id == selectedId } ?: bestRememberedNode(nodes)
        if (selected == null) {
            connectAfterRefresh = true
            refreshAndTest()
            return
        }
        selectedId = selected.id
        repository.saveSelected(selected.shareLink)
        val permission = VpnService.prepare(this)
        if (permission == null) connectNow() else vpnPermission.launch(permission)
    }

    private fun connectNow() {
        ArtVpnService.start(this)
    }

    private fun measureSpeed() {
        if (speedJobRunning) return
        speedJobRunning = true
        val activeNodeId = selectedId
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .callTimeout(12, TimeUnit.SECONDS)
                    .build()
                val request = okhttp3.Request.Builder()
                    .url("https://speed.cloudflare.com/__down?bytes=2000000")
                    .header("Cache-Control", "no-cache")
                    .build()
                val started = System.nanoTime()
                val bytes = client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Speed test HTTP ${response.code}" }
                    response.body?.bytes()?.size ?: 0
                }
                val seconds = ((System.nanoTime() - started).coerceAtLeast(1L)) / 1_000_000_000.0
                val mbps = bytes * 8.0 / seconds / 1_000_000.0
                withContext(Dispatchers.Main) {
                    speed = "%.1f Мбит/с".format(java.util.Locale.US, mbps)
                    if (activeNodeId != null) {
                        val updated = nodes.map { node ->
                            if (node.id == activeNodeId) node.copy(measuredMbps = mbps) else node
                        }
                        nodes = updated
                        repository.save(updated)
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { speed = "нет данных" }
            } finally {
                withContext(Dispatchers.Main) { speedJobRunning = false }
            }
        }
    }

    companion object {
        private const val VERIFIED_NODE_TTL_MS = 24L * 60 * 60 * 1000
    }
}
