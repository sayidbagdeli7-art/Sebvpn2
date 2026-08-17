package com.autovpn.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.autovpn.app.chat.ChatCrypto
import com.autovpn.app.chat.ChatRepository
import com.autovpn.app.model.ChatMessage
import com.autovpn.app.model.NewsMessage
import com.autovpn.app.model.ProxyConfig
import com.autovpn.app.news.NewsRepository
import com.autovpn.app.subscription.ChatPasswordStore
import com.autovpn.app.subscription.ChatSeenStore
import com.autovpn.app.subscription.DeviceIdStore
import com.autovpn.app.subscription.GitHubTokenStore
import com.autovpn.app.subscription.SplitTunnelStore
import com.autovpn.app.subscription.SubscriptionManager
import com.autovpn.app.subscription.SubscriptionStore
import com.autovpn.app.update.UpdateChecker
import com.autovpn.app.vpn.VpnTunnelService
import com.autovpn.app.xray.PingProgress
import com.autovpn.app.xray.PingTester
import com.autovpn.app.xray.XrayConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

enum class ConnState { DISCONNECTED, FETCHING, PINGING, CONNECTING, CONNECTED, ERROR }

class MainActivity : ComponentActivity() {

    private var pendingBestConfig: ProxyConfig? = null
    private var pendingFragmentEnabled: Boolean = false
    private var pendingFragmentLength: String = "10-20"
    private var pendingFragmentInterval: String = "10-20"

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingBestConfig?.let { startTunnelService(it, pendingFragmentEnabled, pendingFragmentLength, pendingFragmentInterval) }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way - notifications just won't show if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        // Background check for new chat messages every ~15 minutes (Android's
        // minimum allowed interval for periodic work), so you get notified even
        // when the app itself isn't open.
        val chatCheckRequest = androidx.work.PeriodicWorkRequestBuilder<com.autovpn.app.chat.ChatCheckWorker>(
            15, java.util.concurrent.TimeUnit.MINUTES
        ).setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        ).build()
        androidx.work.WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "chat_check",
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            chatCheckRequest
        )

        setContent { AppRoot() }
    }

    @Composable
    fun AppRoot() {
        var selectedTab by remember { mutableStateOf(0) }
        var showUpdateDialog by remember { mutableStateOf(false) }
        var downloadingUpdate by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        LaunchedEffect(Unit) {
            if (UpdateChecker.isUpdateAvailable()) {
                showUpdateDialog = true
            }
        }

        fun downloadAndInstallUpdate() {
            scope.launch {
                downloadingUpdate = true
                try {
                    val apkFile = withContext(Dispatchers.IO) {
                        val dir = File(cacheDir, "updates").apply { mkdirs() }
                        val file = File(dir, "AutoVPN.apk")
                        val client = OkHttpClient()
                        val req = Request.Builder().url(UpdateChecker.APK_URL).build()
                        client.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
                            file.outputStream().use { out -> resp.body?.byteStream()?.copyTo(out) }
                        }
                        file
                    }
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", apkFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "application/vnd.android.package-archive")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Download or install failed - nothing to auto-recover here, the
                    // user can just try "بروزرسانی" again later from GitHub Actions.
                } finally {
                    downloadingUpdate = false
                    showUpdateDialog = false
                }
            }
        }

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
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = { Text("چت") }
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            0 -> VpnTab()
                            1 -> NewsTab()
                            else -> ChatTab()
                        }
                    }
                }

                if (showUpdateDialog) {
                    AlertDialog(
                        onDismissRequest = { if (!downloadingUpdate) showUpdateDialog = false },
                        title = { Text("بروزرسانی جدید") },
                        text = {
                            Text(
                                if (downloadingUpdate) "در حال دانلود و آماده‌سازیِ نصب..."
                                else "نسخه‌ی جدیدتری از اپ روی گیت‌هاب موجوده. می‌خوای دانلود و نصبش کنی؟"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = { downloadAndInstallUpdate() }, enabled = !downloadingUpdate) {
                                Text(if (downloadingUpdate) "..." else "دانلود و نصب")
                            }
                        },
                        dismissButton = {
                            TextButton(
                                onClick = { showUpdateDialog = false },
                                enabled = !downloadingUpdate
                            ) { Text("بعداً") }
                        }
                    )
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
    fun ChatTab() {
        var password by remember { mutableStateOf(ChatPasswordStore.load(this@MainActivity)) }
        var githubToken by remember { mutableStateOf(GitHubTokenStore.load(this@MainActivity)) }
        var showPasswordDialog by remember { mutableStateOf(password.isBlank()) }
        var showTokenDialog by remember { mutableStateOf(false) }
        var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
        var loading by remember { mutableStateOf(false) }
        var sending by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf("") }
        var statusMsg by remember { mutableStateOf<String?>(null) }
        var pendingDelete by remember { mutableStateOf<ChatMessage?>(null) }
        val myId = remember { DeviceIdStore.getOrCreate(this@MainActivity) }
        val scope = rememberCoroutineScope()

        fun refresh(showLoading: Boolean = true) {
            scope.launch {
                if (showLoading) loading = true
                val fetched = ChatRepository.fetchMessages()
                // Merge instead of overwrite, so a just-sent message we're already
                // showing locally doesn't briefly disappear if the fetch is still
                // catching up to what we sent.
                messages = (messages + fetched)
                    .distinctBy { it.ciphertext }
                    .sortedBy { it.timestamp }
                ChatSeenStore.save(this@MainActivity, fetched.size)
                if (showLoading) loading = false
            }
        }

        // Auto-refresh in the background every 7s while this tab is open, so the
        // other person's messages show up without needing to tap "بروزرسانی"
        // manually each time. Only reads from jsDelivr (no token used), so this
        // doesn't burn through the GitHub token's rate limit.
        LaunchedEffect(Unit) {
            refresh()
            while (true) {
                delay(7000)
                refresh(showLoading = false)
            }
        }

        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("چتِ رمزنگاری‌شده", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh() }, enabled = !loading) {
                    Text(if (loading) "..." else "بروزرسانی")
                }
            }
            Row {
                TextButton(onClick = { showPasswordDialog = true }) { Text("پسورد") }
                TextButton(onClick = { showTokenDialog = true }) {
                    Text(if (githubToken.isBlank()) "تنظیمِ توکنِ گیت‌هاب" else "توکن تنظیم شده ✓")
                }
            }
            if (statusMsg != null) {
                Text(statusMsg!!, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages) { msg ->
                    val plainPayload = if (password.isNotBlank()) ChatCrypto.decrypt(msg.ciphertext, password) else null
                    if (plainPayload != null) {
                        val (fromId, text) = try {
                            val o = JSONObject(plainPayload)
                            (o.optString("from", "") to o.optString("text", plainPayload))
                        } catch (e: Exception) {
                            "" to plainPayload // old-format messages sent before this update
                        }
                        val isMine = fromId == myId

                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = if (isMine) Arrangement.End else Arrangement.Start
                        ) {
                            Card(
                                modifier = Modifier.widthIn(max = 280.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isMine) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(text)
                                    TextButton(
                                        onClick = { pendingDelete = msg },
                                        modifier = Modifier.align(Alignment.End)
                                    ) {
                                        Text("حذف", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                    // Messages that fail to decrypt (wrong password, or not one of
                    // ours) are just skipped instead of showing a confusing "🔒".
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("پیام...") },
                    singleLine = true
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = !sending && draft.isNotBlank() && password.isNotBlank(),
                    onClick = {
                        val text = draft
                        scope.launch {
                            sending = true
                            statusMsg = "در حال ارسال..."
                            val payload = JSONObject().apply {
                                put("from", myId)
                                put("text", text)
                            }.toString()
                            val ciphertext = ChatCrypto.encrypt(payload, password)
                            val sentAt = System.currentTimeMillis()
                            when (val result = ChatRepository.sendMessage(githubToken, ciphertext)) {
                                is ChatRepository.SendResult.Success -> {
                                    // Add it straight to the visible list instead of only
                                    // relying on a re-fetch - jsDelivr's cache can take a
                                    // few seconds to actually update even after a purge,
                                    // so a fetch right after sending can still miss it.
                                    messages = messages + ChatMessage(ciphertext, sentAt)
                                    draft = ""
                                    statusMsg = null
                                }
                                is ChatRepository.SendResult.Error -> {
                                    statusMsg = "ارسال ناموفق: ${result.message}"
                                }
                            }
                            sending = false
                        }
                    }
                ) { Text("ارسال") }
            }
        }

        if (showPasswordDialog) {
            var input by remember { mutableStateOf(password) }
            AlertDialog(
                onDismissRequest = { showPasswordDialog = false },
                title = { Text("پسوردِ مشترکِ چت") },
                text = {
                    Column {
                        Text(
                            "این پسورد فقط روی همین گوشی ذخیره می‌شه (نه گیت‌هاب، نه هیچ‌جای دیگه). طرفِ مقابل هم باید دقیقاً همین پسورد رو توی اپِ خودش وارد کنه.",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        password = input
                        ChatPasswordStore.save(this@MainActivity, password)
                        showPasswordDialog = false
                    }) { Text("ذخیره") }
                },
                dismissButton = {
                    TextButton(onClick = { showPasswordDialog = false }) { Text("انصراف") }
                }
            )
        }

        if (showTokenDialog) {
            var input by remember { mutableStateOf(githubToken) }
            AlertDialog(
                onDismissRequest = { showTokenDialog = false },
                title = { Text("توکنِ گیت‌هاب") },
                text = {
                    Column {
                        Text(
                            "برای فرستادنِ پیام لازمه (فقط برای خوندن نه). این توکن فقط روی همین گوشی ذخیره می‌شه.",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            placeholder = { Text("ghp_...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        githubToken = input.trim()
                        GitHubTokenStore.save(this@MainActivity, githubToken)
                        showTokenDialog = false
                    }) { Text("ذخیره") }
                },
                dismissButton = {
                    TextButton(onClick = { showTokenDialog = false }) { Text("انصراف") }
                }
            )
        }

        pendingDelete?.let { toDelete ->
            AlertDialog(
                onDismissRequest = { pendingDelete = null },
                title = { Text("حذفِ پیام") },
                text = { Text("این پیام برای هر دو نفر حذف بشه؟") },
                confirmButton = {
                    TextButton(onClick = {
                        val target = toDelete
                        pendingDelete = null
                        scope.launch {
                            when (val result = ChatRepository.deleteMessage(githubToken, target.ciphertext)) {
                                is ChatRepository.SendResult.Success -> {
                                    messages = messages.filterNot { it.ciphertext == target.ciphertext }
                                }
                                is ChatRepository.SendResult.Error -> {
                                    statusMsg = "حذف ناموفق: ${result.message}"
                                }
                            }
                        }
                    }) { Text("حذف") }
                },
                dismissButton = {
                    TextButton(onClick = { pendingDelete = null }) { Text("انصراف") }
                }
            )
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
        var fragmentLength by remember { mutableStateOf("10-20") }
        var fragmentInterval by remember { mutableStateOf("10-20") }
        var totalUp by remember { mutableStateOf(0L) }
        var totalDown by remember { mutableStateOf(0L) }
        var autoSkipNoTraffic by remember { mutableStateOf(false) }
        var showSplitTunnelDialog by remember { mutableStateOf(false) }
        var splitTunnelSelected by remember { mutableStateOf(SplitTunnelStore.load(this@MainActivity)) }
        var refreshing by remember { mutableStateOf(false) }
        var connectJob by remember { mutableStateOf<Job?>(null) }
        val scope = rememberCoroutineScope()

        fun connectToIndex(index: Int) {
            val cfg = pingedConfigs.getOrNull(index) ?: return
            currentIndex = index
            serverName = cfg.name
            pingMs = cfg.pingMs
            pendingBestConfig = cfg
            pendingFragmentEnabled = fragmentEnabled
            pendingFragmentLength = fragmentLength
            pendingFragmentInterval = fragmentInterval
            val vpnIntent = VpnService.prepare(this@MainActivity)
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
            } else {
                startTunnelService(cfg, fragmentEnabled, fragmentLength, fragmentInterval)
            }
        }

        fun disconnect() {
            // Stop whatever fetch/ping work is still running in the background - without
            // this, pressing disconnect mid-search didn't actually stop the search, it
            // just kept going and could even reconnect once it finished.
            connectJob?.cancel()
            connectJob = null
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

        suspend fun fullRefresh() {
            if (refreshing) return
            refreshing = true
            pingProgress = null
            try {
                val enabledUrls = subscriptions.filter { it.enabled }.map { it.url }
                if (enabledUrls.isEmpty()) return
                val configs = SubscriptionManager.fetchAll(enabledUrls)
                if (configs.isEmpty()) return
                val currentRaw = pingedConfigs.getOrNull(currentIndex)?.raw
                val sorted = PingTester.testAll(configs) { progress -> pingProgress = progress }
                if (sorted.isNotEmpty()) {
                    pingedConfigs = sorted
                    val newIndex = sorted.indexOfFirst { it.raw == currentRaw }
                    if (newIndex >= 0) currentIndex = newIndex
                }
            } finally {
                refreshing = false
                pingProgress = null
            }
        }

        val isBusyGlobal = state == ConnState.FETCHING || state == ConnState.PINGING || state == ConnState.CONNECTING || refreshing

        // Polls real up/down traffic every 1.5s while connected. QueryStats resets its
        // counter on every read, so we accumulate deltas into a running total that
        // resets whenever we switch to a different config (currentIndex changes).
        // Also auto-reconnects if the tunnel dies unexpectedly.
        LaunchedEffect(currentIndex, state) {
            if (state == ConnState.CONNECTED) {
                totalUp = 0L
                totalDown = 0L
                val connectedAt = System.currentTimeMillis()
                while (state == ConnState.CONNECTED) {
                    delay(1500)

                    if (!VpnTunnelService.isRunning) {
                        // The tunnel died on its own (not because the user pressed
                        // disconnect) - try to bring it back automatically.
                        connectToIndex(currentIndex)
                        break
                    }

                    val (up, down) = VpnTunnelService.queryTraffic()
                    totalUp += up
                    totalDown += down

                    val elapsed = System.currentTimeMillis() - connectedAt
                    if (autoSkipNoTraffic && elapsed > 10000 && totalDown == 0L) {
                        if (pingedConfigs.isNotEmpty()) {
                            connectToIndex((currentIndex + 1) % pingedConfigs.size)
                        }
                        break
                    }
                }
            }
        }

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

                if (fragmentEnabled) {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp)) {
                        OutlinedTextField(
                            value = fragmentLength,
                            onValueChange = { fragmentLength = it },
                            label = { Text("اندازه (length)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            value = fragmentInterval,
                            onValueChange = { fragmentInterval = it },
                            label = { Text("فاصله (interval)") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 48.dp)) {
                        TextButton(
                            onClick = {
                                if (state == ConnState.CONNECTED) connectToIndex(currentIndex)
                            },
                            enabled = state == ConnState.CONNECTED
                        ) { Text("اعمالِ مقادیر جدید (وقتی وصلی)") }
                    }
                }

                TextButton(onClick = { showSplitTunnelDialog = true }, enabled = !isBusyGlobal) {
                    Text(
                        if (splitTunnelSelected.isEmpty()) "اپ‌های تونل (الان: همه‌چیز)"
                        else "اپ‌های تونل (الان: ${splitTunnelSelected.size} اپ انتخابی)"
                    )
                }

                TextButton(
                    onClick = { connectJob = scope.launch { fullRefresh() } },
                    enabled = !isBusyGlobal
                ) {
                    Text(if (refreshing) "در حال بررسیِ کاملِ کانفیگ‌ها..." else "بررسیِ کاملِ همه‌ی کانفیگ‌ها (بدونِ قطع‌شدن)")
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
                        Spacer(Modifier.height(4.dp))
                        Text("آپلود: ${formatBytes(totalUp)}   دانلود: ${formatBytes(totalDown)}")
                        if (totalDown == 0L) {
                            Text(
                                "هنوز دیتایی رد نشده — ممکنه این کانفیگ فقط پینگ بده و واقعاً وصل نباشه",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = autoSkipNoTraffic,
                                onCheckedChange = { autoSkipNoTraffic = it }
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "رد شدن خودکار از کانفیگ‌های بدون دیتا",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if ((state == ConnState.PINGING || refreshing) && pingProgress != null) {
                        val p = pingProgress!!
                        Spacer(Modifier.height(4.dp))
                        Text("کل کانفیگ‌ها: ${p.total}")
                        Text("پینگ‌خورده (موفق): ${p.succeeded}")
                        Text("پینگ‌نخورده (ناموفق): ${p.failed}")
                    }
                    Spacer(Modifier.height(32.dp))

                    val showNoDataRing = state == ConnState.CONNECTED && totalDown == 0L
                    val infiniteTransition = rememberInfiniteTransition(label = "noDataRing")
                    val ringAngle by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 360f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1200, easing = LinearEasing)
                        ),
                        label = "angle"
                    )

                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
                        if (showNoDataRing) {
                            Canvas(modifier = Modifier.matchParentSize()) {
                                rotate(ringAngle) {
                                    drawArc(
                                        color = Color.Red,
                                        startAngle = 0f,
                                        sweepAngle = 270f,
                                        useCenter = false,
                                        style = Stroke(width = 8.dp.toPx())
                                    )
                                }
                            }
                        }

                        Button(
                            enabled = !isBusyGlobal,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state == ConnState.CONNECTED)
                                    Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                            ),
                            onClick = {
                                if (state == ConnState.CONNECTED) {
                                    if (pingedConfigs.isNotEmpty()) {
                                        val nextIndex = (currentIndex + 1) % pingedConfigs.size
                                        connectToIndex(nextIndex)
                                    }
                                } else {
                                    connectJob = scope.launch {
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

        if (showSplitTunnelDialog) {
            var tempSelected by remember { mutableStateOf(splitTunnelSelected) }
            val installedApps = remember {
                val pm = packageManager
                pm.getInstalledApplications(0)
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { it.packageName to pm.getApplicationLabel(it).toString() }
                    .sortedBy { it.second.lowercase() }
            }

            AlertDialog(
                onDismissRequest = { showSplitTunnelDialog = false },
                title = { Text("اپ‌های داخل تونل") },
                text = {
                    Column {
                        Text(
                            "اگه هیچی تیک نخوره، همه‌چیز از تونل رد می‌شه (پیش‌فرض). اگه چندتا اپ رو تیک بزنی، فقط همون‌ها از تونل رد می‌شن، بقیه مستقیم می‌رن.",
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(modifier = Modifier.heightIn(max = 350.dp)) {
                            items(installedApps) { (pkg, label) ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Checkbox(
                                        checked = tempSelected.contains(pkg),
                                        onCheckedChange = { checked ->
                                            tempSelected = if (checked) tempSelected + pkg else tempSelected - pkg
                                        }
                                    )
                                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        splitTunnelSelected = tempSelected
                        SplitTunnelStore.save(this@MainActivity, tempSelected)
                        showSplitTunnelDialog = false
                        if (state == ConnState.CONNECTED) {
                            // The TUN interface only picks up allow/deny lists when it's
                            // first created, so a running tunnel needs a fresh disconnect
                            // before this setting actually takes effect.
                            disconnect()
                        }
                    }) { Text("ذخیره") }
                },
                dismissButton = {
                    TextButton(onClick = { showSplitTunnelDialog = false }) { Text("انصراف") }
                }
            )
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.2f MB".format(mb)
        val gb = mb / 1024.0
        return "%.2f GB".format(gb)
    }

    private fun startTunnelService(
        config: ProxyConfig,
        fragmentEnabled: Boolean = false,
        fragmentLength: String = "10-20",
        fragmentInterval: String = "10-20"
    ) {
        val fullConfig = XrayConfigBuilder.buildFull(config, fragmentEnabled, fragmentLength, fragmentInterval)
        val intent = Intent(this, VpnTunnelService::class.java).apply {
            action = VpnTunnelService.ACTION_CONNECT
            putExtra(VpnTunnelService.EXTRA_CONFIG_JSON, fullConfig)
            putExtra(VpnTunnelService.EXTRA_SERVER_NAME, config.name)
        }
        startForegroundService(intent)
    }
}
