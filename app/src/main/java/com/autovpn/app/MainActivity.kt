package com.autovpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autovpn.app.model.ProxyConfig
import com.autovpn.app.subscription.SubscriptionManager
import com.autovpn.app.subscription.SubscriptionStore
import com.autovpn.app.vpn.VpnTunnelService
import com.autovpn.app.xray.PingProgress
import com.autovpn.app.xray.PingTester
import com.autovpn.app.xray.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

enum class ConnState { DISCONNECTED, FETCHING, PINGING, CONNECTING, CONNECTED, ERROR }

class MainActivity : ComponentActivity() {

    private var pendingBestConfig: ProxyConfig? = null

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingBestConfig?.let { startTunnelService(it) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppScreen() }
    }

    @Composable
    fun AppScreen() {
        var state by remember { mutableStateOf(ConnState.DISCONNECTED) }
        var serverName by remember { mutableStateOf<String?>(null) }
        var pingMs by remember { mutableStateOf<Long?>(null) }
        var pingProgress by remember { mutableStateOf<PingProgress?>(null) }
        var subscriptions by remember { mutableStateOf(SubscriptionStore.load(this@MainActivity)) }
        var pingedConfigs by remember { mutableStateOf<List<ProxyConfig>>(emptyList()) }
        var currentIndex by remember { mutableStateOf(0) }
        var showAddDialog by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun connectToIndex(index: Int) {
            val cfg = pingedConfigs.getOrNull(index) ?: return
            currentIndex = index
            serverName = cfg.name
            pingMs = cfg.pingMs
            pendingBestConfig = cfg
            val vpnIntent = VpnService.prepare(this@MainActivity)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startTunnelService(cfg)
            }
        }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                    Text("سابسکریپشن‌ها", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))

                    val isBusyGlobal = state == ConnState.FETCHING || state == ConnState.PINGING || state == ConnState.CONNECTING

                    LazyColumn(modifier = Modifier.weight(1f, fill = false).heightIn(max = 220.dp)) {
                        items(subscriptions) { sub ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                Checkbox(
                                    checked = sub.enabled,
                                    enabled = !isBusyGlobal,
                                    onCheckedChange = { checked ->
                                        subscriptions = SubscriptionStore.setEnabled(this@MainActivity, sub.url, checked)
                                    }
                                )
                                Text(
                                    sub.url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    enabled = !isBusyGlobal,
                                    onClick = {
                                        subscriptions = SubscriptionStore.remove(this@MainActivity, sub.url)
                                    }
                                ) { Text("حذف") }
                            }
                        }
                    }

                    TextButton(onClick = { showAddDialog = true }, enabled = !isBusyGlobal) {
                        Text("+ افزودن سابسکریپشن جدید")
                    }

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = when (state) {
                                ConnState.DISCONNECTED -> "قطع"
                                ConnState.FETCHING -> "در حال دریافت سابسکریپشن‌ها..."
                                ConnState.PINGING -> "در حال تست پینگ سرورها..."
                                ConnState.CONNECTING -> "در حال اتصال..."
                                ConnState.CONNECTED -> "متصل"
                                ConnState.ERROR -> "خطا - سرور مناسب پیدا نشد"
                            },
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        if (serverName != null) Text("سرور: $serverName")
                        if (pingMs != null) Text("پینگ: ${pingMs} ms")
                        if (state == ConnState.CONNECTED && pingedConfigs.isNotEmpty()) {
                            Text("کانفیگ ${currentIndex + 1} از ${pingedConfigs.size}")
                        }
                        if (state == ConnState.PINGING && pingProgress != null) {
                            val p = pingProgress!!
                            Spacer(Modifier.height(4.dp))
                            Text("کل کانفیگ‌ها: ${p.total}")
                            Text("پینگ‌خورده (موفق): ${p.succeeded}")
                            Text("پینگ‌نخورده (ناموفق): ${p.failed}")
                        }
                        Spacer(Modifier.height(32.dp))

                        val isBusy = isBusyGlobal

                        Button(
                            enabled = !isBusy,
                            onClick = {
                                if (state == ConnState.CONNECTED) {
                                    if (pingedConfigs.isNotEmpty()) {
                                        val nextIndex = (currentIndex + 1) % pingedConfigs.size
                                        connectToIndex(nextIndex)
                                    }
                                } else {
                                    scope.launch {
                                        state = ConnState.FETCHING
                                        pingProgress = null
                                        val enabledUrls = subscriptions.filter { it.enabled }.map { it.url }
                                        if (enabledUrls.isEmpty()) {
                                            state = ConnState.ERROR
                                            return@launch
                                        }
                                        val configs = SubscriptionManager.fetchAll(enabledUrls)
                                        if (configs.isEmpty()) {
                                            state = ConnState.ERROR
                                            return@launch
                                        }
                                        state = ConnState.PINGING
                                        val sorted = PingTester.testAll(configs) { progress ->
                                            scope.launch(Dispatchers.Main) { pingProgress = progress }
                                        }
                                        if (sorted.isEmpty()) {
                                            state = ConnState.ERROR
                                            return@launch
                                        }
                                        pingedConfigs = sorted
                                        state = ConnState.CONNECTING
                                        connectToIndex(0)
                                        state = ConnState.CONNECTED
                                    }
                                }
                            },
                            modifier = Modifier.size(160.dp),
                            shape = CircleShape
                        ) {
                            Text(if (state == ConnState.CONNECTED) "کانفیگ بعدی" else "اتصال")
                        }

                        if (state == ConnState.CONNECTED) {
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = {
                                startService(Intent(this@MainActivity, VpnTunnelService::class.java).apply {
                                    action = VpnTunnelService.ACTION_DISCONNECT
                                })
                                state = ConnState.DISCONNECTED
                                serverName = null
                                pingMs = null
                                pingProgress = null
                                pingedConfigs = emptyList()
                                currentIndex = 0
                            }) {
                                Text("قطع اتصال")
                            }
                        }
                    }
                }

                if (showAddDialog) {
                    var newUrl by remember { mutableStateOf("") }
                    AlertDialog(
                        onDismissRequest = { showAddDialog = false },
                        title = { Text("افزودن سابسکریپشن جدید") },
                        text = {
                            OutlinedTextField(
                                value = newUrl,
                                onValueChange = { newUrl = it },
                                placeholder = { Text("https://...") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                if (newUrl.isNotBlank()) {
                                    subscriptions = SubscriptionStore.addUrl(this@MainActivity, newUrl.trim())
                                }
                                showAddDialog = false
                            }) { Text("افزودن") }
                        },
                        dismissButton = {
                            TextButton(onClick = { showAddDialog = false }) { Text("انصراف") }
                        }
                    )
                }
            }
        }
    }

    private fun startTunnelService(config: ProxyConfig) {
        val fullConfig = XrayConfigBuilder.buildFull(config)
        val intent = Intent(this, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, fullConfig)
            putExtra(VpnTunnelService.EXTRA_SERVER_NAME, config.name)
        }
        startForegroundService(intent)
    }
}
