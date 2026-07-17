package com.autovpn.app

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autovpn.app.model.ProxyConfig
import com.autovpn.app.subscription.SubscriptionManager
import com.autovpn.app.vpn.VpnTunnelService
import com.autovpn.app.xray.PingTester
import com.autovpn.app.xray.XrayConfigBuilder
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
        var statusText by remember { mutableStateOf("قطع") }
        var serverName by remember { mutableStateOf<String?>(null) }
        var pingMs by remember { mutableStateOf<Long?>(null) }
        val scope = rememberCoroutineScope()

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
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
                    if (serverName != null) {
                        Text("سرور: $serverName")
                    }
                    if (pingMs != null) {
                        Text("پینگ: ${pingMs} ms")
                    }
                    Spacer(Modifier.height(32.dp))

                    val isBusy = state == ConnState.FETCHING || state == ConnState.PINGING || state == ConnState.CONNECTING

                    Button(
                        enabled = !isBusy,
                        onClick = {
                            if (state == ConnState.CONNECTED) {
                                // Disconnect
                                startService(Intent(this@MainActivity, VpnTunnelService::class.java).apply {
                                    action = VpnTunnelService.ACTION_DISCONNECT
                                })
                                state = ConnState.DISCONNECTED
                                serverName = null
                                pingMs = null
                            } else {
                                scope.launch {
                                    state = ConnState.FETCHING
                                    val configs = SubscriptionManager.fetchAll()
                                    if (configs.isEmpty()) {
                                        state = ConnState.ERROR
                                        return@launch
                                    }
                                    state = ConnState.PINGING
                                    val best = PingTester.bestOf(configs)
                                    if (best == null) {
                                        state = ConnState.ERROR
                                        return@launch
                                    }
                                    state = ConnState.CONNECTING
                                    pendingBestConfig = best
                                    serverName = best.name
                                    pingMs = best.pingMs

                                    val vpnIntent = VpnService.prepare(this@MainActivity)
                                    if (vpnIntent != null) {
                                        vpnPermissionLauncher.launch(vpnIntent)
                                    } else {
                                        startTunnelService(best)
                                    }
                                    state = ConnState.CONNECTED
                                }
                            }
                        },
                        modifier = Modifier.size(160.dp),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ) {
                        Text(if (state == ConnState.CONNECTED) "قطع اتصال" else "اتصال")
                    }
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
