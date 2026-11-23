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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.artvpn.app.data.NodeRepository
import com.artvpn.app.data.NodeTester
import com.artvpn.app.data.VpnNode
import com.artvpn.app.vpn.ArtVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private lateinit var repository: NodeRepository
    private var nodes by mutableStateOf<List<VpnNode>>(emptyList())
    private var selectedId by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var connected by mutableStateOf(false)
    private var status by mutableStateOf("Готов к работе")
    private var speed by mutableStateOf("—")
    private var speedJobRunning = false

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) connectNow() else status = "Разрешение VPN не получено"
    }
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val message = intent?.getStringExtra(ArtVpnService.EXTRA_MESSAGE).orEmpty()
            when (intent?.action) {
                ArtVpnService.ACTION_CONNECTED -> { connected = true; status = message.ifBlank { "Подключено" }; measureSpeed() }
                ArtVpnService.ACTION_DISCONNECTED -> { connected = false; status = message.ifBlank { "Отключено" }; speed = "—" }
                ArtVpnService.ACTION_FAILED -> { connected = false; status = message.ifBlank { "Ошибка подключения" } }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = NodeRepository(this)
        nodes = repository.cached()
        selectedId = nodes.firstOrNull { it.reachable }?.id ?: nodes.firstOrNull()?.id
        ContextCompat.registerReceiver(this, statusReceiver,
            IntentFilter().apply {
                addAction(ArtVpnService.ACTION_CONNECTED)
                addAction(ArtVpnService.ACTION_DISCONNECTED)
                addAction(ArtVpnService.ACTION_FAILED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color.White, background = Color(0xFF080808), surface = Color(0xFF151515)
            )) {
                Surface(Modifier.fillMaxSize(), color = Color(0xFF080808)) { ArtVpnScreen() }
            }
        }
        if (nodes.isEmpty()) refreshAndTest()
    }

    override fun onDestroy() { runCatching { unregisterReceiver(statusReceiver) }; super.onDestroy() }

    @Composable
    private fun ArtVpnScreen() {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ART VPN", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Быстрые серверы для вашего соединения", color = Color(0xFF999999), fontSize = 12.sp)
                }
                Text(if (connected) "● ПОДКЛЮЧЕНО" else "○ ОТКЛЮЧЕНО",
                    color = if (connected) Color(0xFF9BE4B0) else Color(0xFFAAAAAA), fontSize = 11.sp)
            }
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF151515)),
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(status, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Metric("PING", selectedNode()?.pingLabel ?: "—")
                        Metric("СКОРОСТЬ", speed)
                        Metric("СЕРВЕРЫ", nodes.size.toString())
                    }
                    Button(onClick = { if (connected) ArtVpnService.stop(this@MainActivity) else askVpnPermission() },
                        modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                        Text(if (connected) "ОТКЛЮЧИТЬ VPN" else "ПОДКЛЮЧИТЬ VPN", fontWeight = FontWeight.Bold)
                    }
                    Text(if (connected) "Скорость измеряется через активное VPN-соединение"
                        else "Выберите сервер или подключитесь к лучшему проверенному",
                        color = Color(0xFF888888), fontSize = 11.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("Серверы", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Text("Задержка TCP · сортировка по отклику", color = Color(0xFF888888), fontSize = 11.sp)
                }
                TextButton(onClick = { refreshAndTest() }, enabled = !busy) { Text("ОБНОВИТЬ") }
            }
            if (busy) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.height(18.dp))
                Text("Загружаю и проверяю узлы…", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
            if (nodes.isEmpty() && !busy) Text("Список пока пуст. Нажмите «Обновить».", color = Color(0xFF999999))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(nodes, key = { it.id }) { node ->
                    ServerRow(node, node.id == selectedId) { selectedId = node.id }
                }
            }
            Text("Публичные узлы могут быть нестабильны. Ping показывает TCP-доступность, а не гарантирует работу туннеля.",
                color = Color(0xFF777777), fontSize = 10.sp)
        }
    }

    @Composable
    private fun Metric(label: String, value: String) {
        Column {
            Text(label, color = Color(0xFF777777), fontSize = 10.sp)
            Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun ServerRow(node: VpnNode, selected: Boolean, onClick: () -> Unit) {
        Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = if (selected) Color(0xFF262626) else Color(0xFF141414))) {
            Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(node.countryLabel, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                    Text(node.name.ifBlank { node.host }, color = Color(0xFF8A8A8A), fontSize = 11.sp, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (node.reachable) node.pingLabel else "Недоступен",
                        color = if (node.reachable) Color(0xFFB7E4C7) else Color(0xFF777777), fontSize = 13.sp)
                    Text(node.protocol, color = Color(0xFF777777), fontSize = 10.sp)
                }
            }
        }
    }

    private fun selectedNode(): VpnNode? = nodes.firstOrNull { it.id == selectedId }

    private fun refreshAndTest() {
        if (busy) return
        busy = true
        status = "Обновляю список…"
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val fresh = runCatching { repository.refresh() }.getOrElse { error ->
                    repository.cached().takeIf { it.isNotEmpty() } ?: throw error
                }
                status = "Проверяю ${fresh.size} серверов…"
                val tested = NodeTester.test(fresh)
                nodes = tested
                repository.save(tested)
                selectedId = tested.firstOrNull { it.reachable }?.id ?: tested.firstOrNull()?.id
                status = "Проверка завершена"
            } catch (e: Exception) { status = e.message ?: "Не удалось обновить серверы" }
            finally { busy = false }
        }
    }

    private fun askVpnPermission() {
        val selected = selectedNode() ?: run { status = "Сначала обновите список серверов"; return }
        if (!selected.reachable) { status = "Этот сервер не прошёл TCP-проверку"; return }
        repository.saveSelected(selected.shareLink)
        val permission = VpnService.prepare(this)
        if (permission == null) connectNow() else vpnPermission.launch(permission)
    }
    private fun connectNow() { ArtVpnService.start(this) }

    private fun measureSpeed() {
        if (speedJobRunning) return
        speedJobRunning = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
                val request = Request.Builder().url("https://speed.cloudflare.com/__down?bytes=2000000")
                    .header("Cache-Control", "no-cache").build()
                val started = System.nanoTime()
                val bytes = client.newCall(request).execute().use { response ->
                    check(response.isSuccessful) { "Speed test HTTP ${response.code}" }
                    response.body?.bytes()?.size ?: 0
                }
                val seconds = ((System.nanoTime() - started).coerceAtLeast(1L)) / 1_000_000_000.0
                val mbps = bytes * 8.0 / seconds / 1_000_000.0
                kotlinx.coroutines.withContext(Dispatchers.Main) { speed = "%.1f Мбит/с".format(java.util.Locale.US, mbps) }
            } catch (_: Exception) {
                kotlinx.coroutines.withContext(Dispatchers.Main) { speed = "нет данных" }
            } finally {
                kotlinx.coroutines.withContext(Dispatchers.Main) { speedJobRunning = false }
            }
        }
    }
}
