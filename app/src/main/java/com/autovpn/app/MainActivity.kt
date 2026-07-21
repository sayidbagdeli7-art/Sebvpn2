package com.autovpn.app

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autovpn.app.model.NewsMessage
import com.autovpn.app.model.ProxyConfig
import com.autovpn.app.news.GitHubChannelSync
import com.autovpn.app.news.NewsRepository
import com.autovpn.app.subscription.ChannelStore
import com.autovpn.app.subscription.GitHubTokenStore
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
    private var pendingFragmentEnabled: Boolean = false

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingBestConfig?.let { startTunnelService(it, pendingFragmentEnabled) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }

    @Composable
    fun AppRoot() {
        var selectedTab by remember { mutableStateOf(0) }

        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("VPN") }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("اخبار") }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        if (selectedTab == 0) VpnTab() else NewsTab()
                    }
                }
            }
        }
    }

    @Composable
    fun NewsTab() {
        var news by remember { mutableStateOf<List<NewsMessage>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var loadedOnce by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            scope.launch {
                loading = true
                news = NewsRepository.fetchChannel("IranintlTV")
                loading = false
                loadedOnce = true
            }
        }

        LaunchedEffect(Unit) { refresh() }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("آخرین اخبار", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "در حال بروزرسانی..." else "بروزرسانی")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (loadedOnce && news.isEmpty() && !loading) {
                Text("خبری پیدا نشد (شاید هنوز اولین بار خودکارسازی گیت‌هاب اجرا نشده).")
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(news) { item ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                item.text,
                                maxLines = 6,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                if (item.date != null) {
                                    Text(item.date, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                                }
                                if (item.link != null) {
                                    TextButton(onClick = {
                                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.link)))
                                    }) {
                                        Text("مشاهده در تلگرام")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun VpnTab() {
        var state by remember { mutableStateOf(if (VpnTunnelService.isRunning) ConnState.CONNECTED else ConnState.DISCONNECTED) }
        var serverName by remember { mutableStateOf<String?>(null) }
        var pingMs by remember { mutableStateOf<Long?>(null) }
        var pingProgress by remember { mutableStateOf<PingProgress?>(null) }
        var subscriptions by remember { mutableStateOf(SubscriptionStore.load(this@MainActivity)) }
        var pingedConfigs by remember { mutableStateOf<List<ProxyConfig>>(emptyList()) }
        var currentIndex by remember { mutableStateOf(0) }
        var showAddDialog by remember { mutableStateOf(false) }
        var fragmentEnabled by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        fun connectToIndex(index: Int) {
            val cfg = pingedConfigs.getOrNull(index) ?: return
            currentIndex = index
            serverName = cfg.name
            pingMs = cfg.pingMs
            pendingBestConfig = cfg
            pendingFragmentEnabled = fragmentEnabled
            val vpnIntent = VpnService.prepare(this@MainActivity)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startTunnelService(cfg, fragmentEnabled)
            }
        }

        fun disconnect() {
            startService(Intent(this@MainActivity, VpnTunnelService::class.java).apply {
                action = VpnTunnelService.ACTION_DISCONNECT
            })
            state = ConnState.DISCONNECTED
            serverName = null
            pingMs = null
            pingProgress = null
            pingedConfigs = emptyList()
            currentIndex = 0
        }

        val isBusyGlobal = state == ConnState.FETCHING || state == ConnState.PINGING || state == ConnState.CONNECTING

        Column(modifier = Modifier.fillMaxSize()) {

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {

                Text("سابسکریپشن‌ها", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Switch(
                        checked = fragmentEnabled,
                        onCheckedChange = { checked ->
                            fragmentEnabled = checked
                            if (state == ConnState.CONNECTED) {
                                connectToIndex(currentIndex)
                            }
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("فرگمنت (ضدفیلترینگ) — اگه پروکسی وصل نمی‌شد، روشن/خاموشش کن")
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
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

                    Button(
                        enabled = !isBusyGlobal,
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

                    Spacer(Modifier.height(24.dp))
                }
            }

            HorizontalDivider()
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = { disconnect() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    ),
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("⏻  قطع اتصال", style = MaterialTheme.typography.titleMedium)
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

    private fun startTunnelService(config: ProxyConfig, fragmentEnabled: Boolean = false) {
        val fullConfig = XrayConfigBuilder.buildFull(config, fragmentEnabled)
        val intent = Intent(this, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, fullConfig)
            putExtra(VpnTunnelService.EXTRA_SERVER_NAME, config.name)
        }
        startForegroundService(intent)
    }
}
