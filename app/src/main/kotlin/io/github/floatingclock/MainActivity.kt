package io.github.floatingclock

import android.Manifest
import android.content.Intent
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import io.github.floatingclock.time.*
import androidx.compose.runtime.saveable.rememberSaveable

class MainActivity : ComponentActivity() {
    private val clock = ClockProvider(SystemClock::elapsedRealtimeNanos)
    private val engine = TimeEngine(clock)
    private var active by mutableStateOf(false)
    private var overlayGranted by mutableStateOf(false)
    private var notificationsGranted by mutableStateOf(false)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStorage.initialize(this)
        enableEdgeToEdge()
        setContent {
            var page by rememberSaveable { mutableStateOf("home") }
            BackHandler(page != "home") { page = "home" }
            if (page == "home") HomeTheme {
                HomeScreen(engine, OverlayState.timeState, OverlayState.preferences,
                    OverlayState.running, OverlayState.requested, active, AppStorage.ready,
                    overlayGranted, notificationsGranted, OverlayState.message, AppStorage.error,
                    start = ::startOverlay, stop = ::stopOverlay, grantOverlay = ::requestOverlay,
                    grantNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    },
                    sync = { OverlayState.onSyncNow?.invoke() }, settings = { page = "settings" }, diagnostics = { page = "diagnostics" })
            } else MaterialTheme {
                Scaffold { insets ->
                    Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
                        Text(stringResource(R.string.development_version, BuildConfig.VERSION_NAME))
                        AppStorage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        TextButton(onClick = { page = "home" }) { Text("返回首页") }
                        if (page == "settings") SettingsPage() else DiagnosticsPage()
                    }
                }
            }
        }
    }

    private fun requestOverlay() {
        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        catch (_: RuntimeException) { OverlayState.message = "无法打开系统授权页，请在系统设置中授予悬浮窗权限" }
    }

    private fun refreshPermissions() {
        overlayGranted = Settings.canDrawOverlays(this)
        notificationsGranted = getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }

    internal fun startOverlay() {
        refreshPermissions()
        if (!AppStorage.ready) { OverlayState.message = "配置仍在读取，暂不能启动"; return }
        if (!overlayGranted) { OverlayState.message = "需要悬浮窗权限"; return }
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || OverlayState.requested || OverlayState.running) return
        if (OverlayState.sourceChoice == SourceChoice.HTTP) {
            try { HttpDateTimeSource(java.net.URL(OverlayState.httpUrl), clock) }
            catch (_: Exception) { OverlayState.message = "请输入有效的公开 HTTPS URL，不含认证、查询参数或片段"; return }
        }
        OverlayState.requested = true
        OverlayState.message = "正在启动"
        try { startForegroundService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)) }
        catch (_: RuntimeException) {
            OverlayState.requested = false
            OverlayState.message = "系统拒绝启动前台服务，请返回首页重试"
        }
    }

    internal fun stopOverlay() {
        OverlayState.requested = false
        stopService(Intent(this, OverlayService::class.java))
        OverlayState.message = "已停止"
    }

    override fun onResume() { super.onResume(); refreshPermissions() }
    override fun onStart() { super.onStart(); active = true }
    override fun onStop() { active = false; super.onStop() }
}

@Composable
internal fun SourceControls() {
    var expanded by remember { mutableStateOf(false) }
    var httpEditing by remember { mutableStateOf(false) }
    var url by remember(OverlayState.httpUrl) { mutableStateOf(OverlayState.httpUrl) }
    val stopped = !OverlayState.running && !OverlayState.requested
    Text("实际时间来源（所有显示平台共用）")
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = stopped) { Text(OverlayState.sourceChoice.label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SourceChoice.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, enabled = choice != SourceChoice.SYSTEM || Build.VERSION.SDK_INT >= 33,
                    onClick = {
                        if (choice == SourceChoice.HTTP) httpEditing = true
                        else { AppStorage.update { it.chooseSource(choice) }; httpEditing = false }
                        expanded = false
                    })
            }
        }
    }
    if (OverlayState.sourceChoice == SourceChoice.AUTO) {
        val saved by AppStorage.preferences.collectAsState()
        TextButton(enabled = stopped && (saved.manualSource != SourceChoice.HTTP || validHttpsUrl(saved.httpUrl) != null) &&
            (saved.manualSource != SourceChoice.SYSTEM || Build.VERSION.SDK_INT >= 33),
            onClick = { AppStorage.update { it.chooseSource(it.manualSource) } }) { Text("恢复上次手动来源：${saved.manualSource.label}") }
    }
    if (httpEditing || OverlayState.sourceChoice == SourceChoice.HTTP) {
        OutlinedTextField(value = url, onValueChange = { url = it.trim() }, enabled = stopped,
            label = { Text("公开 HTTPS URL（无密钥、无查询参数）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("仅估算 Date 秒级时间；缓存或无法确认新鲜度的响应会被拒绝。")
        TextButton(enabled = stopped && validHttpsUrl(url) != null, onClick = {
            AppStorage.update { it.chooseSource(SourceChoice.HTTP).copy(httpUrl = url) }; httpEditing = false
        }) { Text("保存并使用 HTTPS 来源") }
    }
    Text("初次自动选源可兜底；运行中失败只重试原来源。更换来源需先停止。")
    Text(OverlayState.syncDetails)
    OutlinedButton(onClick = { OverlayState.onSyncNow?.invoke() }, enabled = OverlayState.running) { Text("立即同步（最短 30 秒）") }
}

@Composable
internal fun PlatformControls() {
    val config = OverlayState.config
    Text("悬浮平台（1–3 个，按下方顺序显示）")
    PlatformId.entries.forEach { platform ->
        Row {
            val selected = platform in config.platforms
            Checkbox(checked = selected, modifier = Modifier.semantics { contentDescription = platform.label() },
                enabled = if (selected) config.platforms.size > 1 else config.platforms.size < 3,
                onCheckedChange = { AppStorage.configure { it.toggle(platform) } })
            Text(platform.label(), modifier = Modifier.padding(top = 12.dp))
        }
    }
    config.platforms.forEachIndexed { index, platform ->
        Row {
            Text("${index + 1}. ${platform.label()}", Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { AppStorage.configure { it.move(platform, -1) } }, enabled = index > 0) { Text("上移") }
            TextButton(onClick = { AppStorage.configure { it.move(platform, 1) } }, enabled = index < config.platforms.lastIndex) { Text("下移") }
        }
    }
    Row {
        DisplayMode.entries.forEach { mode ->
            TextButton(onClick = { AppStorage.configure { it.copy(mode = mode) } }, enabled = config.mode != mode) {
                Text(when (mode) { DisplayMode.FULL -> "完整"; DisplayMode.COMPACT -> "紧凑"; DisplayMode.MINIMAL -> "极简" })
            }
        }
    }
}
