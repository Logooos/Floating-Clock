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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

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
            val pages = rememberSaveableStateHolder()
            HomeTheme { pages.SaveableStateProvider(page) {
            if (page == "home") {
                HomeScreen(engine, OverlayState.timeState, OverlayState.preferences,
                    OverlayState.running, OverlayState.requested, active, AppStorage.ready,
                    overlayGranted, notificationsGranted, OverlayState.message, AppStorage.error,
                    start = ::startOverlay, stop = ::stopOverlay, grantOverlay = ::requestOverlay,
                    grantNotifications = {
                        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                    },
                    sync = { OverlayState.onSyncNow?.invoke() }, settings = { page = "settings" }, diagnostics = { page = "diagnostics" }, appearance = { page = "appearance" })
            } else {
                SecondaryPage(page, engine, active) { page = it }
            }
            } }
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
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by AppStorage.preferences.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var httpEditing by rememberSaveable { mutableStateOf(false) }
    var url by rememberSaveable(saved.httpUrl) { mutableStateOf(saved.httpUrl) }
    var pending by remember { mutableStateOf<SourceChoice?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val stopped = !OverlayState.running && !OverlayState.requested
    fun save(choice: SourceChoice) { AppStorage.update { it.chooseSource(choice).copy(httpUrl = if (choice == SourceChoice.HTTP) url else it.httpUrl) } }
    fun choose(choice: SourceChoice) { if (stopped) save(choice) else pending = choice }
    LaunchedEffect(stopped, stopping, pending) {
        if (stopped && stopping) { pending?.let(::save); pending = null; stopping = false }
    }
    Text("来源策略（所有显示行共用）")
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = !stopping) { Text(saved.sourceChoice.label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            SourceChoice.entries.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, enabled = choice != SourceChoice.SYSTEM || Build.VERSION.SDK_INT >= 33,
                    onClick = { if (choice == SourceChoice.HTTP) httpEditing = true else { choose(choice); httpEditing = false }; expanded = false })
            }
        }
    }
    Text(if (Build.VERSION.SDK_INT >= 33) "自动初选：系统网络时间 → Cloudflare → Google" else "自动初选：NTP Cloudflare → Google")
    Text("Google 使用闰秒平滑。演示仅供测试，不是真实网络校时。", style = MaterialTheme.typography.bodySmall)
    Text("实际来源：${if (OverlayState.running) homeSource(OverlayState.timeState) else "尚未选择"}")
    if (saved.sourceChoice == SourceChoice.AUTO) {
        TextButton(enabled = !stopping && (saved.manualSource != SourceChoice.HTTP || validHttpsUrl(saved.httpUrl) != null) &&
            (saved.manualSource != SourceChoice.SYSTEM || Build.VERSION.SDK_INT >= 33), onClick = { choose(saved.manualSource) }) {
            Text("恢复上次手动来源：${saved.manualSource.label}")
        }
    }
    if (httpEditing || saved.sourceChoice == SourceChoice.HTTP) {
        OutlinedTextField(value = url, onValueChange = { url = it.trim() }, label = { Text("公开 HTTPS URL（无密钥、无查询参数）") },
            singleLine = true, isError = url.isNotEmpty() && validHttpsUrl(url) == null, modifier = Modifier.fillMaxWidth())
        Text("HTTP Date 为秒级估算；毫秒位是本地推演，不代表 50ms 精度。缓存或无法确认新鲜度的响应会被拒绝。")
        TextButton(enabled = !stopping && validHttpsUrl(url) != null, onClick = { choose(SourceChoice.HTTP) }) { Text("保存 HTTPS 来源") }
    }
    Text("只有初次自动选源可兜底；运行中失败只重试原来源。切源须停止当前会话，保存后不会自动重启。")
    if (stopping) Text("正在停止会话，资源释放后保存来源…")
    if (pending != null && !stopping) AlertDialog(onDismissRequest = { pending = null }, title = { Text("停止会话并保存来源？") },
        text = { Text("悬浮窗和校时将停止。保存后需手动重新开启，不沿用旧锚点。") },
        confirmButton = { TextButton(onClick = {
            OverlayState.requested = false
            context.stopService(Intent(context, OverlayService::class.java))
            stopping = true
        }) { Text("停止并保存") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("取消") } })
}

@Composable
internal fun SecondaryPage(page: String, engine: TimeEngine, active: Boolean, navigate: (String) -> Unit) {
    Scaffold(bottomBar = { ClockNavigation(if (page == "appearance") 1 else if (page == "diagnostics") 2 else 0) {
        navigate(listOf("home", "appearance", "diagnostics")[it])
    } }) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(HomeDesign.gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AppStorage.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (page == "settings") TextButton(onClick = { navigate("home") }) { Text("返回首页") }
            when (page) {
                "appearance" -> AppearancePage(engine, active)
                "settings" -> SettingsPage()
                else -> DiagnosticsPage()
            }
        }
    }
}
